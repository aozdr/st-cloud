# TASK-02：块级同步修复（M4/M5/M7/M8）

## 元信息
- Task ID: `TASK-20260813-code-review-rework-02`
- 关联 State: `.ai/state/20260813-code-review-rework.yaml`
- 关联文档: `.ai/docs/20260813-code-review-rework/design.md`、`testcases.md`、`architecture-review.md`
- 归属 Agent: backend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

修复块级同步与迁移脚本的 4 项 Code Review blocker：
- **M4**：28_file_object.sql 不可重复执行。
- **M5**：block-upload 信任客户端 storagePath/s3UploadId/fileMd5/fileSize。
- **M7**：块级 multipart 无 abort/超时清理。
- **M8**：SyncBlockServiceImpl 无测试覆盖。

## 修改范围（include）

- `st-sync/src/main/java/com/stcloud/sync/service/impl/SyncBlockServiceImpl.java`
- `st-sync/src/main/java/com/stcloud/sync/controller/SyncBlockController.java`
- `st-sync/src/main/java/com/stcloud/sync/service/SyncBlockService.java`（如需新增接口方法）
- 新增 `st-sync/src/main/java/com/stcloud/sync/service/impl/BlockCheckSessionManager.java`（参照 RelayBufferManager 模式）
- `docker/mysql/init/28_file_object.sql`
- 新增 `docker/mysql/init/33_code_review_rework.sql`
- `st-core/src/test/resources/schema.sql`（若表结构变化；预计无需变化）
- `st-sync/src/test/`（新增 SyncBlockServiceImpl 测试）

## 禁止修改（exclude）

- st-web / st-desktop / st-team / st-common / st-search 目录
- UploadServiceImpl、FileObjectServiceImpl、CloudStorageServiceImpl（属 TASK-01）
- `.ai/` 流程文档（只读）
- 不提交 git

## 实施要求（决策完备，无需再问）

### M4 迁移脚本幂等（28_file_object.sql）
将 `ALTER TABLE file_node ADD COLUMN object_id ...` 改为 information_schema 列存在性守卫（严格参照 30 号脚本模式）：
```sql
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'file_node' AND COLUMN_NAME = 'object_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE file_node ADD COLUMN object_id BIGINT DEFAULT NULL COMMENT ''文件对象ID(去重引用)'' AFTER storage_path', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```
其余 CREATE TABLE IF NOT EXISTS / INSERT IGNORE / UPDATE 保持。

### 新增 33_code_review_rework.sql
登记 schema_version `20260813.2`（version_tag 唯一；内容：28 号脚本幂等加固 + 块级会话绑定与 abort）：
```sql
INSERT INTO schema_version (version_tag, iteration_name, applied_sql_files, applied_by, notes)
VALUES ('20260813.2', 'Code Review 修复迭代', '28_file_object.sql,33_code_review_rework.sql', 'codex-agent', '28 号幂等加固；块级会话绑定 + block-abort');
```
（若该版本已存在，用 INSERT IGNORE 防重。）

### M5 会话绑定（BlockCheckSessionManager + SyncBlockServiceImpl）
1. 新增 `BlockCheckSessionManager`（st-sync 内，内存 ConcurrentHashMap，参照 `.ai/docs/20260813-code-review-fix/design.md` 的 RelayBufferManager 模式）：
   - 会话字段：`s3UploadId / storagePath / fileMd5 / fileSize / totalBlocks / blocks(List<BlockHash>) / userId / tenantId / fileNodeId / lastActiveMs / expireAt`。
   - 方法：`create(...)`、`get(s3UploadId)`、`remove(s3UploadId)`、`cleanupExpired()`（@Scheduled，参照 RelayBufferManager 的 `scheduledCleanup`，超时 abort S3 multipart + 移除）。
   - abort 依赖 `UploadStorageManager.abortMultipart(storagePath, s3UploadId)`（已存在，注入即可）。
2. `blockCheck`：校验通过并初始化 multipart 后，`blockCheckSessionManager.create(...)`（记录服务端生成的 storagePath、请求的 fileMd5/fileSize/blocks、当前 userId/tenantId、nodeId）。
3. `blockUpload` 重构为会话驱动：
   - 仅信任 `fileNodeId` 与 `s3UploadId`；`session = blockCheckSessionManager.get(s3UploadId)`，为空抛 `BUSINESS_ERROR("块级上传会话不存在或已过期")`。
   - 校验 session.fileNodeId == request.fileNodeId、session.userId == 当前用户（或租户管理员）、session.tenantId == 当前租户，否则 PERMISSION_DENIED。
   - storagePath、fileMd5、fileSize、blocks 一律取 session（忽略/不信任请求体中的同名字段，可保留字段用于兼容但服务端不使用）。
   - 异常/前置校验失败路径：abort multipart（session.storagePath, s3UploadId）+ remove 会话（参照 relay 失败清理）。
   - 成功后 remove 会话。
4. 新增 `POST /api/sync/block-abort` 端点（SyncBlockService 接口 + Controller，参数 fileNodeId + s3UploadId）：abort S3 + 清理会话；权限 isAuthenticated + owner/租户管理员（服务层校验，与 blockUpload 一致）。

### M8 测试（st-sync/src/test）
新增 `SyncBlockServiceImplTest`（Mockito 单测，参照既有 SyncChangeMessageConsumerTest 风格）覆盖：
- block-check：可复用块/缺失块判定、会话创建。
- block-upload 无会话 → 拒绝。
- block-upload 会话归属不符（他人 fileNodeId/租户）→ PERMISSION_DENIED。
- block-upload 成功流程：uploadPartCopy + complete + 去重命中/未命中 + 版本+1 + 块布局写入 + 事件 + 配额。
- block-upload 失败/配额不足 → abort 调用 + 会话清理。
- block-abort：abort + 会话清理。
如环境支持（H2 可加载 st-core schema.sql），补充集成测试；否则 Mockito 覆盖主路径即可，需在 changereport 说明。

## 验收标准

- 28 号脚本二次执行不报错。
- 33 号脚本可执行且版本记录唯一。
- block-upload 仅凭会话工作；篡改客户端 storagePath/md5/size 不影响服务端行为。
- block-abort 与超时清理均 abort S3 并移除会话。
- SyncBlockServiceImpl 主路径测试通过。

## 验证命令（必须真实执行并记录结果）

```text
mvn -pl st-sync -am test
mvn -pl st-core -am test -Dtest='SchemaConsistencyTest'
```
（compare-schema 与 MySQL 迁移由 Workflow Manager 在 TEST_PASS 阶段统一执行。）

## 输出要求

按 Agent 输出规范回复，并追加 `.ai/docs/20260813-code-review-rework/changereport.md` 的 TASK-02 章节。
