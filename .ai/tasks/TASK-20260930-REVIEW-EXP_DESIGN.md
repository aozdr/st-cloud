# TASK-20260930-REVIEW-EXP_DESIGN

目标：独立审查定版uispec与design中的F8降级体验，输出exp-review.md。
State：.ai/state/20260930-review-fixes.json（只读最小快照）。
修订：review-fixes-design-v1。
输入：.ai/docs/20260930-review-fixes/requirement.md、design.md、testcases.md及原始review.md；baseline记录本次开始前源码哈希/副本。
include 写入白名单：.ai/docs/20260930-review-fixes/exp-review.md、.ai/runtime/results/DISPATCH-20260930-REVIEW-EXP_DESIGN-4caa9c.json。其余源码/State/配置只读。
不得生成子Agent，不得修改业务源码，不得调用Git提交或共享构建。
结果包含背景、输入、分析、决策、State Delta proposal、风险、下一步、变更影响；真实执行者by使用Runtime提供的agent UUID，或报告自身canonical name由主线程与UUID映射。
输出JSON含dispatchId/taskId/status/artifactRefs/criterionProposal（id=EXP_DESIGN,outcome,by,dispatchId,evidenceRef,validatedRevision）/blockerProposals；ACCEPT还须逐项acceptanceEvidence（criterionId为goal原文、evidenceRef、validatedRevision）。
