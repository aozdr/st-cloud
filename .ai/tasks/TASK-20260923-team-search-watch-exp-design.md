# TASK-20260923-team-search-watch-exp-design

## 目标

独立评审当前设计修订的 UI 规格是否被需求及技术设计完整覆盖，向主线程提出 `EXP_DESIGN` 结论。A1 对 `tsw-design-r3` 提出 fail 后，A2 复核 `tsw-design-r4` 的游标恢复及通知 target 乱序修复。不得修改产品代码或 State。

## 输入

- `.ai/docs/20260923-team-search-watch/{requirement.md,uispec.md,design.md,architecture-review.md}`
- `.ai/state/20260923-team-search-watch.yaml` 的最小快照
- 当前 UI 相关源代码可只读用于交叉核查，但本任务关注方案覆盖。

## 写入范围

- `.ai/docs/20260923-team-search-watch/exp-review.md`
- `.ai/runtime/results/DISPATCH-20260923-TSW-EXP-DESIGN-A1.json`
- `.ai/runtime/results/DISPATCH-20260923-TSW-EXP-DESIGN-A2.json`

## 验收

逐项核对入口、加载/空/错误/失权状态、键盘和 375px、并发请求、通知安全跳转。仅当当前设计确实覆盖时建议通过；发现缺口写明文件位置和后续动作。A2 结论绑定 `tsw-design-r4`，保留 A1 的失败记录，并提供真实独立结果和 criterionProposal。
