# 程序设计文档：Code Review 修复迭代

## 后端

### FileObjectMapper / FileObjectServiceImpl（C1）
- 新增 `selectDeletedByTenantAndMd5(tenantId, md5)`：`SELECT * FROM file_object WHERE tenant_id=? AND md5=? AND deleted=1 LIMIT 1`。
- 新增 `reviveDeleted(tenantId, md5, size, storagePath)`：原子 UPDATE 复活，返回受影响行数。
- `acquire` 冲突分支：insertIgnore=0 且 active 缺失 → 查 deleted 行 → 复活并返回；复活 0 行则抛业务异常（FILE_UPLOAD_FAILED），不再返回 null 导致 NPE。

### UploadServiceImpl（M1/M2/M3）
- M1：mergeChunks 读取 node 后补 `!userId.equals(node.getOwnerId()) && !UserContext.canAccessTenant()` → PERMISSION_DENIED。
- M2：merge 事务内顺序调整为 预检/预扣配额 → completeMultipart → 去重 → markCompleted → 事件；consumeQuota 失败回滚事务，S3 完成失败 catch 中 abort + 既有 handleMergeFailure。
- M3：relayFinalize 注入自身代理 `@Lazy @Resource private UploadService self;`，调用 `self.mergeChunks(...)` 保持 @Transactional。

### CloudStorageServiceImpl（M6）
- checkCapacity 正常路径：`getCloudTotalCapacity`（无锁）+ sumCloudStorageUsed；剩余 < 阈值（`total*0.1` 与 `delta*2` 取较大）时 `getCloudTotalCapacityForUpdate` 复核。

### SyncBlockServiceImpl + BlockCheckSessionManager（M5/M7）
- 新增 `BlockCheckSessionManager`：create/get/remove/cleanupExpired；超时（2h）@Scheduled abort S3（UploadStorageManager.abortMultipart）。
- blockCheck：成功初始化 multipart 后写会话；响应不变。
- blockUpload：以 fileNodeId+s3UploadId 取会话，校验 tenant/user 归属；storagePath/md5/size/blocks 以会话为准；前置校验失败或异常时 abort multipart 并清理会话；成功后 remove 会话。
- 新增 `POST /api/sync/block-abort`（参数 fileNodeId+s3UploadId）：abort + 清理会话。

### 迁移脚本（M4/C1）
- 28_file_object.sql：`ADD COLUMN object_id` 补 information_schema 列存在性守卫（参照 30 号脚本模式）。
- 新增 `33_code_review_rework.sql`：登记 schema_version `20260813.2`（28 号修改 + 33 号）；无表结构变化（C1 靠 acquire 复活）。
- st-core schema.sql：无表变化，仅确认一致。

## 前端

### st-web useUpload.tsx / UploadPanel.tsx / types（FE-S1）
- UploadTaskStatus 增加 `cancelling`。
- relay 循环前建立取消标记（ref Map taskId→flag）；循环中每小块检查标记，命中则中止并调 abort；UploadPanel X 按钮：uploading/merging relay → set cancelling → abort → 移除。
- `removeTask` 兼容直接移除已完成/失败任务。

### st-desktop sync-engine.ts（FE-S2）
- reconcileFolder：本地存在且无 sync_state → 保留本地，`syncLog('info', ...)`，不下载覆盖；md5 不一致且有 state 时维持既有 localChanged 逻辑。

## API 契约

- `POST /api/sync/block-abort`，参数：`fileNodeId`(Long)、`s3UploadId`(String)；返回 `Result<Void>`；权限：isAuthenticated + owner/租户管理员。
- `POST /api/sync/block-upload` 请求体兼容不变；服务端以会话为准。
- `DELETE /file/upload/abort`（既有）供 Web relay 取消复用。
