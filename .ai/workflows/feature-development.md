# Feature Development

按风险采用最小充分流程，目标是完成用户需求并提供真实验证。

1. 明确目标、修改和禁止范围、完成标准；按 AGENTS.md 分级。
2. small 直接实现并验证。medium/large 创建 TASK 和精简设计，只有未决实质决策才确认。
3. 实现后立即运行相称验证；large 独立评审可同时推进。
4. 修复实际失败，更新 revision 并复核失效证据。
5. 主线程对照 Goal 验收后交付。

流程定义只在 `.ai/loop/exit-criteria.yaml`；不按标准项数创建 child、不输出每轮四段表。操作见 `.ai/knowledge/agent-loop-runbook.md`，格式见 `.ai/knowledge/document-management.md`。只有实际委派才加载 Dispatch 协议。

设计内写影响分析、架构选择、UI 和测试计划；verification.md 写变更、自检、测试、体验及知识同步。只有独立决策/用户要求才拆额外文档。没有稳定规则变化不新建知识任务。

先完成授权范围内的工作，验证通过后不要为阶段切换重复测试。代码变化仍保守失效所有代码证据，避免复用旧结论。
