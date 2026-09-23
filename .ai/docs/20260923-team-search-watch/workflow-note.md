# 工作流门禁记录

- Task：`TASK-20260923-team-search-watch`
- State：`.ai/state/20260923-team-search-watch.yaml`
- 日期：2026-09-23

历史与当前审阅材料已汇总为 [审阅资料索引](approval-evidence.md)。2026-09-21 的 SQL43 迁移、测试和关注浏览器记录可作审计背景；本轮测试先对 `tsw-code-r6` 回归，并对 `tsw-code-r8` 做了定向验证。2026-09-22 的安全评审派发目标为 `tsw-code-r2`，没有返回结果，不能算当前独立评审。

当前修订为 `tsw-design-r4` / `tsw-code-r8`。12 项 exit criteria 均已按依赖顺序 Evaluate 为 done；`loopctl complete` 退出码 0，顶层 State 为 done。r7 独立代码评审指出的 CR-01 查询放大已在 r8 修复，r8 由主线程做代码、安全和体验自检，不能称为独立评审。

用户要求由当前模型接手 review，`DISPATCH-20260923-TSW-CODE-A2` 已中断并记为 failed；没有使用它的结果。主线程复核结论与证据写入 [代码评审](codereview.md)、[安全评审](security.md)、[浏览器记录](browser-verification.md)和[测试报告](testreport.md)。

用户随后明确要求剩余工作全部由当前模型执行。State 的 `singleAgentAuthorization` 只绑定本 TASK、主线程身份与 CODE_REVIEW、SECURITY_REVIEW、EXP_ACCEPT、TEST_PASS、ACCEPT 五项；原话和适用范围见 [审阅资料索引](approval-evidence.md)。工作流引擎默认仍拒绝同一实现者直接通过评审，只有当前任务的用户授权例外可用，并保留每项实际 `by` 与 `executionKind=direct`。状态机测试验证了默认拒绝、当前任务授权通过、跨任务授权拒绝，以及从旧 blocked 派发切到主线程自检时清除旧 dispatchId。

验证证据：后端 471 项测试 0 失败（排除会重建开发环境业务索引的 `ReindexIntegrationTest`）、MySQL schema 对比退出码 0、前端构建退出码 0、lint 0 error、浏览器 smoke 退出码 0。浏览器测试使用 API fixture，未验证真实登录与 MySQL、ES、MQ、S3 组成的部署级端到端链路；此限制保留在 [测试报告](testreport.md)，不作为生产发布结论。

全库级 `.ai/scripts/verify-loop.ps1` 仍返回 FAIL=6；六项均是 2026-09-16/19/21/22 历史 TASK 指向从未落盘的旧 change report。当前 State 在该脚本中单独显示 V2 严格校验通过，`loopctl complete` 也通过。本 TASK 的写入范围排除历史任务，故未为通过全库检查而补造旧报告或改写历史审计记录。详情见 [全库校验日志](verify-loop-r8.log)。
