# Change Report — 权限模型重设计（2026-08-14）

> 各子任务按 TASK 分节追加；本文件由 Workflow Manager 合并后用于 IMPLEMENTED 判定。

## TASK-PERM-DB 数据库迁移（executor / implement）

### 背景

权限模型重设计（design.md 已确认）：团队文件夹权限与分享权限由单值枚举扩展为权限点 JSON，新增 `permissions` 列承载；原单值列保留用于迁移映射与兼容。本任务只做 DDL + 数据映射 + H2 schema 同步，不改任何既有列定义。

### 修改文件清单

| 文件 | 变更 |
|------|------|
| `docker/mysql/init/34_team_folder_permission_permissions.sql` | 新增：`team_folder_permission.permissions VARCHAR(500)`（information_schema 幂等守卫）+ 历史 `permission` 映射（-1/0/1/2） |
| `docker/mysql/init/35_file_share_permissions.sql` | 新增：`file_share.permissions VARCHAR(500)`（information_schema 幂等守卫）+ 历史 `permission` 映射（0/1/2/3） |
| `st-core/src/test/resources/schema.sql` | `file_share` 补 `permissions VARCHAR(500)` |
| `st-share/src/test/resources/schema.sql` | `file_share` 补 `permissions VARCHAR(500)` |
| `st-team/src/test/resources/schema.sql` | `team_folder_permission` 补 `permissions VARCHAR(500)` |

### 历史映射规则

- `team_folder_permission`：-1 → `{"view":false}`（仅标注，-1 不再生效）；0 → 9 权限点全 true；1 → view/upload/download/delete/rename/move/share true，manage_members/manage_settings false；2 → `{"view":true}`。
- `file_share`：0 → `{"view":true}`；1 → `{"view":true,"download":true}`；2 → `{"view":true,"upload":true}`；3 → `{"view":true,"upload":true,"download":true,"delete":true,"rename":true,"move":true}`。
- 映射 UPDATE 均带 `permissions IS NULL` 守卫，重复执行不覆盖后续已配置数据；ALTER 带 information_schema 存在性守卫，幂等可重跑。

### 验收对照

- [x] 34/35 脚本含 information_schema 幂等守卫 + 历史映射 UPDATE
- [x] 3 处 H2 schema 均含 `permissions` 列
- [x] 未改既有列定义（仅新增列）

### 测试结果

- `mvn -pl st-core test -Dtest=SchemaConsistencyTest`：3/3 通过（实体→H2、实体→init SQL、H2↔init SQL 列集对齐）。
- 迁移执行、`compare-schema.ps1`、`schema_version` 登记由主线程按验证清单执行。

### 风险与下一步

- 新列默认 NULL：旧数据已由脚本映射，新写入需由后续 Java 层（TASK-PERM-BE1/BE2）维护 `permissions` 值；迁移窗口内未映射行（异常 permission 值）保持 NULL，代码侧需按 NULL 兜底。
- 主线程执行 34/35 到 MySQL 后须运行 `compare-schema.ps1` 确认 PASS 并登记 `schema_version`（`YYYYMMDD.N` 递增）。
