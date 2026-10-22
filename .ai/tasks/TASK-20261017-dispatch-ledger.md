# TASK：Dispatch 账本命令化

- Task ID: TASK-20261017-dispatch-ledger
- State: `.ai/state/20261017-dispatch-ledger.yaml`
- 规模：large（修改 Loop 状态机与 Dispatch 契约；不涉及业务代码、数据库、产品 API 或 UI）
- 需求：`.ai/docs/20261017-dispatch-ledger/requirement.md`
- 设计：`.ai/docs/20261017-dispatch-ledger/design.md`

## 目标与验收

1. `dispatch-plan` 能从已校验的 Envelope 原子登记 `planned` attempt，写入 dispatchId/taskId/idempotencyKey/role/taskType/criterionId/childId，并拒绝重复 dispatchId、未知 criterion、角色或 taskType 不匹配、同 criterion 已有未结束 attempt。
2. `dispatch-transition` 能在 `spawned` 记录 childId，在 `returned` 强制要求并记录 `resultRef` 与 `scopeVerified`，补齐时间戳；原有合法前驱链不变。
3. 现有 State 与历史 dispatchLedger 不被改写；不新增 Envelope 字段，不改 state schema。
4. 单元测试覆盖正反路径，`.ai/tests/loop-v2/run-tests.ps1` 全量通过。
5. 用一个只读 reviewer child 通过真实 Envelope 跑完 `plan → spawned → acked → running → returned → evaluate`，并由主线程 Evaluate。

## include / exclude

- include：`.ai/scripts/loopctl.ps1`；`.ai/tests/loop-v2/**`；`.ai/knowledge/agent-dispatch-protocol.md`；`.ai/knowledge/agent-loop-runbook.md`；`.ai/templates/dispatch-template.md`；`.ai/docs/20261017-dispatch-ledger/**`；`.ai/tasks/TASK-20261017-dispatch-ledger.md`；`.ai/state/20261017-dispatch-ledger.yaml`；`.ai/runtime/dispatch/`、`.ai/runtime/results/` 中本任务测试产物。
- exclude：所有业务源码、数据库、部署配置、其他任务 State/文档/结果、Git index；不执行提交、发布或数据迁移。

## 验证

loop-v2 全量测试、定向 `dispatch-plan` 正反用例、真实 reviewer child 的 Envelope/结果文件、`loopctl validate` 与 `verify-loop.ps1` 定向检查、`git diff --check`。
