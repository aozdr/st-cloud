# Agent Dispatch Protocol V2

> 当前唯一 Dispatch 运行协议。历史协议位于 `.ai/archive/protocols/`，仅供审计，禁止加载为当前指令。

## 1. 唯一传输路径

主线程直接完成的非独立标准可使用 `loopctl evaluate-direct` 记账：提交真实任务、执行者、当前修订和产物证据，不创建或伪造 dispatch。reviewer 标准及角色分离名单中的标准默认禁止此路径，仍须按下方唯一 Dispatch 传输路径取得独立结果。若用户明确要求同一执行者完成当前任务的剩余评审与验收，可在 State 中逐项记录 `singleAgentAuthorization`（任务、执行者、用户原话、证据及标准），并将结果如实标为自检；该授权不能跨任务复用，也不能声称独立评审。`evaluate-direct` 只接受通过结果，并保持原有依赖、确认、产物和 State 校验。

Dispatch 只通过当前 Codex Runtime 的子 Agent 创建消息传递：

```text
TASK
  ↓ build + schema validate
Dispatch Envelope
  ↓ spawn_agent(message=<完整 Envelope>)
child
  ↓ DISPATCH_ACK
execute
  ↓ 独立 result + criterionProposal
Workflow Manager Evaluate
```

写 TASK、State 或结果文件不等于已派发。只有完整 Envelope 实际传入 `spawn_agent.message` 才是一次派发。禁止添加第二传输路径，也禁止 child 自行扫描项目猜任务。

## 2. 身份与幂等

- `taskId`：TASK 的稳定业务标识。
- `idempotencyKey`：同一 TASK 的稳定幂等键，所有重试保持不变。
- `dispatchId`：单次 attempt 的唯一标识；每次重派必须生成新值。
- `taskCode`：可选的人类可读标签，不参与身份、ACK 或幂等判断。
- `childId`：Runtime 返回的执行实例标识，由主线程记入 dispatchLedger。

主线程以 `dispatchId` 归集一次 attempt，以 `idempotencyKey` 识别它们属于同一 TASK。旧 attempt 的迟到结果不能覆盖新 attempt。

## 3. Envelope

唯一结构定义为 `.ai/schema/dispatch.schema.json`。发送前必须通过 schema 校验；不得在其他文档维护第二份必填字段列表。

额外语义约束：

- 一个 Envelope 的 `taskRefs` 只描述同一独立任务所需的稳定输入，不得捆绑多个可独立验收的 TASK；
- `scope.include` 是写入白名单，`scope.exclude` 优先级更高；
- `stateRef` 供定位，child 只读取与 TASK 相关的最小 State 快照，不得写 State；
- `forbidSpawn` 必须为 `true`；
- `skillRefs` 使用运行时技能注册表标识；列出的技能在 ACK 后、执行前完整读取，`-` 表示无额外技能。它不是仓库文件引用，不用 `taskRefs` 的路径规则校验。

推荐以 `.ai/templates/dispatch-template.md` 构建消息。

## 4. 创建与并行

仅对已派发 TASK 适用“一 TASK / 一 Envelope / 一 child”；small 直接路径不创建 Dispatch：

```text
TASK-A → envelope-A → child-A
TASK-B → envelope-B → child-B
```

同一批次应先构建并验证所有 Envelope，再创建 child。无依赖 TASK 可以并行创建和执行；不得复用可变 message，不得用 child 的创建顺序或位置识别任务。

Runtime 上下文参数不是 Dispatch 字段。无论采用 fresh 还是 bounded context，child 的唯一任务来源仍是本次 `message`。

## 5. ACK 门禁

child 的首条输出必须是：

```text
DISPATCH_ACK
dispatchId: <Envelope.dispatchId>
taskId: <Envelope.taskId>
role: <Envelope.role>
```

ACK 只校验 `dispatchId/taskId/role` 三元组。`taskCode`、文件路径、child 位置或自然语言 objective 都不参与 ACK 身份校验。

ACK 前 child 禁止调用工具。ACK 仅证明消息已送达，不代表任务完成；child 必须在同轮继续执行。

主线程为每次 attempt 在 dispatchLedger 记录：

```text
planned → spawned → acked → running → returned → evaluated
                                     └→ failed
```

只有 ACK 三元组完全匹配，attempt 才能进入 `acked`。错配结果标记为 failed，关闭该 child，不得猜测修正。

## 6. 子 Agent 执行边界

ACK 后 child：

1. 校验 Envelope；
2. 读取 `skillRefs`、`taskRefs` 和最小 State 快照；
3. 只在 scope 内执行；
4. 写入该 dispatch 独立的结果文件；
5. 返回事实结果、证据和 proposal。

child 不得：

- 修改 Loop State 或把 exitCriterion 直接标为 done；
- 定义 Goal、执行 Evaluate 或宣布整个迭代完成；
- 创建/指挥其他 Agent；
- 向用户请求确认；
- 与其他 child 共同追加同一结果或 changereport 文件。

需要确认时返回 `confirmationRequest`，需要其他能力时返回 `delegationRequest`。主线程决定后续动作。

## 7. 结果契约

每个 attempt 使用独立结果路径，例如 `.ai/runtime/results/<dispatchId>.json`。结果至少包含：

```yaml
dispatchId: DISPATCH-...
taskId: TASK-...
status: completed | blocked | failed
artifactRefs: []
criterionProposal:
  id: IMPLEMENTED
  outcome: pass | fail | blocked | skip
  by: <child identity>
  dispatchId: DISPATCH-...
  evidenceRef: .ai/runtime/results/<dispatchId>.json
  validatedRevision: <design-or-code-revision>
blockerProposals: []
```

`skip` 仅供 canonical 定义声明可跳过的标准使用；当前只有中型 `SECURITY_REVIEW` 可在提供 `skipReason/approvedBy` 后使用。`criterionProposal` 只是建议。主线程核对 schema、attempt、scope、产物、证据、revision 和 DAG 后，才可通过 Evaluate 改变 State。

共享 `changereport.md` 由主线程串行合并各独立结果，避免并发追加竞态。

## 8. 失败与恢复

| 情况 | attempt 状态 | 恢复 |
|---|---|---|
| Envelope schema 无效 | 不得 spawn | 修正原 Envelope |
| 无 ACK / ACK 超时 | failed | 关闭 child；新 dispatchId 重派 |
| ACK 三元组错配 | failed | 关闭 child；新 dispatchId 重派 |
| 只返回 ACK，无独立结果 | failed（ACK_ONLY） | 关闭 child；新 dispatchId 重派 |
| child 返回 `DISPATCH_INVALID` | failed | 修正构建逻辑；新 dispatchId 重派 |
| 结果属于旧 attempt | 保留审计，不 Evaluate | 使用当前 attempt 结果 |
| 真实外部条件阻塞 | returned/blocked | 由主线程建立业务 blocker |
| scope 越界或证据缺失 | returned/rejected | 定向 rework，禁止 Evaluate 为 pass |

同一 TASK 的自动重试保持 `taskId/idempotencyKey` 不变，每次创建新的 `dispatchId` 和 child。Dispatch 故障只进入 dispatchLedger，不增加业务 blocker attempts。

若 Runtime 无法把 message 送达 child，报告 `DISPATCH_RUNTIME_INJECTION_FAILED` 并停止该 TASK；不得启用其他投递路径。

## 9. 生命周期与 Evaluate

child 返回后，主线程应立即：

1. 读取独立结果；
2. 核对 `dispatchId/taskId` 和当前 attempt；
3. 验证 scope、artifact、evidence、validatedRevision；
4. 关闭 child；
5. 调用状态迁移工具执行 Evaluate；
6. 更新 ledger/history 并重新 Plan。

阶段切换前只需回收会阻塞当前依赖、共享资源或最终收敛的 child；无关 child 不阻塞当前阶段。最终状态是否完成只由 Workflow Manager 根据当前 State 与 Goal completionCriteria 判定。
