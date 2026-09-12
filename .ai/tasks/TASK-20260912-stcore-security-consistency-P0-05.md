# TASK-20260912-stcore-security-consistency-P0-05

## 任务类型

P0-05 移动环与遍历防御。

## 前置条件

- P0-01～P0-03 已集成并通过验证。
- 已确认技术设计和测试用例文档存在。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/RecycleBinPhysicalDeleteIntegrationTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P0-05-01.json`

## 禁止写入

- `st-team`、数据库 SQL、前端、Loop State、共享报告。
- 不删除或自动修复存量环数据；只拒绝新操作并安全终止遍历。

## 实现要求

1. personal/team move 共用按节点 ID 的祖先/后代判定，不依赖可被污染的 path 前缀。
2. 目标等于自身或位于源文件夹后代时拒绝；源节点与目标 scope 不一致时也拒绝。
3. `collectDescendants`、folder tree、size、回收站级联等递归/遍历使用 visited 集合并设置最大深度 20、最大节点 500000；超过上限返回明确业务错误或不完整状态，不能无限循环/静默成功。
4. 为历史异常 parent cycle、重复引用和超深链路补测试。

## 验收标准

- 新移动不能形成 directory cycle。
- 历史环数据不会导致无限递归、栈溢出或无界 SQL 调用。
- 个人/团队移动使用一致的防护规则。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行移动、folder tree、folder size、回收站相关测试，并记录边界行为。
- `git diff --check`、`git diff --name-only`。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P0-05-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
