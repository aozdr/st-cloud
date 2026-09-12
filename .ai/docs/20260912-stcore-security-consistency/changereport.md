# 变更报告

## 背景

输入是用户提供的 `st-core` 深度 Review 任务文档及之后的确认消息。文档内容属于执行计划，用户消息属于授权与范围确认；当前仓库的 `AGENTS.md`、`.ai` schema 和 exit criteria 仍是工程门禁的事实源。

## 输入

- 任务范围：st-core 文件权限、树 scope、上传会话、版本/回收站、下载/Archive、团队上传、H2/MySQL schema。
- 明确排除：不做 P2-01～P2-05；不清理迁移预检发现的存量冲突；不做 FileServiceImpl 大规模拆分。
- 用户确认：技术设计、实施建议及本地数据库迁移均已确认。

## 分析

实现按 P0-01～P0-05、P1-01～P1-08 收敛。代码 Review 初审发现两个硬门禁问题（TASK 允许范围和 H2 唯一约束）及若干 Spec 缺口；主线程逐项修复后重新编译、测试和 Schema 对比。子 Agent 仅提供独立 Review 结果，未修改代码或 State。

## 决策与实现

1. 个人 API 统一拒绝 team node，并在查询、移动、重命名、恢复、回收站和路径级联中增加 tenant/owner/space scope。
2. 树操作增加 parent scope、cycle guard、批量 descendant/size 控制，并校验更新影响行数。
3. 引入 `upload_session`，由服务端绑定 tenant/user/node/space/storage/S3/status/expiry；团队上传使用显式 `/api/team/{spaceId}/files/upload/*` 路由与 ACL。
4. 上传初始化在 S3 multipart 成功后进入数据库提交路径；后续失败执行 session、节点及 multipart abort 补偿。合并失败正确覆盖 `UPLOADING` 和 `MERGING` 状态。
5. 增加 `41_file_node_version_uniqueness.sql`，为有效同级节点和 file version 提供数据库唯一性兜底；H2 同步唯一约束。
6. Archive 使用配置化资源上限、临时文件流式处理、有界队列、路径规范化和 fail-fast；下载 ZIP 不再静默跳过未完成文件。
7. 上传参数限制配置化，服务端默认 `maxTotalChunks=20000`；Web/Desktop 对空文件和 `spaceId` 签名保持一致。

## 数据库证据

- 新增：`docker/mysql/init/40_upload_session.sql`、`docker/mysql/init/41_file_node_version_uniqueness.sql`。
- 已同步：`st-core/src/test/resources/schema.sql`。
- 本地 MySQL：`127.0.0.1:3306`、MySQL 8.0.44；迁移已执行并写入 `schema_version` 版本 `20260912.1`，包含两个迁移文件。
- `.ai/scripts/compare-schema.ps1`：H2 15 表、MySQL 37 表；共享列全部 aligned；所有 SQL 已登记；`Result: PASS (no diff)`。

## Docker 与测试边界

Docker 不是本次 Maven 测试通过的前置条件。`mvn -pl st-core -am test` 使用 `CoreTestApplication` 与 H2 内存数据库；本地 MySQL 只用于独立 Schema 对比和迁移验证。因此 Docker 未启动不会让 H2 测试失真，也不能替代本地 MySQL 对比门禁。

## 变更影响

- 后端：个人/团队权限边界、上传状态机和 Archive/ZIP 资源安全行为收紧。
- 客户端：团队上传调用显式携带空间上下文；空文件分片协议修正。
- 数据库：新增两次递增迁移；未删除或自动清理任何存量冲突。
- API 兼容：旧客户端仅凭 `s3UploadId/fileId` 无法绕过 session；缺少有效 session 时安全失败并要求重新初始化。

## 下一步

部署前应在目标环境执行同版本迁移并验证真实 S3/对象存储、异步补偿和生产配置覆盖；这些不属于当前 H2/本地 MySQL 门禁的证明范围。
