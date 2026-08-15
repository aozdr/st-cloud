# TASK-FIX-M2M3-MIGRATION（迁移脚本幂等治理与编号唯一化 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-M2M3-MIGRATION`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 来源: 全量 Code Review M2/M3（迁移幂等缺口 + 编号重复）

## 目标

1. 为缺失幂等守卫的迁移脚本补 `information_schema` 存在性守卫（参照 `30_sync_change_log_event_log_id.sql` 模式），保证重复执行不报错、最终 schema 结构不变。
2. 消除迁移脚本编号重复：`09_jwt_secret.sql` 与 `09_remove_two_factor.sql` 同号。

## 修改范围

### 幂等守卫（按 30 号脚本模式改造，逐个核对内容）
- `docker/mysql/init/07_cloud_capacity.sql`
- `docker/mysql/init/08_chunk_original_size.sql`
- `docker/mysql/init/16_add_file_hidden.sql`
- `docker/mysql/init/22_team_member_pinned.sql`
- `docker/mysql/init/23_file_lock.sql`
- `docker/mysql/init/25_team_external.sql`
- `docker/mysql/init/28_file_object.sql`（已知未修复项）

### 编号唯一化
- 将 `docker/mysql/init/09_remove_two_factor.sql` 重命名为 `09b_remove_two_factor.sql`（09a=jwt_secret、09b=remove_2fa，按文件名顺序执行，消除同号歧义），文件头注释补充说明。

## 兼容策略

- 只加守卫/改名，不改变任何列、表、索引的最终结构；不改变执行语义。
- `schema_version` 版本记录由主线程最后统一 INSERT（本任务不写库）。

## 范围

- include：`docker/mysql/init/07_cloud_capacity.sql`、`08_chunk_original_size.sql`、`09_jwt_secret.sql`（只读参考）、`09_remove_two_factor.sql`（重命名）、`09b_remove_two_factor.sql`（新增）、`16_add_file_hidden.sql`、`22_team_member_pinned.sql`、`23_file_lock.sql`、`25_team_external.sql`、`28_file_object.sql`、`30_sync_change_log_event_log_id.sql`（只读参考模式）
- exclude：`st-*/` 任何代码、`st-core/src/test/resources/schema.sql`、`.ai/`（除收件箱）、创建子 Agent

## 验收标准

- 7 个目标脚本均含存在性守卫，重复执行不报错（逐脚本内容核对）
- `09_remove_two_factor.sql` 已重命名为 `09b_remove_two_factor.sql`，无同号残留
- 未改变任何最终 schema 结构

## 验证

- 如运行中的 MySQL 可连（docker），主线程跑 `.ai/scripts/compare-schema.ps1` 复核；不可连则在 changereport 注明
