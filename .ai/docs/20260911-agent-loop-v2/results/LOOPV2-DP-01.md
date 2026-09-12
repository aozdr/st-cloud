# LOOPV2-DP-01 实现结果

## 背景

Agent Loop V2 要求把活跃 Dispatch 收敛为 Direct Message 单一路径，移除文件投递和旧运行时兼容规则，并避免子 Agent 直接改变 Loop State。

## 输入

- TASK：`.ai/tasks/TASK-20260911-agent-loop-v2-dispatch.md`
- State：`.ai/state/20260911-agent-loop-v2.yaml`（只读，IMPLEMENTED=in_progress）
- 已确认设计：`.ai/docs/20260911-agent-loop-v2/design.md`
- 当前 schema：`.ai/schema/dispatch.schema.json`
- taskCode：`LOOPV2-DP-01`

## 分析

旧规则在 `AGENTS.md`、Workflow Manager、Dispatch 协议和模板中重复定义，且同时包含直接消息、文件投递、消息双写、顺序认领和多版 ACK 判据。历史运行时文档仍位于 knowledge 目录，容易被当作当前指令。

## 决策

1. 当前唯一传输方式为 `spawn_agent.message`；一个 TASK 对应一个 Envelope 和一个 child。
2. ACK 只校验 `dispatchId/taskId/role`。
3. 同一 TASK 使用稳定 `idempotencyKey`；每次 attempt 使用新 `dispatchId`。
4. 子 Agent 只写独立结果并返回 `criterionProposal`；Workflow Manager 独占 Evaluate 与 State 写入。
5. 四份旧运行时文档移入 `.ai/archive/protocols/`，并增加归档禁用声明和索引。
6. `AGENTS.md` 只保留身份、确认门禁、安全和工程硬约束；协议正文只存在于 `agent-dispatch-protocol.md`。

## 验证

- scoped 活跃文档已无文件投递、第三方 provider、消息双写、文件认领或 claimedFile 执行分支；相关文字只保留于 archive 历史内容和“禁止使用”的验收表述。
- 当前协议交叉引用均指向存在的 schema、exit criteria、模板、Workflow Manager 和 Worktree 工具。
- 原四个 `.ai/knowledge/` 历史协议路径已移除，对应文件存在于 `.ai/archive/protocols/`。
- `AGENTS.md` 从 699 行缩减为约 122 行；Workflow Manager 从 1301 行缩减为约 164 行。
- 未运行 Maven/Git 写操作；未修改 State、业务代码、数据库或 scope.exclude。

## State Delta

```yaml
dispatchId: DISPATCH-20260911-LOOPV2-DP-01
taskId: TASK-20260911-agent-loop-v2-dispatch
status: completed
artifactRefs:
  - .ai/docs/20260911-agent-loop-v2/results/LOOPV2-DP-01.md
criterionProposal:
  id: IMPLEMENTED
  outcome: pass
  evidenceRef: .ai/docs/20260911-agent-loop-v2/results/LOOPV2-DP-01.md
  validatedRevision: working-tree
blockerProposals: []
```

该段仅为 proposal，未修改 `.ai/state/20260911-agent-loop-v2.yaml`。由主线程结合其他实现 TASK 执行 Evaluate。

## 风险

- `.ai/scripts/worktree.ps1` 仍含旧文件确认实现，但在本 TASK 的 `scope.exclude` 中；必须由 Worktree 专项 TASK 清理，当前结果不越界修改。
- 历史 TASK/State/Docs 仍会出现旧术语，它们是执行记录，不是当前协议入口。

## 下一步

主线程收集 State Engine、Dispatch 和 Worktree 三路结果后，统一运行协议扫描、schema/状态机测试和 Worktree 验证，再进入 Code Review。

## 变更影响

当前 Agent 入口、编排器职责、Dispatch 模板和状态模型口径已统一；新 attempt 不再依赖文件侧信道，且 child 结果必须经过主线程 Evaluate 才能影响 State。
