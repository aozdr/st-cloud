# Dispatch 账本命令化：实现记录

## 变更

- `.ai/scripts/loopctl.ps1`
  - 新增 `dispatch-plan`：读取并校验 Envelope，检查 dispatchId 唯一、criterion 属于 State/catalog、role/taskType 匹配、criterion 未完成、同 criterion 无未结束 attempt；写入 planned attempt（含 `envelopeRef/plannedAt`）和 history。
  - 扩展 `dispatch-transition`：`spawned` 可补齐/校验 childId，`acked/running/returned/evaluated/failed` 写入时间戳；`returned` 强制 `-ResultRef` 与 `-ScopeVerified` 并验证结果文件；非 spawned 阶段禁止更换 childId。
- `.ai/tests/loop-v2/unit/state-engine.tests.ps1`
  - 新增 TC-DSP-08 系列：创建 planned、重复 dispatchId、未知 criterion、role 不匹配、active attempt、spawned 缺 childId、returned 缺 resultRef/scopeVerified、完整 returned、evaluate 绑定。
  - TC-DSP-07 适配 returned 新参数。
- `agent-dispatch-protocol.md`、`dispatch-template.md`、`agent-loop-runbook.md`：补充 runtime-neutral 传输、ACK-only 规则、`dispatch-plan`/`dispatch-transition` 操作命令。

## 验证

```text
pwsh -NoProfile -File .ai/tests/loop-v2/unit/state-engine.tests.ps1
ALL PASSED

pwsh -NoProfile -File .ai/tests/loop-v2/run-tests.ps1
SUITES_PASSED=3
ALL PASSED
```

`verify-loop.ps1 -StatePath .ai/state/20261017-dispatch-ledger.yaml`：FAIL=0，PASS。

## 限制

- `dispatch-plan` 不 spawn child；主线程仍需通过当前运行时创建 child，并在 `spawned` 迁移时补齐 childId。
- 本次未改 Envelope/State schema，未迁移历史 State。
