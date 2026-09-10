# Change Report：Schema 一致性修复 + 严谨性增强

> 关联: 审查遗留建议 Loop（`.ai/state/20260812-review-followups.yaml`）后续修复

## 背景
全量测试 124 用例通过后，运行时 `FileServiceImpl.listDirectory` 报 `Unknown column 'object_id' in 'field list'`。
根因：MySQL 数据库未执行迁移 26/27/28/29/30，导致 `file_node` 缺 `object_id`、`sync_change_log`/`file_object`/`event_log` 表缺失。
H2 测试 schema.sql 已含全部列，故单测全绿但线上崩溃 -- 测试不严谨。

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `docker/mysql/init/27_sync_exclusion_conflict.sql` | 修改 | 唯一键 `uk_root_path` 改用前缀索引 `relative_path(765)`，修复 utf8mb4 下超 3072 字节限制 |
| `st-core/src/test/.../schema/SchemaConsistencyTest.java` | 新增 | 三层 schema 一致性校验：实体字段 ↔ schema.sql ↔ MySQL init SQL |

## 数据库迁移修复（已应用到运行中 MySQL）
| 迁移 | 状态 | 说明 |
|------|------|------|
| 26_sync_change_log.sql | ✅ 已执行 | 创建 sync_change_log 表（含 event_log_id），sync_root 加 conflict_strategy/last_sync_at |
| 27_sync_exclusion_conflict.sql | ✅ 已执行（修复后） | 创建 sync_exclusion/sync_conflict 表 |
| 28_file_object.sql | ✅ 已执行 | file_node 加 object_id 列，创建 file_object 表 |
| 29_event_log.sql | ✅ 已执行 | 创建 event_log 表 |
| 30_sync_change_log_event_log_id.sql | ✅ 已执行 | sync_change_log 加 uk_event_log_id 唯一索引 |

## SchemaConsistencyTest 设计
三层校验（均在 `mvn test` 中自动执行，无需 MySQL 连接）：
1. **实体字段 -> schema.sql**：反射扫描实体 `@TableName` + 字段（含 BaseEntity 继承），snake_case 转换后校验 H2 schema.sql 有对应列
2. **实体字段 -> MySQL init SQL**：解析 `docker/mysql/init/*.sql` 的 CREATE TABLE + ALTER TABLE ADD COLUMN，校验实体字段有对应列
3. **schema.sql ↔ init SQL 列集对齐**：共有表的列集差异告警（WARN 不硬失败）

此测试防止「实体新增字段后仅更新 H2 schema.sql 而漏写 MySQL 迁移」导致线上 Unknown column 报错。

## 测试结果
- SchemaConsistencyTest：3 用例全通过
- 全量回归：5 模块 127 用例 0 失败 0 错误
- verify-loop PASS

## 风险
- SchemaConsistencyTest 仅覆盖 st-core 实体（st-sync 实体从 st-core 测试不可见）；后续可在 st-sync 加同类测试
- 前缀索引 `relative_path(765)` 对超 765 字符的路径不保证唯一性（实际同步路径不会如此长）