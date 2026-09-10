# Change Report — TASK-P1-KNOWLEDGE（知识库与代码同步）

- Task ID: `TASK-P1-KNOWLEDGE`
- Agent: executor（taskType=knowledge）
- dispatchId: p1-knowledge-002
- 日期: 2026-08-14

## 背景

全量 Code Review Spec P1/P2 发现知识库落后于代码（api-reference 缺 relay/block/同步排除冲突/团队角色/通知端点，data-model 缺 file_block 表，block-sync design 迁移脚本编号漂移，favorites 文档散落在 `.ai/docs` 根目录）。本任务将知识库与当前代码对齐，全部端点/表结构从实际 Controller 与 SQL 提取，未臆造。

## 输入

- Dispatch Envelope：`.ai/dispatch/archived/inbox-p1-knowledge-002.md`
- TASK 文件：`.ai/tasks/TASK-P1-KNOWLEDGE.md`
- 契约来源（读）：`st-core/src/main/java/com/stcloud/core/controller/FileController.java`、`st-core/.../dto/UploadInitResponse.java`、`st-core/.../dto/RelayChunkResponse.java`、`st-sync/.../controller/SyncController.java`、`st-sync/.../controller/SyncBlockController.java`、`st-team/.../controller/TeamController.java`、`st-team/.../controller/NotificationController.java`、`docker/mysql/init/32_file_block.sql`

## 分析

逐一对照实际代码确认端点路径、方法与响应字段：

- 中转上传：`FileController`（`@RequestMapping("/api/file")`）新增 `POST /api/file/upload/relay-chunk`（接收小块，限速 pacing）、`POST /api/file/upload/relay-finalize`（完成末片并合并）
- `UploadInitResponse` 新增字段：`transferMode`、`relayChunkSize`、`relayRateKb`（均来自 st-core DTO，前端 `st-web/src/types/index.ts` 已同步 `transferMode`/`relayChunkSize`）
- 同步排除/冲突：`SyncController` 新增 3 个排除端点（GET/POST/DELETE `/api/sync/roots/{rootId}/exclusions...`）+ 1 个冲突策略端点（PUT `/api/sync/roots/{rootId}/conflict-strategy`）。TASK 所述“3 个”为排除端点，实际代码另含 conflict-strategy，全部补录以保证一致性
- 块级同步：`SyncBlockController` 新增 `POST /api/sync/block-check`、`POST /api/sync/block-upload`
- 团队角色/统计：`TeamController` 的 roles GET / role POST·PUT·DELETE / stats GET 共 5 个端点（roles GET、stats GET 文档已有，补 role 三件套）
- 通知：`NotificationController`（st-team，`@RequestMapping("/api/notification")`）4 个端点：unread-count / list / 单条已读 / 全部已读
- 数据模型：`file_block` 表按 `32_file_block.sql` 逐列对齐（9 列 + `idx_node_ver` 索引），并在实体关系补充 `file_node 1───* file_block`
- design 编号：`.ai/docs/20260813-block-sync/design.md` 中 3 处 `27_file_block.sql` 全部改为 `32_file_block.sql`

## 决策

按 TASK 修改清单逐项落实，只改 4 类文档资产：

1. `.ai/knowledge/api-reference.md`：上传流程补 relay 两端点；上传初始化下新增 `UploadInitResponse` 字段表；同步模块补排除/冲突/块级 6 端点；团队模块补 role 三件套；新增「通知模块（st-team）」4 端点
2. `.ai/knowledge/data-model.md`：其他表新增 `file_block`（32_file_block.sql），实体关系新增 file_node→file_block
3. `.ai/docs/20260813-block-sync/design.md`：迁移脚本编号 27→32（3 处）
4. favorites 文档归档：`.ai/docs/favorites-enhancement-{requirement,design,testcases}.md` 移入 `20260814-favorites-archive/`，原位置留 `favorites-enhancement-README.md` 说明

## State Delta

- api-reference.md：补录 relay-chunk / relay-finalize、UploadInitResponse 3 个新字段、同步排除/冲突 4 端点、block-check / block-upload、团队 role POST·PUT·DELETE、通知 4 端点
- data-model.md：新增 file_block 表（列与 32 号脚本一致）+ 实体关系
- 20260813-block-sync/design.md：`27_file_block.sql` → `32_file_block.sql`（3 处）
- `.ai/docs/20260814-favorites-archive/`：3 个 favorites 文档归档完成；根目录留 README 指引
- 业务代码零改动（git 状态中 st-*/docker 相关变更均为工作区既有改动，本任务未触碰）

## 风险

- 工作区存在大量既有未提交改动，本任务仅修改 `.ai/knowledge/api-reference.md`、`.ai/knowledge/data-model.md`、`.ai/docs/20260813-block-sync/design.md`，并新增归档目录与 README，未触碰任何业务代码
- 同步模块实际排除/冲突端点为 4 个（TASK 描述 3 个），已按代码实况补录全部 4 个
- `.ai/knowledge/loop-dryrun-favorites.md` 等文档引用旧 favorites 路径，属 read 范围未改，由归档 README 指引兜底

## 下一步

主线程抽查补录端点与 Controller 映射一致性（逐项比对已在上文列出），确认后标记本环节 exitCriteria done。

## 变更影响

- api-reference/data-model 与代码一致，后续开发/评审可据此文档工作
- favorites 文档归档后根目录仅留说明，文档管理规范对齐
- 对业务代码、数据库脚本、测试无任何影响
