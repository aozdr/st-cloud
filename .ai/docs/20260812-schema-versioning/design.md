# 设计：数据库 Schema 版本管理（20260812）

> 产出者：architect。关联 State：`.ai/state/20260812-schema-versioning.yaml`

## 总体方案
建立三层版本管理机制：
1. **版本表**：`schema_version` 记录每次迭代的版本号、SQL 文件清单、执行时间/人
2. **对比脚本**：`compare-schema.ps1` 自动对比 H2 schema.sql 与 MySQL 实际列集，输出差异与待执行 SQL
3. **规则约束**：AGENTS.md 强制每次迭代走版本管理流程

## SV-001 schema_version 表设计
```sql
CREATE TABLE schema_version (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_tag       VARCHAR(32)  NOT NULL UNIQUE,  -- 格式 YYYYMMDD.N
    iteration_name    VARCHAR(255) NOT NULL,
    applied_sql_files TEXT,                           -- 逗号分隔的 SQL 文件清单
    applied_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    applied_by        VARCHAR(64),
    notes             TEXT
);
```
- 基线记录 2 条：20260811.1（历史 02~25）+ 20260812.1（26~31）
- H2 schema.sql 同步补表（不含 ENGINE/CHARSET）
- 执行到 MySQL 后验证

## SV-002 compare-schema.ps1 设计
- 解析 `st-core/src/test/resources/schema.sql` 得到 H2 列集（复用 SchemaConsistencyTest 的正则）
- 查询 MySQL `INFORMATION_SCHEMA.COLUMNS` 得到实际列集
- 对比输出：
  - `ONLY_IN_H2`：H2 有 MySQL 无的列（需补迁移）
  - `ONLY_IN_MYSQL`：MySQL 有 H2 无的列（需补 schema.sql）
  - `PENDING_SQL`：`schema_version.applied_sql_files` 未记录的新 SQL 文件
- 退出码：0=PASS 无差异，1=有差异

## SV-003 AGENTS.md 规则设计
新增「数据库版本管理」章节，置于「代码修改强制约束」之后：
- 每次迭代涉及 DB 变更须更新 `schema_version` 版本号
- H2 测试通过后必须运行 `compare-schema.ps1` 对比正式 MySQL
- 将本次执行的 SQL 文件清单写入 `schema_version.applied_sql_files`
- 记录格式：版本号 `YYYYMMDD.N`，SQL 文件逗号分隔

## SV-004 知识库更新
- `testing.md`：将「数据库迁移验证」从人工检查升级为自动化（compare-schema.ps1 + schema_version 表）
- `data-model.md`：新增 schema_version 表结构说明

## 风险
- compare-schema.ps1 依赖 MySQL 连接（需 -h127.0.0.1），沙箱环境可能需 escalated
- schema_version 表 INSERT 需幂等（使用 INSERT IGNORE 或唯一键兜底）