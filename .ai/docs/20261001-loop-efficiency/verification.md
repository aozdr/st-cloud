# Loop 效率优化验证记录

完成日期：2026-10-02。代码/规则修订：loop-v3-efdd2a53815a931f；设计修订：loop-efficiency-design-v3。

## 结果与影响

- small 默认对话内直接执行，无 TASK/State/Dispatch；medium 的持久化门禁由 8 项减为 4 项，默认主线程执行；large 由 12 项减为 6 项，保留独立 CODE_REVIEW，测试与评审可同时推进，ACCEPT 等待两者。
- medium 必需设计/评审/变更/测试文件合并为 design.md、verification.md；large 增加 requirement.md、codereview.md。UI、安全、数据库、API 契约和不可逆检查仍按风险触发，不能用 false 风险标记降级。
- 入口、状态定义/schema、状态工具、操作手册、文档规则、角色规则、模板和 conventions 已同步。内容不按门禁重复落盘；不强制八段结果或每阶段独立 Agent。
- State history 原本持有整个 State（包含 history），导致递归膨胀及 JSON 截断；已改为事件摘要，有 init/complete 文件大小反例保护。
- V2 定义冻结为 exit-criteria.v2.yaml；原 State 默认按绑定版本校验，单人授权原行为保留。V3 独立评审禁止单人授权绕过。旧迁移工具仍生成 V2。
- 本任务的 V2 初始化快照保留为 state-v2-initial.json，新建 V3 State 后重新 Evaluate，未继承任何旧通过项。
- 静态门禁默认检查当前规则和本次变更的 State；CI 从事件 base SHA 选择提交范围，支持 -BaseRevision/-StatePath 和显式 -AuditHistory。不靠跳过当前 State 取得通过。
- phase6 的资源对账测试改在临时 Git 仓库运行，另加未登记分支反例；实际资源检查仍在统一门禁执行，未放宽 Worktree 校验。

## tests：真实验证

| 检查 | 结果 | 证据 |
|---|---|---|
| pwsh -NoProfile -File .ai/tests/loop-v2/run-tests.ps1 | 退出 0，三套测试、106 条检查通过 | loop-tests.log |
| 新路径正反例 | 单人 medium、测试先于评审、风险降级、UI/安全/DB/API 证据、独立身份、旧版本、stale 和递归膨胀均覆盖 | loop-tests.log |
| verify-loop.ps1 -StatePath .ai/state/20261001-loop-efficiency.yaml | 退出 0 | static.log |
| git diff --check（本次修改范围） | 退出 0 | diff-check.log |
| 当前变更 State 的默认扫描 | 退出 1：历史 ui-ux-polish State 缺 dispatchId | repository-static.log |
| 实际仓库 worktree reconcile -Strict | 退出 1：旧 codex/frontend-review-fixes 未登记 | resource-baseline.log |

初次回归遇到沙箱 Git ownership 不一致及宿主旧分支；未改写全局 Git 配置。测试改用独立临时仓库，主仓库检查仅在当前进程临时提供 safe.directory。最终三套测试均正常通过。

## selfReview：主线程自检

核对 canonical DAG：medium 无独立评审门禁；large CODE_REVIEW 与 VERIFIED 都依赖 IMPLEMENTED，ACCEPT 汇合二者。核对运行工具：V3 风险、产物、分维度证据、修订、确认、职责分离、未结束 Dispatch 和 Goal 覆盖均仍校验。测试已验证大任务自评和单人授权无法绕过独立评审。本任务未启动 child 或创建 worktree，没有虚构独立审查。

## knowledge：稳定规则同步

相关流程说明和 conventions 已与实现同步；历史协议/业务知识条目不作为当前步骤。业务 API、源码、数据库、部署配置和其他任务 State 未改动。无需数据库迁移或业务模块构建，本次验证覆盖流程工具。

## Goal 核对

1. 风险分级与精简产物落地：AGENTS.md、exit-criteria.yaml、schema 和操作说明一致。
2. 测试与评审可并行且最终门禁完整：large 测试先完成及缺 review 拒绝 ACCEPT 的用例通过。
3. 旧 State 兼容且入口规则一致：V2 自动选择冻结定义、显式错版拒绝及旧套件通过。
4. 新旧流程验证通过：三套 106 条检查及本任务静态验证通过；仓库范围旧资源问题单独说明。

## 限制与剩余问题

实际仓库的统一完整门禁仍会被历史 State 缺执行来源和旧分支资源差异阻止；没有修补其他任务证据、删除分支或声称全仓门禁通过。这两项与本次流程工具变更无关，相关严格检查继续保留。

门禁/文档/派发开销减少是可数的结构变化；没有真实任务的耗时/Token 基准，未宣称效率提升百分比。代码变更仍保守失效全部代码证据，尚未引入按文件局部失效。

## 官方依据

按需加载和精简规则参考 [OpenAI 官方说明](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)；是否采用多 Agent 参考 [官方评估建议](https://developers.openai.com/api/docs/guides/evaluation-best-practices)；复杂任务计划参考 [PLANS.md Cookbook](https://developers.openai.com/cookbook/articles/codex_exec_plans)。门禁数量、文件合并及风险字段为本仓库工程决策。
