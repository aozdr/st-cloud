# 独立代码审查：Dispatch 账本命令化

- dispatchId：`DISPATCH-20261017-DISPATCH-LEDGER-CR-01`
- taskId：`TASK-20261017-DISPATCH-LEDGER-CR`
- Reviewer：`reviewer-dispatch-ledger-01`
- 审查对象：当前工作区 `.ai/scripts/loopctl.ps1`、`.ai/tests/loop-v2/unit/state-engine.tests.ps1`、Dispatch 协议/模板/runbook 与任务文档
- validatedRevision：`dispatch-ledger-code-r1`
- 结论：**PASS**（无阻断项）

## 1. 审查范围与方法

读取了 TASK、requirement/design/implementation/verification、当前 State、真实 Envelope、loopctl 变更 diff、测试 diff、协议/模板/runbook diff 与 schema。

审查方式：

1. 逐项核对 `dispatch-plan` 的字段、校验顺序、错误码、写入内容与原子性；
2. 逐项核对 `dispatch-transition` 的合法前驱链、childId 更新边界、resultRef/scopeVerified、时间戳与失败分支；
3. 用仓库外临时 State/Envelope 独立执行正反路径探针，覆盖测试未写到的 taskType mismatch、criterion done、childId drift、failed 分支；
4. 运行单元测试、loop-v2 全量测试、`verify-loop.ps1`、Windows PowerShell 5.1 兼容性检查；
5. 扫描现有 State 的 dispatchLedger，确认没有旧 attempt 因 `returned/evaluated` 新校验而失效。

未修改源码、测试、State 或 Envelope；临时夹具全部位于 `%TEMP%` 并在探针结束后删除。

## 2. 结论摘要

- 需求要求的 `planned → spawned → acked → running → returned → evaluated` 主链可用；`spawned` 强制 childId，`returned` 强制 `-ResultRef` + `-ScopeVerified` 且校验文件存在；
- `dispatch-plan` 的重复 dispatchId、未知 criterion、criterion done、role/taskType mismatch、active attempt、`<pending>` childId 均按设计拒绝；
- 未改 Envelope/State schema，未迁移历史 State；可解析的旧 State 中未发现不满足新 `resultRef/scopeVerified` 要求的 attempt；
- 单元测试 55/55 PASS，loop-v2 全量 3/3 suite PASS，`verify-loop.ps1` FAIL=0；
- 审查期间发现 `loopctl.ps1` 的 BOM 被移除导致 Windows PowerShell 5.1 解析失败；主线程已恢复 BOM，当前文件按 5.1 复检通过，该问题关闭（见第 6 节）；
- 剩余问题为测试证据完整性、协议措辞与 scope 记录，均不构成功能阻断。

## 3. 发现（按严重度）

### Minor-1 设计测试计划中的两个负向用例未落地

- 位置：`.ai/tests/loop-v2/unit/state-engine.tests.ps1:313-368`（TC-DSP-08 系列）
- 对照：`.ai/docs/20261017-dispatch-ledger/design.md:42` 要求“role/taskType 不匹配、criterion done 均拒绝”；`.ai/docs/20261017-dispatch-ledger/requirement.md:28` 明确要求拒绝 taskType 不匹配与 criterion done。
- 事实：新增 10 个 TC-DSP-08 检查覆盖了重复 dispatchId、未知 criterion、role mismatch、active attempt、childId `<pending>`、returned 缺参数与 evaluate 绑定；但没有 taskType mismatch 与 criterion done 用例。全文件搜索无 `DISPATCH_TASKTYPE_MISMATCH` / `CRITERION_ALREADY_DONE` 断言。
- 独立探针结果（临时 State）：
  - `taskType=implement` 指向 `VERIFIED` → `[DISPATCH_TASKTYPE_MISMATCH] taskType 应为 test，实际 implement`；
  - 指向已 done 的 `IMPLEMENTED` → `[CRITERION_ALREADY_DONE] IMPLEMENTED 已完成，不得重复派发`。
- 影响：行为正确，但交付测试证据没有覆盖这两个需求校验点，后续回归无法自动发现回退。**不阻断本轮结论**，建议补 TC-DSP-08K/L 后再进入最终 ACCEPT。

### Minor-2 `failed` 阶段未执行 childId 漂移校验，与设计措辞不一致

- 位置：`.ai/scripts/loopctl.ps1:829-831`
- 对照：`.ai/docs/20261017-dispatch-ledger/design.md:30`“非 spawned 阶段传入不同 `-ChildId` 会被拒绝”。
- 事实：条件为 `$DispatchStatus -notin @('spawned','failed')`，即 `failed` 被显式豁免；`failed` 分支也不更新 childId，因此传入不同 childId 被静默忽略。独立探针：对 `returned` attempt 执行 `dispatch-transition ... -DispatchStatus failed -ChildId other-child -FailureReason probe-reason`，退出 0，childId 仍为 `probe-child`，failureReason/failedAt 正常写入。
- 影响：attempt 已终止时静默忽略 childId 实际风险很低，但与设计承诺不符，且会掩盖调用方错误。建议把 `failed` 纳入漂移拒绝，或明确在设计中豁免并说明原因。

### Minor-3 协议仍把 childId 限定为“运行时返回”，与设计/真实派发不一致

- 位置：`.ai/knowledge/agent-dispatch-protocol.md:33`
- 对照：`.ai/docs/20261017-dispatch-ledger/design.md:37`“协议同步说明 childId 允许主线程分配”；真实 State 的 attempt 使用主线程逻辑标识 `reviewer-dispatch-ledger-01`，并非运行时 session id。
- 影响：文档与 design/实现记录不一致。建议改为“childId 为当前运行时返回的实例标识，或主线程分配的稳定逻辑标识；同一 attempt 内固定不变”。

### Suggestion-1 “旧调用方式继续有效”的措辞不准确

- 位置：`.ai/docs/20261017-dispatch-ledger/design.md:34`、`.ai/docs/20261017-dispatch-ledger/verification.md:26`
- 事实：`dispatch-transition` 的 `returned` 现在要求 `-ResultRef` 与 `-ScopeVerified`，TC-DSP-07 也不得不为旧调用补参数（`.ai/tests/loop-v2/unit/state-engine.tests.ps1:309`）。
- 影响：旧 State 校验仍兼容，但旧的 `returned` 调用形状是有意破坏的。建议将措辞改为“旧 State 与其余调用兼容；`returned` 新增强制证据参数”。

### Suggestion-2 测试脚本丢失 UTF-8 BOM，与仓库编码规范不一致

- 位置：`.ai/tests/loop-v2/unit/state-engine.tests.ps1:1`
- 事实：当前首字节为 `35,114,101`（无 BOM）；仓库规范要求含非 ASCII 的 `.ps1` 使用 UTF-8 BOM（`.ai/knowledge/conventions.md:233`、`.ai/knowledge/document-management.md:13`）。
- 影响：该测试 `#requires -Version 7.0`，不会在 Windows PowerShell 5.1 下运行，因此不影响当前测试结果；但属于本轮 diff 的非必要编码变化，建议恢复 BOM 以保持规范与最小 diff。

### Suggestion-3 `agent-loop-runbook.md` 被修改但不在 TASK include 内

- 位置：`.ai/tasks/TASK-20261017-dispatch-ledger.md:19`、`.ai/docs/20261017-dispatch-ledger/implementation.md:11`
- 事实：实现记录明确同步了 `.ai/knowledge/agent-loop-runbook.md:46`，但 TASK 的 include 列表只列了 protocol 与 template。
- 影响：内容本身正确且与命令一致，属流程范围记录缺口。建议把 runbook 补入 include，或在实现记录中标注为知识同步的显式例外。

### Suggestion-4 Dispatch 操作文档的少量可用性问题

- `.ai/templates/dispatch-template.md:56-59` 的 DSH `subagent(...)` 示例缺少当前工具要求必填的 `description` 参数，照抄会调用失败；
- `.ai/templates/dispatch-template.md:71`、`.ai/knowledge/agent-dispatch-protocol.md:89` 把 `-ChildId` 写成固定参数，而 `dispatch-plan` 的设计是可选（缺省 `<pending>`）；
- 协议/模板只给了 returned 命令，没有给出“只返回 ACK 无事可记 → failed（ACK_ONLY）”时带 `-FailureReason` 的 `dispatch-transition ... -DispatchStatus failed` 示例，与 `dispatch-template.md:85` 的失败处理描述不闭环。

## 4. 审查明细

### 4.1 `dispatch-plan`

| 检查项 | 代码位置 | 结论与证据 |
| --- | --- | --- |
| 必填参数与 Envelope 校验 | `loopctl.ps1:604-609` | `DispatchPath`/`CriterionId` 缺失报 `DISPATCH_ARGUMENT_MISSING`；复用 `Assert-Dispatch`（schema + 路径校验），未新增 Envelope 字段。 |
| 重复 dispatchId | `loopctl.ps1:610-614` | 探针 `[DISPATCH_ALREADY_PLANNED]`；TC-DSP-08B 覆盖。 |
| criterion 属于当前 State/catalog | `loopctl.ps1:615-617` | 先查 State map 再查 catalog，均报 `CRITERION_UNKNOWN`；TC-DSP-08C 覆盖。 |
| criterion 已 done | `loopctl.ps1:618-619` | 报 `CRITERION_ALREADY_DONE`；探针覆盖，测试未覆盖（Minor-1）。 |
| role 匹配 catalog owner | `loopctl.ps1:620-621` | 报 `DISPATCH_ROLE_MISMATCH`；TC-DSP-08D 覆盖。 |
| taskType 匹配 catalog taskType | `loopctl.ps1:622` | 报 `DISPATCH_TASKTYPE_MISMATCH`；探针覆盖，测试未覆盖（Minor-1）。 |
| 同 criterion 无未结束 attempt | `loopctl.ps1:623-624` | 检查 `planned/spawned/acked/running/returned`；TC-DSP-08E 覆盖。 |
| childId 缺省与 `<pending>` | `loopctl.ps1:628`、`810` | plan 缺省写 `<pending>`；spawned 时必须替换为真实值，否则 `DISPATCH_CHILD_ID_REQUIRED`；TC-DSP-08F 与探针覆盖。 |
| attempt 字段完整 | `loopctl.ps1:629-634` | 写入 dispatchId/taskId/idempotencyKey/role/taskType/criterionId/childId/status/envelopeRef/plannedAt；探针确认 State 落盘字段齐全。 |
| history 与原子性 | `loopctl.ps1:635-639` | ledger 与 `dispatch-plan` history 同一次 `Save-State` 写入；失败路径在保存前退出。探针确认所有拒绝场景 ledger 数量不变。 |
| 不 spawn child | 全文 | 无创建子 Agent 行为；仅登记账本。 |

补充：`envelopeRef` 会规范化为仓库相对路径（仓库外路径保留绝对形式）；`plannedAt` 为 UTC ISO-8601。`dispatch-plan` 不检查 Envelope 的 `taskId/stateRef` 与目标 State 的绑定关系，依赖调用方传入正确的 Envelope 与 criterion（见残余风险）。

### 4.2 `dispatch-transition` 扩展

| 检查项 | 代码位置 | 结论与证据 |
| --- | --- | --- |
| 原有前驱链不变 | `loopctl.ps1:806-807` | `planned→spawned→acked→running→returned→evaluated`，各非终态可 `failed`；TC-DSP-07 通过。 |
| childId 只在 spawned 更新 | `loopctl.ps1:808-811` | 有 `-ChildId` 时更新；仍为 `<pending>` 则拒绝；探针确认 `spawned` 后 childId/`spawnedAt` 正确。 |
| acked/running 时间戳 | `loopctl.ps1:812-815` | 写入 `ackedAt`/`runningAt`；探针确认。 |
| returned 强制 resultRef | `loopctl.ps1:816-818` | 缺失报 `DISPATCH_RESULT_REQUIRED`；TC-DSP-08G 覆盖。 |
| returned 强制 scopeVerified | `loopctl.ps1:818`、`821` | 未传 `-ScopeVerified` 报 `DISPATCH_SCOPE_UNVERIFIED`；TC-DSP-08H 覆盖。 |
| returned 结果文件存在 | `loopctl.ps1:819` | 不存在的文件报 `EVIDENCE_NOT_FOUND`；探针覆盖。 |
| returned 落盘字段 | `loopctl.ps1:820-822` | `resultRef`、`scopeVerified=true`、`returnedAt`；TC-DSP-08I 覆盖。 |
| evaluated/failed 时间戳 | `loopctl.ps1:823-827` | `evaluatedAt`/`failedAt`；`failed` 可记录 `failureReason`；探针覆盖 failed。evaluated 分支无新增测试，但代码路径简单且 `evaluate` 主路径已由 TC-DSP-08J 覆盖。 |
| 非 spawned 阶段 childId 漂移 | `loopctl.ps1:829-831` | acked/running/returned/evaluated 漂移报 `DISPATCH_CHILD_MISMATCH`（探针覆盖 acked）；`failed` 被豁免（Minor-2）。 |
| State 校验 | `loopctl.ps1:833-834` | 每次变更后 `Assert-State`，再原子保存；失败不落盘。 |

### 4.3 兼容性、schema 与业务代码

- Envelope schema（`.ai/schema/dispatch.schema.json`）与 State schema（`.ai/schema/loop-state.schema.json`）均未修改；`git status -- .ai/schema` 为空。
- 扫描 `.ai/state/` 53 个文件：32 个可按当前 JSON-compatible YAML 解析；其中所有 `returned/evaluated` attempt 均已有 `resultRef` 与 `scopeVerified=true`，未发现旧 State 因新校验失败。其余 21 个在本环境（未安装 `ConvertFrom-Yaml`）不可解析，属既有边界，本次未迁移、未改写。
- 业务代码未被本次任务修改：本次 diff 的审查文件集仅包含 `.ai/` 下的脚本、测试、文档与 State；工作区另有 `st-*` 等与本任务无关的未提交修改，未计入本结论（见残余风险）。
- 旧命令兼容：`validate`、`evaluate`、`complete`、`reconcile` 的代码未改动；旧 `returned` 调用形状因新增强制参数而变化，属设计意图，但设计/验证措辞需修正（Suggestion-1）。

### 4.4 测试

新增 TC-DSP-08 系列 10 个检查；TC-DSP-07 适配 returned 新参数。覆盖：

- 正向：plan 创建、spawned 补 childId、完整 returned 落盘、evaluate 绑定并完成 criterion；
- 负向：重复 dispatchId、未知 criterion、role mismatch、active attempt、spawned 缺 childId、returned 缺 resultRef/scopeVerified；
- 缺口：taskType mismatch、criterion done（Minor-1）；另外 `failed` 分支、childId 漂移拒绝、结果文件不存在没有直接断言，但本次探针已覆盖该三条代码路径。

命令结果见第 5 节。测试夹具使用唯一临时目录并在结束后清理，未在仓库留下测试目录。

### 4.5 文档

- protocol、template、runbook 已补充 `dispatch-plan`/`dispatch-transition` 操作命令，与脚本参数一致；
- 协议错误码/状态名与实现一致（`DISPATCH_*`、`CRITERION_*`）；
- 存在 Minor-3 与 Suggestion-4 所述措辞/示例问题；
- design/verification 的“旧调用兼容”措辞需修正（Suggestion-1）；
- TASK include 未列 runbook（Suggestion-3）。

## 5. 验证命令与结果

| 命令 | 结果 |
| --- | --- |
| `pwsh -NoProfile -File .ai/tests/loop-v2/unit/state-engine.tests.ps1` | `PASS_COUNT=55`、`FAIL_COUNT=0`、`ALL PASSED`，退出码 0（当前 BOM 恢复后的文件） |
| `pwsh -NoProfile -File .ai/tests/loop-v2/run-tests.ps1` | `SUITES_PASSED=3`、`ALL PASSED`，退出码 0 |
| `pwsh -NoProfile -File .ai/scripts/verify-loop.ps1 -StatePath .ai/state/20261017-dispatch-ledger.yaml` | 该 State `PASS`，`FAIL=0`，退出码 0 |
| `git diff --check -- .ai/scripts/loopctl.ps1 .ai/tests/loop-v2/unit/state-engine.tests.ps1 ...` | 退出码 0 |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/loopctl.ps1 validate .ai/state/20261017-dispatch-ledger.yaml` | `PASS`，退出码 0（Windows PowerShell 5.1.26100.9444） |
| `powershell.exe ... verify-loop.ps1 -StatePath ...` | State `PASS`，`FAIL=0`，退出码 0（5.1） |
| Windows PowerShell 5.1 临时夹具执行 `dispatch-plan` | `PLANNED`，退出码 0；随后 `validate` 为 `PASS` |

独立探针（仓库外临时 State/Envelope）覆盖并全部通过：

- plan 无 childId → `childId=<pending>`、`plannedAt`/`envelopeRef`/history 落盘，criterion 状态保持 `pending`；
- 重复 dispatchId → `DISPATCH_ALREADY_PLANNED`；同 criterion 第二次 plan → `DISPATCH_ATTEMPT_ACTIVE`；
- 未知 criterion → `CRITERION_UNKNOWN`；已 done → `CRITERION_ALREADY_DONE`；role/taskType mismatch 各自报码；
- 所有拒绝路径均未写入 ledger；
- spawned 无 childId 或字面 `<pending>` → `DISPATCH_CHILD_ID_REQUIRED`；带 childId → 成功并写 `spawnedAt`；
- acked 传不同 childId → `DISPATCH_CHILD_MISMATCH` 且不落盘；acked/running → 各自时间戳；
- returned 缺 resultRef → `DISPATCH_RESULT_REQUIRED`；文件不存在 → `EVIDENCE_NOT_FOUND`；缺 scopeVerified → `DISPATCH_SCOPE_UNVERIFIED`；齐全 → `resultRef`/`scopeVerified`/`returnedAt` 正确；
- failed + `-FailureReason` → `failureReason`/`failedAt` 正确；不同 childId 被静默忽略（Minor-2）。

## 6. 已关闭问题：`loopctl.ps1` BOM

- 审查开始时 `.ai/scripts/loopctl.ps1` 首字节为 `35,114,101`（无 BOM）；Windows PowerShell 5.1 解析该文件失败（`Unexpected token '}'`，涉及 473/532/655/574/873 行），同一文件补回 BOM 后可在 5.1 解析。该状态同时违反 `.ai/knowledge/conventions.md:233` 的编码规范。
- 主线程随后恢复 BOM。当前文件首字节为 `239,187,191`；用 Windows PowerShell 5.1.26100.9444 复检 `validate` 为 `PASS`，`verify-loop.ps1` 为 `FAIL=0`，临时夹具 `dispatch-plan` 为 `PLANNED`。
- 结论：**该问题在当前文件已关闭**，不作为本轮发现或阻断项。仅测试脚本 BOM 仍缺失，见 Suggestion-2（不影响 PS7 测试）。

## 7. 残余风险

1. **并发丢失更新**：`dispatch-plan` 与既有写命令一样是“读 State → 修改 → 临时文件替换”，没有跨进程锁；两个主线程并发 plan 同一 State 可能后写覆盖先写。当前 Loop 假设单 Workflow Manager 串行写入，未做并发测试。
2. **Envelope 与 State 的绑定未强校验**：`dispatch-plan` 只校验 criterion/role/taskType，不校验 `Envelope.stateRef` 是否指向目标 State，也不校验 `taskId` 与 State 的身份关系（历史约定中 Envelope taskId 为 `TASK-...`、State taskId 为 slug，不能直接相等）。传错 Envelope 但 criterion 恰好匹配时会被接受。建议后续校验 `stateRef` 归一化路径或引入显式任务身份映射。
3. **resultRef 未规范化**：`returned` 原样保存 `-ResultRef`；`evaluate` 要求 proposal.evidenceRef 与其字符串完全相等。传绝对路径或不同写法会导致 `DISPATCH_RESULT_MISMATCH`；与 `envelopeRef` 的规范化行为不一致。建议统一为仓库相对路径或文档明确要求。
4. **`dispatch-transition -DispatchStatus evaluated` 的旁路**：该命令可在不更新 criterion 的情况下把 attempt 标为 evaluated；之后 `evaluate` 会因 attempt 已 evaluated 直接输出 `UNCHANGED`，criterion 可能永远不完成。该路径沿用原有前驱链，非本次新增，但文档未提示；主线程应使用 `evaluate` 收敛。
5. **历史格式 State**：21 个非 JSON-compatible 历史 State 在本环境（未安装 `ConvertFrom-Yaml`）不可解析，本次未迁移；如需审计需单独迁移。
6. **共享工作区噪声**：工作区存在 `st-*` 业务源码与 `AGENTS.md` 等与本任务无关的未提交修改；本结论只针对本任务 diff 文件，未将这些修改归因于本任务。
7. **Evaluate 前置条件（主线程操作）**：当前 State `artifacts` 只有 `requirement/design`。`CODE_REVIEW` 的 catalog 必需产物是 `codereview.md`；主线程在 Evaluate 前需在 State 登记 `codereview` artifact（`status=ready`、`ref=.ai/docs/20261017-dispatch-ledger/codereview.md`、`provides=['CODE_REVIEW']`），否则会因 `CATALOG_ARTIFACT_MISSING` 失败。本 reviewer 未写 State。

## 8. 未做与边界

- 未运行 Maven/H2/MySQL/前端测试；本任务不涉及业务代码，相关门禁不适用。
- 未审计工作区中与本任务无关的 `st-*` 变更。
- 未修改源码、测试、State、Envelope、Git index，未创建子 Agent，未执行 Evaluate 或宣布 Goal 完成。
- Windows PowerShell 5.1 检查使用 `-ExecutionPolicy Bypass`（本机执行策略默认阻止脚本），不影响 BOM/解析结论。

## 9. Review 结论

**PASS**。`dispatch-plan` 与 `dispatch-transition` 的字段、校验、错误码与兼容性主路径符合 requirement/design/TASK 要求；未发现正确性、安全或 schema 阻断问题；测试与全量套件真实通过。

建议在进入最终 ACCEPT 前处理：Minor-1（补 taskType/criterion done 用例）、Minor-2（failed 的 childId 漂移语义）、Minor-3（协议 childId 定义）；其余为措辞、编码与文档建议。存在 7 项残余风险，均不改变本轮 PASS 结论。
