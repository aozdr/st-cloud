# TASK-20260923-team-search-watch-accept

在其他 11 项适用标准通过、知识库同步后，独立核对当前 State 的 Goal 五条 completionCriteria 与 `tsw-code-r7` 的真实证据。只读 TASK、State、需求、设计、评审、测试和知识库；不得修改代码、文档或 State。用户要求的最终 State `done` 只能由主线程根据独立建议和 `loopctl complete` 判定。

写入白名单：`.ai/runtime/results/DISPATCH-20260923-TSW-ACCEPT-A1.json`。结果应含 `criterionProposal` 和顶层 `acceptanceEvidence`，逐条使用 State 中完全相同的 criterion 文本、真实 evidenceRef 和 `validatedRevision=tsw-code-r7`。缺任何条件、独立性或真实证据时建议 fail，不得凑齐状态。
