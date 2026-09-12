# TASK-20260912-stcore-security-consistency-P0-04

## 任务类型

P0-04 上传会话归属与团队显式上传入口。

## 前置条件

- P0-01～P0-03 已集成并通过验证。
- 已确认技术设计、测试用例和 `upload_session` 迁移方案。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/entity/UploadSession.java`
- `st-core/src/main/java/com/stcloud/core/mapper/UploadSessionMapper.java`
- `st-core/src/main/resources/mapper/UploadSessionMapper.xml`
- `st-core/src/main/java/com/stcloud/core/service/UploadService.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/controller/FileController.java`
- `st-core/src/main/java/com/stcloud/core/dto/Upload*.java`
- `st-team/src/main/java/com/stcloud/team/controller/TeamUploadController.java`
- `st-team/src/test/java/com/stcloud/team/controller/TeamUploadControllerTest.java`
- `st-web/src/hooks/useUpload.tsx`
- `st-desktop/src/utils/file-utils.ts`
- `st-desktop/src/upload-manager.ts`
- `st-desktop/src/types.ts`
- `st-desktop/src/database.ts`
- `st-desktop/src/db/transfer-tasks.ts`
- `st-desktop/src/ipc-handlers.ts`
- `st-desktop/src/preload.ts`
- `docker/mysql/init/40_upload_session.sql`
- `st-core/src/test/resources/schema.sql`
- `st-core/src/test/java/com/stcloud/core/service/impl/UploadStateMachineIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/RelayUploadIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/ConcurrentUploadIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/UploadTransactionBoundaryTest.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadCommitManager.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadManager.java`
- `.ai/runtime/results/DISPATCH-20260912-P0-04-01.json`

允许在上述 DTO/测试目录中新增最小必要文件；不得修改其他模块。

## 禁止写入

- `docker/mysql/init/02_create_tables.sql` 及已有迁移文件（只新增递增迁移）。
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/*`，仅允许本 TASK 列明的两个文件；它们负责会话完成/失败路径的行数检查，属于会话状态机的最终一致性边界。
- Loop State、共享 changereport、无关前端组件。
- 不删除旧 upload_session、chunk 或对象数据，不自动清理冲突。

## 实现要求

1. 新增持久化 `upload_session`，绑定 tenantId、userId、fileNodeId、spaceId、storagePath、s3UploadId、状态、过期时间和客户端上传约束；uploadId 必须不可预测且唯一。
2. init/check/状态查询/chunk-url/chunk-confirm/merge/abort/relay-chunk/relay-finalize 均以服务端 session 为准，校验当前 tenant/user、节点、space 和状态；客户端传入 `s3UploadId/fileId` 只能兼容校验，不能覆盖服务端值。
3. generic `/api/file/upload/*` 只允许 personal scope；携带 team `spaceId` 必须拒绝。新增 `TeamUploadController` 使用 `/api/team/{spaceId}/files/upload/*`，每个入口先做团队 ACL，再校验 session 的 spaceId。
4. S3 外部操作在事务外执行，成功后才进入数据库提交路径；异常需保持会话可重试或明确 FAILED，不产生跨用户可操作残留。
5. 分片边界校验覆盖 fileSize、totalChunks、chunkSize、clientLimit、MD5 与 totalChunks/size 关系；chunk 记录与 session 建立关联并批量写入。
6. Web 与桌面端团队上传调用显式团队路由；个人调用保持原路由；旧客户端无法提供 session 时必须安全失败并提示重新初始化。
7. 增加中文注释说明 uploadId 归属校验、S3 标识服务端权威和事务/外部网络边界。

## 验收标准

- 泄露 uploadId、伪造 s3UploadId/fileId、跨 user/tenant/space 的后续请求均不能操作会话。
- generic personal API 不能创建或操作 team 上传；team upload 只能通过显式团队路由和 ACL。
- 成功上传、失败重试、abort、过期、并发 merge、relay 流程保持可用且幂等。
- H2 schema 与 MySQL 新迁移包含同样的 upload_session/关联约束；迁移冲突不自动删除。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行上传状态、relay、并发和团队接口测试。
- `mvn -pl st-core -am test -Dtest=SchemaConsistencyTest`
- `git diff --check`、`git diff --name-only`。
- MySQL 可用时按文档执行迁移和 `.ai/scripts/compare-schema.ps1`；不可用时记录阻塞，不伪报通过。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P0-04-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
