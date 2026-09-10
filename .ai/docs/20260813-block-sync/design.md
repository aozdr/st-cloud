# 迭代 5 - 块级增量同步 技术设计文档

## 背景

迭代 1-4 已完成同步基础（可靠游标、实时推送、选择性同步、冲突策略、状态面板）。迭代 5 实现块级增量同步：大文件修改后仅上传变化的块，而非整个文件，提升大文件同步效率。

## 现状分析

### 存储栈
- MinIO（S3 兼容，AWS SDK v2，pathStyleAccess，endpoint 127.0.0.1:9000）
- `StorageService`：支持 multipart upload（init/presign/complete/abort/listParts）+ downloadObjectRange，**未暴露 UploadPartCopy**
- `file_object`：按 md5 去重的物理对象，refCount 引用计数
- `file_chunk`：分片上传的临时记录（合并后 status=2 保留），按 uploadId 组织，非按版本持久化
- `FileVersion`：历史版本快照（storagePath + fileMd5）

### S3 multipart 约束
- 每块最小 **5MB**（最后一块除外）
- 最多 10000 块
- UploadPartCopy 支持跨对象复制块（MinIO 完全兼容）

### 同步现状
- `sync-engine.ts uploadFile()`：统一走 `startUpload`（init+merge 全量上传或秒传）
- 无块级哈希缓存，文件修改必全量重传

## 方案设计

### 核心思路
文件按固定块大小（5MB）分块计算 md5，上传前与服务端该文件当前版本的块布局对比，仅上传变化块；服务端用 UploadPartCopy 复用未变块 + 新块组装新版本。

### 后端改动

**1. 新增 `file_block` 表**（持久化块布局，docker/mysql/init/32_file_block.sql）
```sql
CREATE TABLE IF NOT EXISTS file_block (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT NOT NULL,
  file_node_id  BIGINT NOT NULL COMMENT '文件节点ID',
  version       INT NOT NULL COMMENT '文件版本号(对齐 file_node.version)',
  block_index   INT NOT NULL COMMENT '块序号(0-based)',
  block_md5     VARCHAR(64) NOT NULL COMMENT '块MD5',
  block_size    BIGINT NOT NULL COMMENT '块大小(字节)',
  storage_path  VARCHAR(512) NOT NULL COMMENT '块在S3的key(整文件对象路径)',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_node_ver (file_node_id, version, block_index)
);
```
> 块存储路径 = 整文件对象路径（不单独存块对象），复用通过 UploadPartCopy 从整对象按字节范围复制。

**2. StorageService 扩展**：新增 `uploadPartCopy`
```java
// 从源对象复制字节范围到目标 multipart 的指定 partNumber
void uploadPartCopy(String sourceKey, long rangeStart, long rangeEnd,
                    String destKey, String s3UploadId, int partNumber);
```
实现用 AWS SDK v2 `UploadPartCopyRequest`。

**3. SyncBlockController + SyncBlockService**
- `POST /api/sync/block-check`：入参 `BlockCheckRequest { fileNodeId, fileMd5, fileSize, blockSize, blocks: [{index, md5, size}] }`；对比该 file_node 当前版本的 file_block 记录（含整文件 MD5 判定），返回 `BlockCheckResponse { s3UploadId, storagePath, reusableBlocks: [{blockIndex, sourceKey, rangeStart, rangeEnd}], missingBlocks: [{blockIndex, presignedUrl}] }`
- `POST /api/sync/block-upload`：入参 `BlockUploadRequest { fileNodeId, s3UploadId, storagePath, fileMd5, fileSize, blockSize, totalBlocks, blocks: [{index, md5, size}] }`
  - 对 reusableBlocks 调 uploadPartCopy（按 sourceKey + rangeStart/rangeEnd 复制未变块）
  - 缺失块由客户端用 block-check 返回的 `missingBlocks[].presignedUrl` 直传 S3（不走后端中转）
  - completeMultipartUpload 组装新版本对象
  - snapshotCurrentVersion + 更新 file_node + 写 file_block(新版本) + 发 SyncChangeEvent(UPDATE)

### 桌面端改动

**1. database.ts 新增 `sync_block_hash` 表**
```sql
CREATE TABLE IF NOT EXISTS sync_block_hash (
  root_id     TEXT NOT NULL,
  rel_path    TEXT NOT NULL,
  block_index INTEGER NOT NULL,
  block_md5   TEXT NOT NULL,
  block_size  INTEGER NOT NULL,
  PRIMARY KEY (root_id, rel_path, block_index)
);
```
+ getBlockHashes / setBlockHashes 函数

**2. utils/block-hash.ts**：按 5MB 分块计算 md5

**3. sync-engine.ts uploadFile 路径分叉**
```
if (文件大小 >= 8MB && existingNodeId != null) {
  // 块级：算块哈希 -> block-check -> presign新块上传 -> block-upload组装 -> 存块哈希
  // 失败回退全量
} else {
  // 全量上传（现有逻辑）
}
```
> 仅更新已有文件走块级；新文件走全量（无历史块可复用）。

## 修改范围

| 层 | 文件 | 改动 |
|----|------|------|
| 后端 | docker/mysql/init/32_file_block.sql | 新增 file_block 表 |
| 后端 | st-core schema.sql | 同步 H2 |
| 后端 | StorageService.java + Impl | 新增 uploadPartCopy |
| 后端 | st-sync: SyncBlockController/Service/DTO | 新增 2 API |
| 后端 | FileBlock 实体/Mapper | 新增 |
| 后端 | UploadServiceImpl | block 组装后发 SyncChangeEvent(UPDATE)（已有 publishUpdated） |
| 桌面端 | database.ts | sync_block_hash 表 + 函数 |
| 桌面端 | utils/block-hash.ts | 新增块哈希工具 |
| 桌面端 | sync-engine.ts | upload 路径分叉 |

## 关键约束与风险

1. **块大小 5MB**：S3 multipart 最小块约束；与原计划"8MB 阈值"的关系：8MB 是**文件大小阈值**（>=8MB 走块级），块大小用 5MB
2. **块对齐**：客户端与服务端块大小必须一致（5MB），否则无法复用；服务端 block-check 时校验块大小
3. **文件大小变化**：最后一块大小不同，需重新上传最后一块（block-check 自然识别为 missing）
4. **块级失败回退**：block-check/block-upload 任一步失败，catch 后回退全量上传
5. **新文件不走块级**：无历史版本，块级无意义；仅 existingNodeId != null 时走块级
6. **并发安全**：file_block 按 (file_node_id, version) 隔离，版本递增
7. **迁移**：历史文件无 file_block 记录，首次更新走全量上传并补建块布局；不影响读取

## 验证方式

- 后端 mvn compile + H2 测试
- 桌面端 tsc --noEmit
- 手动：>8MB 文件小修改，观察仅上传变化块（日志可见 block-check reusable 数）
- schema 一致性 compare-schema.ps1

## 待确认决策点

1. 块大小 5MB（S3 约束下限，块数多但复用粒度细）vs 8MB（块数少但首块即超约束需特殊处理）？推荐 **5MB**
2. 文件阈值 8MB（原计划）保持？推荐保持
3. file_block 表是否接受？需迁移脚本 32_file_block.sql + H2 同步
