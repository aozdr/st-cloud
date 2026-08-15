# TASK-P1-KNOWLEDGE（知识库与代码同步 — executor/knowledge）

## 元信息

- Task ID: `TASK-P1-KNOWLEDGE`
- 归属 Agent: executor（taskType=knowledge）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review Spec P1/P2（知识库落后 + 文档管理漂移）

## 目标

让知识库与当前代码一致。**所有端点/表结构必须从实际代码与 SQL 提取，禁止臆造。**

## 修改清单（已定版）

1. `.ai/knowledge/api-reference.md` 补录（先 `rg` 实际 Controller 确认路径/方法）：
   - 上传中转端点（relay-chunk / finalize，位于 st-core Upload/Transfer 相关 Controller）
   - `UploadInitResponse` 新增字段（对照 st-core DTO）
   - 块级同步端点（block-check / block-upload，st-sync SyncBlockController）
   - 同步排除/冲突端点（3 个，st-sync）
   - 团队角色/统计端点（TeamController 新增 5 个：roles GET / role POST·PUT·DELETE / stats GET）
   - 通知端点（4 个，如有 NotificationController）
2. `.ai/knowledge/data-model.md` 补 `file_block` 表（列定义对照 `docker/mysql/init/32_file_block.sql`）。
3. `.ai/docs/20260813-block-sync/design.md`：迁移脚本编号 27→32 的表述同步为实际 `32_file_block.sql`。
4. `.ai/docs/` 根目录 `favorites-enhancement-*.md` 归档到迭代子文件夹（`20260814-favorites-archive/` 或既有迭代目录），并在原位置留说明。

## 范围

- include（写）：`.ai/knowledge/api-reference.md`、`data-model.md`、`.ai/docs/20260813-block-sync/design.md`、favorites 文档归档
- include（读）：`st-*/**`（提取契约）、`docker/mysql/init/*.sql`、`.ai/knowledge/**`、`.ai/docs/**`
- exclude：修改任何 `st-*` 业务代码、数据库脚本、`.ai/tasks`、创建子 Agent

## 验收标准

- api-reference.md 补录项逐条存在且路径/字段与实际 Controller/DTO 一致
- data-model.md 含 `file_block` 表（列与 32 号脚本一致）
- block-sync design 编号表述与实际一致
- 未改任何业务代码

## 验证

- 主线程抽查补录端点与 Controller 实际映射一致
