# Agent 结果格式

正式 child 用中文返回可评估的简洁结果：

- 结果与变更影响：实际完成项及对应 TASK 验收项。
- 证据：真实产物、命令结果、validatedRevision；自检/独立评审身份如实标记。
- 风险/阻塞：只列实际剩余问题和必要下一步。
- criterionProposal：供主线程 Evaluate；不得声称已修改 State。

背景、输入、分析、决策只在解释结论需要时补充，不强制八段重复文字。事实与推测分开；结果数据契约见 `.ai/knowledge/agent-dispatch-protocol.md`。

设计/测试/变更等文档按 `.ai/knowledge/document-management.md` 合并，模板可裁剪；用户指定格式优先。
