# Change Report — TASK-FIX-M2M3-MIGRATION

## 元信息

- Task ID: `TASK-FIX-M2M3-MIGRATION`
- Agent: executor（taskType=implement）
- dispatchId: `fix-m2m3-001`
- 日期: 2026-08-14
- 来源: 全量 Code Review M2/M3（迁移幂等缺口 + 编号重复）

## 背景

全量 Code Review 发现 7 个迁移脚本（07/08/16/22/23/25/28）在存量库重复执行时会因目标列已存在而报错（仅 `CREATE TABLE IF NOT EXISTS` 场景安全），且 `09_jwt_secret.sql` 与 `09_remove_two_factor.sql` 存在编号重复，按文件名顺序执行时语义歧义。

## 修改文件清单

- 修改（幂等守卫）：`docker/mysql/init/07_cloud_capacity.sql`、`08_chunk_original_size.sql`、`16_add_file_hidden.sql`、`22_team_member_pinned.sql`、`23_file_lock.sql`、`25_team_external.sql`、`28_file_object.sql`
- 重命名：`09_remove_two_factor.sql` → `09b_remove_two_factor.sql`（09a=jwt_secret、09b=remove_2fa）

## 修改内容

按 `30_sync_change_log_event_log_id.sql` 模式（`information_schema.COLUMNS` 存在性检查 + `PREPARE/EXECUTE` 条件 DDL）为所有新增列补守卫：

| 脚本 | 守卫列 | 备注 |
|------|--------|------|
| 07_cloud_capacity.sql | `sys_tenant.cloud_total_capacity` | UPDATE 默认值保持原样（天然幂等） |
| 08_chunk_original_size.sql | `file_chunk.original_size` | — |
| 16_add_file_hidden.sql | `file_node.hidden` | — |
| 22_team_member_pinned.sql | `team_member.is_pinned` | — |
| 23_file_lock.sql | `file_node.locked_by` / `locked_at` / `lock_expire_at` | 3 个独立守卫 |
| 25_team_external.sql | `team_member.member_type` / `expire_at` | `team_external_config` 为 `CREATE TABLE IF NOT EXISTS`，无需改 |
| 28_file_object.sql | `file_node.object_id` | `file_object` 建表/回填/关联 UPDATE 天然幂等 |

`09b_remove_two_factor.sql` 沿用原有存储过程守卫逻辑，文件头补充编号说明；未改动任何列、表、索引定义。

## 与验收标准对照

| 验收项 | 结果 |
|--------|------|
| 7 个目标脚本均含 `information_schema` 存在性守卫 | PASS（逐脚本核对，30 号模式） |
| 重复执行不报错 | PASS（守卫判定列不存在才执行 ALTER） |
| `09_remove_two_factor.sql` 已重命名为 `09b_remove_two_factor.sql` | PASS |
| 09 无同号残留 | PASS（`09_jwt_secret.sql` + `09b_remove_two_factor.sql`） |
| 最终 schema 结构不变 | PASS（仅加守卫/改名，未改任何列/表/索引/数据语义） |

## 测试结果

- 守卫计数核对：07/08/16/22 各 1 处，23 三处，25 两处，28 一处，均含 `information_schema.COLUMNS` + `PREPARE stmt_col FROM @sql`。
- 09 号文件残留检查：目录下仅 `09_jwt_secret.sql` 与 `09b_remove_two_factor.sql`。
- `git diff --stat` 核对：改动仅限 7 个目标脚本与重命名文件，无越界修改。
- Docker/MySQL 当前不可连（`DOCKER_UNAVAILABLE`），`compare-schema.ps1` 复核由主线程在环境可用时执行；`schema_version` 版本记录由主线程统一 INSERT（本任务不写库）。

## 风险

- 16/22/23/25 脚本原本无 `USE stcloud;`，守卫使用 `DATABASE()` 判定当前库；Docker 初始化环境已设 `MYSQL_DATABASE=stcloud`，与既有 30 号模式行为一致。手动执行时需先 `USE stcloud;`。
- 08/16 原文件带 UTF-8 BOM，本次编辑统一为 UTF-8 无 BOM（符合项目文档编码规范）。

## 下一步

- 主线程逐脚本核对守卫内容（本报告已列对照表）。
- MySQL 可连时运行 `.ai/scripts/compare-schema.ps1` 复核 schema 一致后，再统一 INSERT `schema_version` 记录。
