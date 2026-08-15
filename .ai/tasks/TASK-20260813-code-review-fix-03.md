# TASK：TASK-03 注释乱码修复 + 迁移脚本幂等

> 开发前置产物。编码输入只接受本文件。
> 关联 State: `.ai/state/20260813-code-review-fix.yaml`

## 元信息
- Task ID: `TASK-20260813-code-review-fix-03`
- 归属 Agent: backend-engineer

## 目标
修复 Code Review Standards 硬性违规：核心注释/日志乱码（字面 `?`）；28/30 迁移脚本不可重复执行。

## 修改范围
- `st-desktop/src/database.ts`：修复 sync_block_hash 相关注释乱码
- `st-desktop/src/sync-engine.ts`：修复块级同步注释与日志文案乱码（含 syncLog / Error 文案，禁止改动业务逻辑）
- `st-core/src/main/java/com/stcloud/core/service/StorageService.java`：修复 uploadPartCopy Javadoc 乱码
- `st-core/src/test/resources/schema.sql`：修复 file_block 注释乱码
- `docker/mysql/init/28_file_object.sql`：回填 INSERT 改为 INSERT IGNORE（幂等）
- `docker/mysql/init/30_sync_change_log_event_log_id.sql`：ADD COLUMN / ADD UNIQUE KEY 加 information_schema 存在性守卫（PREPARE/EXECUTE，幂等）

## 禁止修改范围
- 不改任何业务逻辑、表结构、字段语义
- 不改 28/30 的目标 schema（仅幂等化；已在 schema_version 记录执行）

## 验收标准
- [ ] `rg "\?{3,}"` 在上述目录无命中
- [ ] 28/30 脚本逻辑上可重复执行不报错
- [ ] H2 SchemaConsistencyTest 不回归

## 测试要求
- `mvn test -pl st-core -am`（含 SchemaConsistencyTest）通过
