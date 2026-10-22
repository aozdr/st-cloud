# Dispatch 账本命令化：设计

## 方案

### `dispatch-plan`

输入：`StatePath`、`-DispatchPath`（Envelope JSON）、`-CriterionId`、可选 `-ChildId`、`-Actor`、`-EventId`、`-DryRun`。

流程：

1. 读取 State 并 `Assert-State`；读取 Envelope 并复用 `Assert-Dispatch`。
2. 校验：
   - `dispatchId` 在 ledger 中唯一；
   - `criterionId` 属于当前 State 与 catalog；
   - criterion 不是 `done`；
   - `Envelope.role` 与 catalog owner 一致；
   - `Envelope.taskType` 与 catalog taskType 一致；
   - 同一 criterion 没有 `planned/spawned/acked/running/returned` attempt。
3. 写入 attempt：Envelope 身份字段 + `criterionId` + `childId`（缺省 `<pending>`）+ `status=planned` + `envelopeRef` + `plannedAt`。
4. 记录 `dispatch-plan` history，`Assert-State` 后保存。

不 spawn child；childId 可以是主线程分配的稳定逻辑标识（与现有 `/root/...` 记录一致），也可以是运行时返回的 id。

### `dispatch-transition` 扩展

- `spawned`：可传 `-ChildId` 更新；若仍为 `<pending>` 则拒绝。
- `acked` / `running`：记录时间戳。
- `returned`：必须传 `-ResultRef` 与 `-ScopeVerified`；验证结果文件存在后写入 `resultRef`、`scopeVerified=true`、`returnedAt`。
- `failed`：可记录 `-FailureReason` 与 `failedAt`。
- planned/spawned/acked/running/returned/evaluated 阶段传入不同 `-ChildId` 会被拒绝，防止身份漂移；`failed` 为终止态，仅记录 `-FailureReason`，不强制 childId 漂移校验。

## 兼容与风险

- Envelope 与 State schema 不变；旧 State 与其余命令兼容；`returned` 有意新增强制 `-ResultRef`/`-ScopeVerified` 参数。
- `dispatch-plan` 只新增命令，不改变现有 `evaluate`、`complete`、`reconcile`。
- 风险：调用方先 spawn 后 plan 的时序会造成“实际已启动、账本仍 planned”；以 `spawned` 迁移立即补齐，和现有运行时语义一致。
- 风险：逻辑 childId 与运行时 UUID 不同；以 `evaluate` 的 `by == childId` 校验保证结果归属一致。协议同步说明 childId 允许主线程分配。

## 测试计划

- `dispatch-plan` 成功创建 planned attempt，字段齐全、history 有记录。
- 重复 dispatchId、未知 criterion、role/taskType 不匹配、active attempt、criterion done 均拒绝。
- `spawned` 无 childId 拒绝；带 childId 成功。
- `returned` 缺 resultRef 或 scopeVerified 拒绝；齐全后 State validate 通过。
- `evaluate` 使用该 attempt 的 resultRef/childId 成功完成 criterion。
- loop-v2 全量测试与真实 reviewer child dry-run。

## 验收映射

见 `.ai/tasks/TASK-20261017-dispatch-ledger.md`；验证记录写入 `verification.md`，独立评审写入 `codereview.md`。
