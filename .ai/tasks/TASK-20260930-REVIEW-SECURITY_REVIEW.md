# TASK-20260930-REVIEW-SECURITY_REVIEW

目标：独立审查本次九项修复的SECURITY_REVIEW，只审本任务相对baseline的新改动；报告原有未改代码时须证明属于本任务修复必要范围。
State：.ai/state/20260930-review-fixes.json（只读最小快照）。
修订：review-fixes-code-3ae1050e4646f449。
输入：.ai/docs/20260930-review-fixes/requirement.md、design.md、testcases.md及原始review.md；baseline记录本次开始前源码哈希/副本。
include 写入白名单：.ai/docs/20260930-review-fixes/security.md、.ai/runtime/results/DISPATCH-20260930-REVIEW-SECURITY_REVIEW-f79eaf.json。其余源码/State/配置只读。
不得生成子Agent，不得修改业务源码，不得调用Git提交或共享构建。
结果包含背景、输入、分析、决策、State Delta proposal、风险、下一步、变更影响；真实执行者by使用Runtime提供的agent UUID，或报告自身canonical name由主线程与UUID映射。
输出JSON含dispatchId/taskId/status/artifactRefs/criterionProposal（id=SECURITY_REVIEW,outcome,by,dispatchId,evidenceRef,validatedRevision）/blockerProposals；ACCEPT还须逐项acceptanceEvidence（criterionId为goal原文、evidenceRef、validatedRevision）。
