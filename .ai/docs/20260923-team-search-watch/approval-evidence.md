# 审阅资料索引

- 当前任务：`TASK-20260923-team-search-watch`
- 当前修订：`tsw-design-r4` / `tsw-code-r8`
- 用途：将历史资料与本轮证据放在同一入口，供需求、技术和验收审阅。历史 State 与产物只作为审计背景；本轮结论以当前修订的复核与验证为准。

| 审阅事项 | 可用资料 | 能证明什么、边界是什么 |
|---|---|---|
| 需求范围与验收口径 | [2026-09-21 需求](../20260921-team-search-watch/requirement.md)、[2026-09-22 需求](../20260922-team-search-watch/requirement.md)、[本轮需求](requirement.md)、[UI 规格](uispec.md) | 前两轮记录了需求形成过程；本轮文档给出当前范围、权限、异常及验收口径。历史文档不替代本轮定版文档。 |
| 技术方案与影响 | [2026-09-22 设计](../20260922-team-search-watch/design.md)、[本轮设计](design.md)、[架构审查](architecture-review.md)、[影响分析](impact.md) | 可对照数据、API、可靠性和兼容策略；以本轮设计为实施依据。 |
| 数据库迁移 | [2026-09-21 测试及迁移记录](../20260921-team-search-watch/testreport.md)、[本轮 schema 对比日志](../20260923-team-search-watch-schema-check-r2.log) | 历史记录载明开发 MySQL 应用 SQL43、登记版本 `20260921.1`、迁移后对比退出码 0；本轮再次对比退出码 0。没有生产迁移证据。 |
| 既有浏览器过程 | [2026-09-21 测试报告](../20260921-team-search-watch/testreport.md)、[本轮浏览器记录](browser-verification.md) | 旧报告记录关注、取消关注及 375px 交互，同时明确团队搜索和通知仍未完整验收。本轮记录补充移动筛选和通知弹层；两者均不能证明完整部署级端到端链路。 |
| 当前代码与回归 | [变更报告](changereport.md)、[测试用例](testcases.md)、[本轮测试报告](testreport.md)、[代码评审](codereview.md)、[安全评审](security.md) | r6 已有 core 14、team 40、search 64 项通过及 schema 对比；r7 前端 build/lint 通过；r8 定向 team 13、search 19 项通过。旧评审随代码修订过期，当前独立门禁须重新核对。 |
| 知识库同步 | [文档证据清单](documentation-evidence.json) | 可定位当前知识库更新及交付文档。 |

## 不能从历史记录推定的结论

2026-09-21 的测试报告明确写明当时整体尚未通过；2026-09-22 的[续做状态](../20260922-team-search-watch/testreport.md)仍列出未完成缺陷、浏览器闭环和独立评审。旧安全评审 dispatch `DISPATCH-20260922-TSW-SEC-A1` 针对 `tsw-code-r2`，其声明的结果文件不存在，旧 State 也未将它评估为完成。当前代码为 `tsw-code-r8`，不能把该 dispatch 当作当前独立安全评审。

因此这些材料足以辅助人工审阅需求、设计、迁移和测试历程；它们本身不构成当前修订的独立代码、安全、体验评审或正式 `ACCEPT`。r8 主线程自检已补入对应评审文档，不能代替角色分离的正式门禁。当前 State 的门禁情况见 [工作流记录](workflow-note.md)。

## 当前任务的单人执行授权（2026-09-23）

用户在当前任务中明确说：“review你来review，luna的智商不够会让你继续干活”，随后又说：“剩余任务都由你来执行，能到达规定标准就行”。据此仅对 `20260923-team-search-watch` 允许当前主线程执行剩余代码、安全、体验、测试和最终验收。State 以 `singleAgentAuthorization` 逐项记录该授权；结论必须标为主线程自检，不能声称独立评审，也不能扩展到其他任务。所有技术证据、依赖和当前 revision 检查仍须满足原门禁。

当前修订的主线程验收依据为 [r8 代码自检](codereview.md)、[r8 安全自检](security.md)、[r8 体验验收](exp-review.md)、[测试报告](testreport.md)及[工作流记录](workflow-note.md)。正式完成状态以 `.ai/state/20260923-team-search-watch.yaml` 的 `complete` 校验结果为准，不从单份历史材料推定。
