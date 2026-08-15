# TASK-20260813-share-expiry-01（后端）

## 元信息

- Task ID: `TASK-20260813-share-expiry-01`
- 关联任务 State: `.ai/state/20260813-share-expiry.yaml`
- 关联文档: `.ai/docs/20260813-share-expiry/design.md` / `testcases.md` / `requirement.md`
- 归属 Agent: backend-engineer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

在 st-share 后端完成分享过期时间的加固：新增"过期时间必须晚于当前时间"校验、更新接口支持清除过期时间（恢复永久）、补齐 H2 测试 schema 与 st-share 集成测试基础设施，并编写覆盖过期全链路的自动化测试。

## 修改范围

### 模块/目录

- `st-share/src/main/java/com/stcloud/share/dto/UpdateShareRequest.java`
- `st-share/src/main/java/com/stcloud/share/dto/ShareAccessVO.java`
- `st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`
- `st-share/pom.xml`
- `st-share/src/test/`（新增：application-test.yml、schema.sql、ShareTestApplication、AbstractShareIntegrationTest、ShareServiceImplExpiryIntegrationTest、契约测试）
- `st-core/src/test/resources/schema.sql`（追加 `file_share` 表）

### 接口/数据库

- `PUT /api/share/{shareId}`：`UpdateShareRequest` 新增可选字段 `clearExpireAt`（Boolean，null=false）。`clearExpireAt=true` → `expire_at` 置 NULL（永久）；否则 `expireAt` 非空时先校验"晚于当前时间"再更新。老客户端不传该字段行为不变（向后兼容）。
- `POST /api/share/create`：`expireAt` 非空时必须晚于当前时间，否则 `BusinessException(BAD_REQUEST, "过期时间必须晚于当前时间")`。
- `ShareAccessVO` 删除 `isExpired` 字段及其赋值。
- 数据库：MySQL 无 DDL 变更；H2 测试 schema 补齐 `file_share` 表（列对齐 MySQL `02_create_tables.sql` 中 file_share 定义）。

## 禁止修改范围

- 不得修改 `ShareController` 路由、权限注解与既有接口路径。
- 不得修改 `FileShare` 实体结构、`CreateShareRequest`/`ShareVO` 既有字段。
- 不得修改 `st-web/**`、`st-desktop/**`、`st-team/**` 等其它模块代码。
- 不得新增/修改 MySQL `docker/mysql/init/` 脚本（无 DDL 变更）。
- 不得重构与本次任务无关的代码（如团队邀请时间处理）。

## 验收标准

- [ ] `createShare` 支持未来时间与永久（null）；传过去时间返回 `BAD_REQUEST`。
- [ ] `updateShare` 支持修改 `expireAt`（未来时间校验）与 `clearExpireAt=true` 清除过期。
- [ ] 过期分享的 `accessShare`/`getDownloadUrl`/`listShareFiles`/`streamShareFile` 均返回 `SHARE_EXPIRED(3002)`。
- [ ] `ShareAccessVO.isExpired` 已删除（含前端类型同步由前端任务负责）。
- [ ] `st-share/src/test/` 集成测试覆盖 testcases.md 的 S1-S14 与 U1-U2。
- [ ] `st-core/src/test/resources/schema.sql` 含 `file_share` 表。
- [ ] 核心逻辑（过期校验、状态流转）补充中文注释。

## 测试要求

- 集成测试使用 H2（MODE=MySQL）+ 真实 `FileShareMapper`/`FileNodeMapper`，`StorageService`/`DownloadService`/`FileService` 以 Mock 隔离（参考 `st-core` 的 `FileServicePermissionIntegrationTest` 模式）。
- 测试方法级 `@Transactional` 回滚，无需手工清理。
- 运行 `mvn -pl st-share -am test` 与 `mvn test` 全绿。

## 输出要求

- 编码完成后将变更情况追加到 `.ai/docs/20260813-share-expiry/changereport.md`（修改文件清单 / 与验收标准对照 / 测试结果 / 风险）。
- 返回 State Delta：列出改动文件、测试执行结果、未覆盖项。
