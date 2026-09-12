# TASK-20260912-stcore-security-consistency-P1-01-03

## 任务类型

P1-01～P1-03 并发一致性与数据库最终约束。

## 前置条件

- P0-01～P0-05、P0-04 已集成并通过验证。
- `testcases.md` 已完成，且设计中的存量冲突“不自动删除”已确认。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/VersionServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/mapper/FileNodeMapper.java`
- `st-core/src/main/java/com/stcloud/core/mapper/FileVersionMapper.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadCommitManager.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/UploadManager.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/*Guard*.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/*Allocator*.java`
- `docker/mysql/init/41_file_node_version_uniqueness.sql`
- `st-core/src/test/resources/schema.sql`
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/editor/EditorVersionIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/ConcurrentUploadIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/schema/SchemaConsistencyTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P1-01-03-01.json`

## 禁止写入

- `docker/mysql/init/02_create_tables.sql` 和已有迁移。
- `st-team`、前端、Loop State、共享 changereport。
- 不删除/重命名存量同名记录，不用脚本静默修复冲突。

## 实现要求

1. 所有关键 `updateById` 必须检查影响行数/版本冲突；冲突抛明确异常，且副作用（配额、引用、事件、外部调用）在冲突前不发生或可回滚。
2. 同一 tenant/scope/parent 下有效节点的最终唯一性由数据库保障；scope 为 personal(owner) 或 team(spaceId)，有效节点按 status/deleted 生成 active_name。迁移前只读预检并记录冲突策略。
3. 版本号分配采用锁定对应 file_node 行或等价原子方案；`file_version(file_node_id, version_num)` 增加唯一约束，并对 DuplicateKeyException 可安全重试。
4. 迁移 SQL 首行必须是 `SET NAMES utf8mb4;`，编号递增；同步 H2 schema；不得把未执行的 MySQL 迁移写成完成。
5. 关键路径补充并发测试：同名创建/复制、同节点版本快照、覆盖上传与版本并发、乐观锁冲突。

## 验收标准

- 乐观锁冲突不会产生部分成功。
- 同级有效节点和同一文件版本号均有数据库唯一性最终兜底。
- H2 schema 与 MySQL 迁移结构一致，存量冲突不被自动删除。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行并发、版本和 schema consistency 测试。
- `.ai/scripts/compare-schema.ps1` 按环境执行两次并记录输出；MySQL 不可用时保留阻塞证据。
- `git diff --check`、`git diff --name-only`。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P1-01-03-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
