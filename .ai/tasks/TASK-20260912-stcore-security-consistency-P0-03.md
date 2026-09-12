# TASK-20260912-stcore-security-consistency-P0-03

## 任务类型

P0-03 scope-safe path 更新与元数据事件。

## 前置条件

- P0-01、P0-02 已集成并通过验证。
- 已确认技术设计和测试用例文档存在。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/mapper/FileNodeMapper.java`
- `st-core/src/main/resources/mapper/FileNodeMapper.xml`
- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/RecycleBinPhysicalDeleteIntegrationTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P0-03-01.json`

## 禁止写入

- `st-team`、前端、数据库 SQL、Loop State、共享报告。
- 不以大范围 Service 拆分替代修复，不调整无关查询。

## 实现要求

1. `updateChildrenPath` 必须增加 owner/space scope 条件，或改为按已校验的 descendant ID 批量更新；禁止仅凭 raw path 前缀更新。
2. rename/move/restore 传递源节点 scope，确保后代更新与源节点相同 owner/space；目标路径变更不能触及其他 scope 的同前缀节点。
3. `publishMetaUpdate` 不得用无 scope 的 path 前缀广播；优先使用已收集的 descendant ID，事件仅面向实际受影响节点。
4. 补造共享 path 前缀但不同 owner/space 的数据，验证 rename/move/restore 不会误改路径或事件。

## 验收标准

- rename/move/restore 不修改其他 owner/space 的 path。
- 元数据事件集合只包含实际 subtree 节点。
- 正常路径更新与回收站恢复保持原有业务语义。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行路径、回收站和事件测试；检查数据库更新行数与事件节点 ID。
- `git diff --check`、`git diff --name-only`。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P0-03-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
