> 已撤销：用户后续明确需求与程序设计由 GPT-6 编写。本 TASK 未作为文档验收依据，仅保留派发审计；当前文档已由主线程完成。

# TASK：团队搜索与关注功能影响分析

- Task ID: TASK-20260916-team-search-watch-impact
- State: .ai/state/20260916-team-search-watch.yaml
- 模型：GPT-5.6-Luna max；禁止切换 GPT-6。
- 输入：[planned-output] .ai/docs/20260916-team-search-watch/requirement.md 与 uispec.md。
- 目标：依据需求与真实源码，形成完整影响分析，为程序设计提供明确改动边界和风险。
- 写入范围：.ai/docs/20260916-team-search-watch/impact.md；.ai/runtime/results/DISPATCH-20260916-TSW-IMPACT-A1.json。
- 禁止：业务代码、数据库、State、TASK、其他文档；禁止派生或指挥 Agent。
- 验收：覆盖前后端、数据模型、权限、事件与通知、搜索索引、兼容性、测试、部署迁移及知识库；区分现有事实与设计建议，提供源码路径和行号，不能以知识库描述代替核实。
- 结果：中文正式结果，IMPACT_ANALYSIS proposal，by=/root/luna_impact，validatedRevision=tsw-design-r1，必须读取现行 Dispatch 协议。

