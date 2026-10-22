# Dispatch 账本命令化：验证

- validatedRevision: `dispatch-ledger-code-r1`
- 任务：`TASK-20261017-dispatch-ledger`
- State：`.ai/state/20261017-dispatch-ledger.yaml`

## 修改范围

- `.ai/scripts/loopctl.ps1`：新增 `dispatch-plan`；扩展 `dispatch-transition` 的 childId/resultRef/scopeVerified/时间戳处理。
- `.ai/tests/loop-v2/unit/state-engine.tests.ps1`：新增 TC-DSP-08 系列（含 taskType/criterion-done 负向用例）并适配 TC-DSP-07。
- `.ai/knowledge/agent-dispatch-protocol.md`、`.ai/templates/dispatch-template.md`、`.ai/knowledge/agent-loop-runbook.md`：同步 runtime-neutral 传输、ACK-only、账本命令。
- 无业务源码、数据库、产品 API 或 UI 变更。

## 测试与证据

| 检查 | 命令 | 结果 |
|---|---|---|
| 状态机单元测试 | `pwsh -NoProfile -File .ai/tests/loop-v2/unit/state-engine.tests.ps1` | 57 PASS / 0 FAIL，ALL PASSED |
| loop-v2 全量 | `pwsh -NoProfile -File .ai/tests/loop-v2/run-tests.ps1` | SUITES_PASSED=3，ALL PASSED |
| 当前 State 校验 | `.ai/scripts/verify-loop.ps1 -StatePath .ai/state/20261017-dispatch-ledger.yaml` | FAIL=0，State PASS |
| Windows PowerShell 5.1 | reviewer 复检 `validate`、`verify-loop.ps1`、临时夹具 `dispatch-plan` | PASS / FAIL=0 / PLANNED |
| 真实 reviewer dispatch | `dispatch-plan → spawned → acked → running → returned → evaluate` | CODE_REVIEW EVALUATED，PASS |

## 自检

- `dispatch-plan` 复用 `Assert-Dispatch`，不新增 Envelope 字段；`dispatch-transition` 保持合法前驱映射，只在 `spawned` 更新 childId，在 `returned` 强制 resultRef/scopeVerified。
- 旧 State 与其余命令兼容；`returned` 有意新增强制 `-ResultRef`/`-ScopeVerified`；已有 attempt 的 resultRef/scopeVerified 不被重写；未改 `loop-state.schema.json`。
- 真实 reviewer 使用 DSH 原生 `subagent`（DeepSeek 模型），只写 `codereview.md` 与独立结果文件，不写 State。
- `loopctl.ps1` 的 UTF-8 BOM 在审查中被发现缺失并已恢复；reviewer 用 Windows PowerShell 5.1 复检通过。

## 独立评审

- 评审记录：`.ai/docs/20261017-dispatch-ledger/codereview.md`
- 结果文件：`.ai/runtime/results/DISPATCH-20261017-DISPATCH-LEDGER-CR-01.json`
- 结论：**PASS**，无阻断项；3 Minor + 4 Suggestions。
- BOM 缺失问题在审查期间发现并关闭；产品代码 `loopctl.ps1` 在评审后未再变更。
- 评审后补齐（均为测试/文档，不改变产品代码）：
  - Minor-1：新增 TC-DSP-08K（taskType mismatch）与 TC-DSP-08L（criterion done），状态机测试 57 PASS。
  - Minor-2：design 明确 `failed` 为终止态，仅记录 failureReason，不强制 childId 漂移校验。
  - Minor-3：protocol 明确 childId 可为运行时返回或主线程分配的稳定逻辑标识，同一 attempt 内固定。
  - Suggestion：修正“旧调用兼容”措辞；测试脚本恢复 UTF-8 BOM；runbook 补入 TASK include；模板补 `description`、可选 `-ChildId` 与 ACK_ONLY `failed` 示例。

## 知识同步

更新了当前有效的 Dispatch 操作说明、模板与 runbook；未发现需要新增业务知识条目的稳定规则。历史 `.ai/docs/20260911-agent-loop-v2/**` 保持原样，不重写历史。

## 残余风险

- `dispatch-plan` 不校验 `Envelope.stateRef` 是否指向目标 State；调用方必须传入匹配的 Envelope 与 criterion。后续可做 stateRef 归一化校验。
- `resultRef` 原样保存，`evaluate` 要求与 proposal.evidenceRef 字符串完全一致；传绝对路径或不同写法会 `DISPATCH_RESULT_MISMATCH`。
- `dispatch-transition -DispatchStatus evaluated` 是既有旁路，可能在不更新 criterion 的情况下把 attempt 标为 evaluated；主线程应使用 `evaluate` 收敛。
- `loopctl` 写 State 无跨进程锁，沿用单 Workflow Manager 串行写入假设。
- 21 个历史非 JSON-compatible State 未迁移，属既有边界。
- `dispatch-plan` 不 spawn child；主线程仍需创建 child 并在 `spawned` 补齐 childId。
- childId 使用主线程分配的稳定逻辑标识（与现有 `/root/...` 记录一致），不是运行时 UUID；`evaluate` 以 `by == childId` 保证归属一致。
