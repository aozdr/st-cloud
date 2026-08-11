# Agent 输出标准

所有 Agent 输出必须包含以下章节，供 Loop 编排器在 Evaluate 段消费：

## 背景
说明任务原因。

## 输入
列出使用的信息，含读取的 Loop State 关键字段（goal / 相关 artifacts / open blockers / 相关 exitCriteria）。

## 分析
说明判断过程。

## 决策
说明最终方案。

## State Delta
说明对 Loop State 的变更：
- 新增/更新的 artifacts（含 ref 路径）
- 新增/解除的 blockers
- 勾选的 exitCriteria（标注 id）

编排器据此在 Evaluate 段更新 State。

## 风险
说明潜在问题。

## 下一步
建议的下一个动作/Agent，供编排器 Plan 参考（非强制，编排器仍基于全量 State 重新规划）。

## 变更影响
说明本次变更对其他模块/Agent/exitCriteria 的影响。

> 避免只输出结论，必须提供可供编排器与下一 Agent 消费的结构化信息。
## 文档产出标准（与 Agent 输出格式的区别）

> 上述「背景/输入/分析/决策/State Delta/风险/下一步/变更影响」是 Agent 在对话中返回给编排器的**输出格式**。
> 当 Agent 产出落盘文档（PRD、UI 设计、架构评审、程序设计、测试用例、Code Review、需求发现）时，文档**内容结构**须遵循 `docs/newList/` 下对应的输出标准，并基于 `.ai/templates/` 对应模板填写。文档类型、归属、模板与输出标准的对应关系见 `.ai/knowledge/document-management.md`。

| 文档 | 输出标准 | 模板 |
|------|---------|------|
| 需求文档 | `docs/newList/ai-requirement-document-standard.md` | `.ai/templates/requirement-template.md` |
| UI 设计文档 | `docs/newList/ai-ui-design-document-standard.md` | `.ai/templates/ui-design-template.md` |
| 需求发现报告 | `docs/newList/ai-requirement-discovery-agent-standard.md` | `.ai/templates/discovery-template.md` |
| 架构设计评审 | `docs/newList/ai-architecture-review-standard.md` | `.ai/templates/architecture-review-template.md` |
| 程序设计文档 | `docs/newList/ai-design-document-standard.md` | `.ai/templates/design-template.md` |
| 测试用例 | `docs/newList/ai-test-case-standard.md` | `.ai/templates/test-case-template.md` |
| Code Review 记录 | `docs/newList/ai-code-review-standard.md` | `.ai/templates/code-review-template.md` |
