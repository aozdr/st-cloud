# 测试用例：数据库 Schema 版本管理（20260812）

> 产出者：tester。关联 State：`.ai/state/20260812-schema-versioning.yaml`

## SV-001 schema_version 表
- MySQL `schema_version` 表存在，含 version_tag/iteration_name/applied_sql_files/applied_at/applied_by/notes 列
- 基线记录 2 条：20260811.1 + 20260812.1
- H2 schema.sql 含 schema_version 表，集成测试启动不报错
- SchemaConsistencyTest 3 用例全绿（含 schema_version 表覆盖）

## SV-002 compare-schema.ps1
- 无差异场景：输出 PASS，退出码 0
- 有差异场景：输出 ONLY_IN_H2 / ONLY_IN_MYSQL / PENDING_SQL，退出码 1
- 能识别 schema_version 表中未记录的新 SQL 文件

## SV-003 AGENTS.md 规则
- AGENTS.md 含「数据库版本管理」章节
- verify-loop PASS

## SV-004 知识库
- testing.md「数据库迁移验证」章节引用 compare-schema.ps1
- data-model.md 含 schema_version 表结构

## 回归
- 全量 5 模块测试 127+ 用例全绿
- verify-loop PASS