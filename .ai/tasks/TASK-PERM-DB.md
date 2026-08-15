# TASK-PERM-DB（权限模型数据库迁移 — executor/implement）

## 元信息

- Task ID: `TASK-PERM-DB`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 权限模型重设计（design.md，已确认）

## 目标

新增两张表的 `permissions` JSON 列 + H2 schema 同步（幂等守卫，对齐 30 号模式）。**只加列与数据映射，不改任何既有列定义。**

## 修改（已定版）

1. 新增 `docker/mysql/init/34_team_folder_permission_permissions.sql`：
   - `ALTER TABLE team_folder_permission ADD COLUMN permissions VARCHAR(500) DEFAULT NULL COMMENT '权限点JSON：{"view":true,"upload":true,...}'`（information_schema 守卫，幂等）
   - 历史数据映射：`permission=-1 → permissions='{"view":false}'`（仅标注，规则语义为增强，-1 不再生效）；`0 → 全部 9 权限点 true`；`1 → {"view":true,"upload":true,"download":true,"delete":true,"rename":true,"move":true,"share":true,"manage_members":false,"manage_settings":false}`；`2 → {"view":true}`（查看者仅可查看）
2. 新增 `docker/mysql/init/35_file_share_permissions.sql`：
   - `ALTER TABLE file_share ADD COLUMN permissions VARCHAR(500) DEFAULT NULL COMMENT '分享权限点JSON'`（幂等守卫）
   - 历史映射：`permission=0 → {"view":true}`；`1 → {"view":true,"download":true}`；`2 → {"view":true,"upload":true}`；`3 → {"view":true,"upload":true,"download":true,"delete":true,"rename":true,"move":true}`
3. H2 schema 同步（3 处）：
   - `st-core/src/test/resources/schema.sql` 的 `file_share` 加 `permissions VARCHAR(500)`
   - `st-share/src/test/resources/schema.sql` 的 `file_share` 加 `permissions VARCHAR(500)`
   - `st-team/src/test/resources/schema.sql` 的 `team_folder_permission` 加 `permissions VARCHAR(500)`（如该 schema 无此表则补列于对应位置）

## 范围

- include：`docker/mysql/init/34_*`、`35_*`（新增）、上述 3 个 H2 schema.sql
- exclude：修改 02-33 号既有迁移脚本、任何 Java 代码、前端、创建子 Agent

## 验收标准

- 两个新脚本含 information_schema 幂等守卫与历史映射 UPDATE
- 3 处 H2 schema 含 `permissions` 列
- 未改既有列定义

## 验证

- 主线程执行迁移到 MySQL + `compare-schema.ps1` PASS + `schema_version` 登记（34/35）
