# 主线程直接执行的状态评估设计

## 问题与范围

当前 `loopctl evaluate` 只接受已经 `returned` 的子 Agent dispatch。用户要求主线程完成产品实现与文档，导致有真实产物的需求、影响、设计、测试用例、实现和知识库标准无法正式记账。此增强只为非独立评审标准增加 `evaluate-direct`；不改子 Agent dispatch 传输、独立评审、验收或角色分离规则。

## 输入与规则

- 主线程提交本任务的直接执行结果文件，字段为 `taskId` 与 `criterionProposal`（`id`、`outcome=pass`、`by`、`evidenceRef`、`validatedRevision`），并以 `-Actor` 声明执行者。文件和证据路径必须存在。
- 只允许 catalog owner 为 `executor` 或 `tester`、且不在 `separationOfDuty.verifiers` 中的标准。`EXP_DESIGN` 等 reviewer 标准仍走真实 dispatch。直接路径不支持 fail/skip、用户确认绕过或最终 ACCEPT。
- 检查任务身份、执行者、当前设计/代码修订、依赖、证据、catalog 产物和重复事件；通过后写 `executionKind=direct`、`executionId`、执行者、证据与时间。直接执行不创建 dispatchLedger attempt，也不填写虚假的 dispatchId。
- 原有派发结果继续走 `evaluate`；现有 State 可以继续读取。`done` 标准必须具有真实 dispatchId 或 direct executionId，不能两者皆无。角色分离同时适用于两种路径。

## 验证

在 State 引擎测试中覆盖直接通过、评审标准拒绝、旧修订拒绝、依赖缺失拒绝、证据缺失拒绝与无来源 `done` 拒绝，并运行 `verify-loop.ps1`。本轮 Task 的文档及代码证据随后通过此路径顺序 Evaluate；独立标准仍由真实 GPT-6 Luna max 子 Agent 审查。
