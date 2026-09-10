# Change Report：SV-001 schema_version 表 + 迁移执行

> 关联 Task: `.ai/tasks/SV-001.md`  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `docker/mysql/init/31_schema_version.sql` | 新增 | CREATE TABLE schema_version + 2 条基线 INSERT |
| `st-core/src/test/resources/schema.sql` | 修改 | 末尾追加 schema_version 表定义（H2 兼容） |

## 数据库迁移
- 31_schema_version.sql 已执行到 MySQL，schema_version 表存在
- 基线记录：20260811.1（历史 02~25）+ 20260812.1（26~31）

## 验收标准对照
- [x] MySQL schema_version 表存在且有 2 条基线记录
- [x] H2 schema.sql 含 schema_version 表，集成测试启动不报错
- [x] SchemaConsistencyTest 全绿

## 测试结果
- 全量回归 127 用例 0 失败