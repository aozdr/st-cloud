> 已撤销：用户后续明确需求与程序设计由 GPT-6 编写。本 TASK 未作为文档验收依据，仅保留派发审计；当前文档已由主线程完成。

# TASK：团队搜索与关注功能交互方案评审

- Task ID: TASK-20260916-team-search-watch-experience
- State: .ai/state/20260916-team-search-watch.yaml
- 模型：GPT-5.6-Luna max；禁止切换 GPT-6。
- 输入：[planned-output] .ai/docs/20260916-team-search-watch/requirement.md 与 uispec.md。
- 目标：独立评审需求对应的交互方案是否完整、贴合现有 UI、可落实到后续技术设计。
- 写入范围：.ai/docs/20260916-team-search-watch/exp-review.md；.ai/runtime/results/DISPATCH-20260916-TSW-EXP-A1.json。
- 禁止：业务代码、数据库、State、TASK、其他文档；禁止派生或指挥 Agent。
- 验收：逐项核对页面入口、搜索范围和结果、关注/取消关注与通知流程、无权/空/加载/错误状态、移动端与可访问性；给出真实差距与明确修订建议。不替用户扩展产品范围，不将假设的测试说成已执行。
- 结果：中文正式结果，EXP_DESIGN proposal，by=/root/luna_experience，validatedRevision=tsw-design-r1，必须读取现行 Dispatch 协议。若发现阻塞缺口，返回 fail 及需要修改的文档位置，不自行修改他人文档。

