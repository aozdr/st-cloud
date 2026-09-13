# Workflow Manager（Agent Loop V2）

> 主线程唯一职责：把用户需求转换为可验证的 Loop，并独占 Plan、Evaluate、State 写入和最终验收。

当前事实源：

- 退出标准：`.ai/loop/exit-criteria.yaml`
- State 结构：`.ai/schema/loop-state.schema.json`
- Dispatch 结构：`.ai/schema/dispatch.schema.json`
- Dispatch 运行协议：`.ai/knowledge/agent-dispatch-protocol.md`
- Worktree 操作：`.ai/scripts/worktree.ps1`

本文件说明编排职责，不复制上述协议或 schema。历史版本在 `.ai/archive/protocols/`，不得作为当前指令加载。

## 1. 用户入口

收到需要落地的修改需求后启动；只读咨询、诊断和审查直接交付结论：

```text
Observe → Goal → Scale → Plan → Act → Evaluate
```

“尚未生成 TASK”是编排器的待办，不是用户缺少需求。低风险 small 任务可走直接路径，完成修改和相称验证后结束，不创建持久化 Loop 或伪造 dispatch。除非用户明确要求待命，不得让用户学习或填写 Agent Loop 协议。

## 2. Observe

中型及以上任务读取或初始化当前 State，并核对：

- Goal、规模、iteration、未满足 exitCriteria；
- open/escalated blockers；
- artifacts 与实际文件；
- 当前 design/code revision；
- dispatchLedger 中未结束的 attempt；
- 活跃 child、worktree、branch 与 State 是否一致。

小型直接路径不写持久化 State；只在对话中保留目标、范围和验证记录。

State 不可信时先修复真实性，不能在错误基线上继续推进。

## 3. Goal 与 Scale

中型及以上任务的 Goal 必须包含：

- `objective`：客观目标；
- `scope`：影响和禁止范围；
- `completionCriteria`：可逐项验证的完成标准。

小型直接路径只保留必要的目标、范围和验证记录，不初始化完整 State。

规模判断：

- small：低风险、局部 Bug、配置或样式微调；
- medium：单模块功能、新接口或具有明显行为影响的变更；
- large：跨模块、数据模型或上传/下载/分享/权限/配额等核心流程。

从 `.ai/loop/exit-criteria.yaml` 加载完整标准集，不在本文手写第二份 DAG。

## 4. Plan

每轮根据当前 State 重新选择最高价值动作，而不是机械执行上轮“下一步”。Plan 至少说明：

1. 修改范围；
2. 实现步骤；
3. 风险；
4. 验证方式。

以下情况必须暂停并等待用户确认：

- 中型 `design.md` 存在范围、兼容性或风险未决事项时；
- 大型 `requirement.md`（涉及 UI 时包含 `uispec.md`）存在范围、兼容性或风险未决事项时；
- 大型 `architecture-review.md`/`design.md` 存在范围、兼容性或风险未决事项时；
- 数据库、API 契约、跨模块或不可逆方案尚未定版。

确认门禁满足后才创建依赖该决策的下游 TASK；已确认或无未决事项时不暂停。

## 5. TASK 与角色

中型及以上任务在执行前必须有 `.ai/tasks/TASK-*.md`。TASK 应包含唯一目标、输入、scope、验收、验证、产物和禁止事项；small 直接路径不创建 TASK。

| role | taskType 示例 | 边界 |
|---|---|---|
| executor | requirement、impact、architecture、design、implement、knowledge | 产出内容，不判定最终通过 |
| reviewer | review、security、exp-review、accept | 独立审查，不替代实现者修改业务代码 |
| tester | testcases、test | 设计或执行测试，不替代实现者修改业务代码 |

子 Agent 不定义 Goal、不互相派发、不直接修改 State。实现者与评审/验收者遵守 `.ai/loop/exit-criteria.yaml` 的职责分离。

大型任务只有在 scope 涉及页面、交互或视觉验收时才派发 EXP_DESIGN/EXP_ACCEPT 并将两项标记 `applicable: true`；无 UI 时将两项标记 `applicable: false`，写入跳过原因与证据，不为满足项数额外派发评审。

## 6. Dispatch

每个已派发 TASK 生成一个符合 `.ai/schema/dispatch.schema.json` 的 Envelope，并把完整 Envelope 作为 `spawn_agent` 的 `message` 直接传入一个独立 child。

硬规则：

- 一已派发 TASK / 一 Envelope / 一 child；
- TASK 的 `idempotencyKey` 跨 attempt 稳定；
- 每次 attempt 使用新的 `dispatchId`；
- `taskCode` 如保留，仅是可读标签，不作为身份或唯一键；
- ACK 只核对 `dispatchId`、`taskId`、`role`；
- spawn 前完成 schema 校验，spawn 后登记 childId；
- 无依赖任务可并行派发；同一批次先完整构建并验证所有 Envelope；
- child 返回后立刻核对产物并关闭线程；阶段切换前只清理会阻塞当前依赖、共享资源或最终收敛的旧 child。

重试和错误恢复完全遵循 `.ai/knowledge/agent-dispatch-protocol.md`。禁止添加文件投递、备用通道或共享结果追加。

## 7. 子 Agent 结果

每个 child 写自己的独立结果文件，并返回：

```yaml
dispatchId: DISPATCH-...
taskId: TASK-...
status: completed | blocked | failed
artifactRefs: []
criterionProposal:
  id: IMPLEMENTED
  outcome: pass | fail | blocked
  evidenceRef: .ai/runtime/results/<dispatchId>.json
  validatedRevision: <revision>
blockerProposals: []
```

这只是 proposal，不是 State Delta 的已应用事实。共享 `changereport.md` 由主线程串行合并，避免多个 child 同时追加。

## 8. Evaluate

主线程是唯一 Evaluate 执行者：

1. 核对返回的 `dispatchId/taskId`、独立结果文件和 TASK 约定产物；
2. 拒绝旧 attempt、错 revision、缺证据或 scope 越界的 proposal；
3. 调用状态迁移工具应用 proposal；
4. 强制检查 canonical DAG、确认门禁、职责分离、blocker 和 artifact；每个 done 标准的 catalog.artifacts 均须登记并指向真实文件，不适用的条件标准须 skipped 且有证据；
5. design/code revision 改变时，从 DAG 自动计算并应用 stale cascade；
6. 更新 dispatchLedger、结构化 history 和 iteration；
7. 重新 Observe 并选择下一轮动作。

Agent 自述“完成”不能替代 Evaluate。子 Agent 不得直接把 exitCriterion 写成 `done`。

## 9. Rework 与失败

- 业务缺陷：创建/更新稳定 fingerprint 的 blocker，重新 Plan 修复；修复仍未解决才增加 repair attempt。
- 代码变化：重开 IMPLEMENTED，并使绑定旧 code revision 的下游证据 stale。
- Dispatch 失败：只更新 dispatchLedger，不计入业务 blocker attempts。
- 同一 TASK 重派：保留相同 `idempotencyKey`，创建新 `dispatchId` 和新 child；旧 attempt 的迟到结果不得覆盖新 attempt。
- ACK_ONLY、ACK 错配、超时或结果缺失：关闭对应 child，标记 attempt failed，再按协议决定是否重试。
- 同一 blocker 修复 3 次仍 open，或 iteration 超规模上限：State 转 `blocked_escalation`，请求人工裁决。

## 10. 实现与 Worktree

并行实现 TASK 的文件集合必须零重叠。需要 Worktree 隔离时：

```text
prepare → child implement → scope check → commit → merge → verify → cleanup
```

主线程独占 Git 写生命周期；child 遵守 Envelope 的 `forbidGitMvn`。任一步失败保留可恢复证据并停止后续危险动作，不自动降级到共享目录。集成构建与测试由主线程或单一 tester 串行运行。

## 11. 收敛

只有以下条件全部满足才能 `status: done`：

- 当前规模全部 exitCriteria 已通过 Evaluate；
- 所有证据绑定当前 revision；
- 无 open/escalated blocker；
- 无未结束 dispatch、活跃 child 或残留 worktree/branch；
- ACCEPT 已逐项覆盖 Goal completionCriteria；
- 文档、代码和知识库一致。

验收失败时重新 Plan；不得跳过下游复检或沿用旧 revision 结论。
