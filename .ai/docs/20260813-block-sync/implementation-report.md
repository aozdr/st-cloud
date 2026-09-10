# 迭代 5 - 块级增量同步 实现报告

## 背景

迭代 1-4 已完成同步基础。迭代 5 实现块级增量同步：大文件（>=8MB）修改后仅上传变化块而非整个文件。

## 实现内容

### 后端

| 文件 | 改动 |
|------|------|
| `docker/mysql/init/32_file_block.sql` | 新增 file_block 表（块布局持久化） |
| `st-core/.../schema.sql` | H2 同步 file_block 表 |
| `st-core/.../StorageService.java` | 新增 uploadPartCopy 接口方法 |
| `st-core/.../StorageServiceImpl.java` | 实现 uploadPartCopy（AWS SDK UploadPartCopyRequest） |
| `st-sync/.../entity/FileBlock.java` | 新增实体 |
| `st-sync/.../mapper/FileBlockMapper.java` | 新增 Mapper |
| `st-sync/.../dto/BlockCheckRequest.java` | 块检查请求 DTO |
| `st-sync/.../dto/BlockCheckResponse.java` | 块检查响应 DTO（可复用块+缺失块预签名URL） |
| `st-sync/.../dto/BlockUploadRequest.java` | 块组装请求 DTO |
| `st-sync/.../dto/BlockUploadResponse.java` | 块组装响应 DTO |
| `st-sync/.../service/SyncBlockService.java` | 服务接口 |
| `st-sync/.../service/impl/SyncBlockServiceImpl.java` | 服务实现（block-check + block-upload） |
| `st-sync/.../controller/SyncBlockController.java` | 2 个新 API |

### 桌面端

| 文件 | 改动 |
|------|------|
| `st-desktop/src/database.ts` | 新增 sync_block_hash 表 + getBlockHashes/setBlockHashes/deleteBlockHashes |
| `st-desktop/src/utils/block-hash.ts` | 新增块哈希计算工具（5MB 分块 MD5） |
| `st-desktop/src/sync-engine.ts` | 新增 uploadFileBlockLevel 方法 + uploadFile 路径分叉 |

### API 契约

- `POST /api/sync/block-check`：客户端发送文件块哈希列表，服务端对比当前版本块布局，初始化 multipart，返回可复用块（UploadPartCopy）+ 缺失块预签名 URL
- `POST /api/sync/block-upload`：客户端上传完缺失块后调用，服务端复制可复用块 + 合并 multipart + 更新文件节点 + 写块布局 + 发 SyncChangeEvent(UPDATE)

### 块级同步流程

1. 客户端检测到文件变更（>=8MB 且为更新已有文件）
2. 按 5MB 分块计算 MD5 + 全文件 MD5
3. 调用 block-check：服务端对比块布局，返回可复用/缺失块
4. 客户端上传缺失块到预签名 URL（直传 S3）
5. 调用 block-upload：服务端 UploadPartCopy 复用块 + 合并 + 更新元数据
6. 客户端缓存块哈希 + 更新 sync_state
7. 失败回退全量上传

### 数据库变更

- 新增 `file_block` 表（版本化块布局）
- 迁移脚本：`32_file_block.sql`
- schema_version：`20260813.1`
- H2 schema 同步完成

## 验证结果

- 后端 `mvn compile` 通过
- H2 `SchemaConsistencyTest` 通过
- `compare-schema.ps1` PASS（file_block 9 列对齐）
- Web `npm run build` 通过
- 桌面端 `tsc --noEmit` 仅预存错误（sync-engine.ts 零错误）

## 风险

- 历史文件无 file_block 记录，首次块级更新时全部块为缺失（等价全量上传），之后正常复用
- 块级失败自动回退全量上传，不影响同步可靠性
- 新文件不走块级（无历史块可复用）
