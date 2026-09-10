# Change Report — TASK-P1-TEST-PREVIEW-TEAM

## 元信息

- Task ID: `TASK-P1-TEST-PREVIEW-TEAM`
- Agent: executor（taskType=implement）
- dispatchId: `p1-testprevteam-001`
- 日期: 2026-08-14
- 来源: 全量 Code Review H7（st-preview 零测试；st-team 无集成测试）

## 背景

全量 Code Review H7 指出两个模块测试缺失：st-preview 完全没有测试（预览缩略图/转码主路径无回归保护）；st-team 仅有纯 Mockito 单测（文件夹权限），缺少覆盖空间/成员/角色/统计主流程的 H2 集成测试。本次为两个模块补齐测试设施与主路径测试，不改业务主代码。

## 修改文件清单

### st-preview（新增测试设施 + 主路径集成测试）

- 修改：`st-preview/pom.xml`（新增 `spring-boot-starter-test`、`h2`，test scope）
- 新增：`st-preview/src/test/resources/application-test.yml`（H2 MODE=MySQL）
- 新增：`st-preview/src/test/resources/schema.sql`（file_node，列对齐 02/16/23 号建表脚本）
- 新增：`st-preview/src/test/java/com/stcloud/preview/PreviewTestApplication.java`
- 新增：`st-preview/src/test/java/com/stcloud/preview/AbstractPreviewIntegrationTest.java`
- 新增：`st-preview/src/test/java/com/stcloud/preview/service/PreviewServiceIntegrationTest.java`

### st-team（新增 H2 集成测试）

- 修改：`st-team/pom.xml`（新增 `h2`，test scope；`spring-boot-starter-test` 已存在）
- 新增：`st-team/src/test/resources/application-test.yml`（H2 MODE=MySQL）
- 新增：`st-team/src/test/resources/schema.sql`（sys_user/file_node/team_space/team_member/team_invite/team_activity/notification/team_comment/team_folder_permission/team_role/team_external_config，列对齐 02 + 17~25 号脚本）
- 新增：`st-team/src/test/java/com/stcloud/team/TeamTestApplication.java`
- 新增：`st-team/src/test/java/com/stcloud/team/AbstractTeamIntegrationTest.java`
- 新增：`st-team/src/test/java/com/stcloud/team/service/TeamServiceIntegrationTest.java`

## 修改内容

### st-preview

- 测试基类沿用 st-core/st-share 范式：`@SpringBootTest` + 专用 `PreviewTestApplication`（`@EnableAutoConfiguration` 排除 Redis/Security/WebMvc/RocketMQ，`@MapperScan` 仅 core mapper，`@Import` MyBatis-Plus 租户拦截器 + 自动填充）。
- S3 全 Mock 隔离：`S3Client`/`S3Presigner`/`StorageService`/`FileService` 均为 Mock，`S3StorageConfig` 以 Mock 返回 `previewBucket`；`file_node` 走真实 H2，验证 SQL/表结构/租户隔离。
- 5 条主路径集成测试：
  1. 图片预览：headObject 404 → 生成缩略图（真实 1x1 PNG 经 ImageIO）→ putObject 上传 → presigner 返回预览 URL；
  2. 文本预览：返回内容与后缀；
  3. 视频预览：返回 storageService 下载 URL；
  4. 不支持类型：返回 `unsupported`；
  5. 非图片缩略图：回退原图 URL 且不触发 S3 探测。

### st-team

- 测试基类：`TeamTestApplication` 手动注册真实 `TeamServiceImpl`、`FolderPermissionService`、`NotificationHelper`；Mock `CloudStorageService`/`ActiveTracker`/`TeamActivityHelper`/`StringRedisTemplate`（隔离 Redis 与异步活动日志线程，避免测试抖动）；`@MapperScan` 覆盖 team/core/auth 三个 mapper 包。
- 8 条 H2 集成测试（真实 MyBatis-Plus + 租户隔离 + 自动填充）：
  1. 创建空间：空间落库 + 创建者自动成为管理员（role=0）；
  2. 空间列表：返回空间与成员数；
  3. 邀请成员：成员落库 + TEAM_INVITE 通知落库；
  4. 成员列表：返回成员及用户昵称，按角色升序；
  5. 修改成员角色：role 0→1 生效；
  6. 角色列表：返回 3 个预设角色；
  7. 自定义角色创建/删除生命周期（C2 roles 端点对应 Service）；
  8. 空间统计：存储/配额、文件数、类型分布、成员活跃度、操作统计（C2 stats 端点对应 Service）。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| `mvn -q -pl st-preview -am test` EXIT=0（≥3 条） | PASS（5 条，EXIT=0） |
| `mvn -q -pl st-team -am test` EXIT=0（集成 ≥5 条，覆盖空间/成员/角色端点含 roles/stats） | PASS（集成 8 条，EXIT=0） |
| 只改 pom 测试依赖 + src/test，不动业务主代码 | PASS（`git status` 仅涉及两模块 pom 与测试目录） |
| 未创建子 Agent | PASS |

## 测试结果

- `mvn -q -pl st-preview -am test`：EXIT=0；`PreviewServiceIntegrationTest` Tests run: 5, Failures: 0, Errors: 0, Skipped: 0。
- `mvn -q -pl st-team -am test`：EXIT=0；`TeamServiceIntegrationTest` Tests run: 8, Failures: 0, Errors: 0, Skipped: 0；既有 2 个 FolderPermissionService 单测保持通过。
- 上游模块（st-common/st-auth/st-core）测试全部保持通过。

## 风险

- 测试仅在 H2（MODE=MySQL）上运行，未覆盖 MySQL 特有语法差异；若后续表结构变更，需同步维护两模块 `src/test/resources/schema.sql`（已与 `docker/mysql/init/` 对应脚本列对齐）。
- `PreviewServiceImpl` 文本预览按平台默认字符集解码（`new String(bytes)`），测试用 ASCII 内容规避平台差异；该行为本身是生产代码既有实现，未在本次范围内改动。
- 集成测试将 `TeamActivityHelper`/`ActiveTracker` Mock 掉，活动日志异步写入与 Redis 活跃去重逻辑不在集成覆盖内（由既有单测覆盖）。

## 下一步

- 主线程复跑两个模块测试确认 EXIT=0（验证命令见上）。
- 后续如需提升覆盖，可补 Controller 层 MockMvc 端点测试与 MySQL 真实库联调。
