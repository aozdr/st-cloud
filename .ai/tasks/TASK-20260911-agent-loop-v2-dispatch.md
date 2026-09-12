# TASK：Agent Loop V2 Direct Message 协议与文档瘦身

## 元信息

- Task ID: `TASK-20260911-agent-loop-v2-dispatch`
- State: `.ai/state/20260911-agent-loop-v2.yaml`
- Design: `.ai/docs/20260911-agent-loop-v2/design.md`
- Testcases: `.ai/docs/20260911-agent-loop-v2/testcases.md`
- Agent: executor（taskType=implement）

## 目标

删除活跃 File Inbox/第三方模型兼容路径，将当前协议收敛为单一 Direct Message Dispatch，并把历史协议归档。

## 修改范围

- `AGENTS.md`
- `.ai/agents/workflow-manager.md`
- `.ai/knowledge/agent-dispatch-protocol.md`
- `.ai/knowledge/loop-state-model.md`
- `.ai/knowledge/loop-verification-checklist.md`
- `.ai/templates/dispatch-template.md`
- `.ai/archive/protocols/**`
- 以下历史文档允许移动到 archive：
  - `.ai/archive/protocols/file-dispatch-runtime.md`（从 knowledge 归档）
  - `.ai/archive/protocols/parallel-dispatch-runtime-v7.md`（从 knowledge 归档）
  - `.ai/archive/protocols/parallel-dispatch-runtime-v8.md`（从 knowledge 归档）
  - `.ai/archive/protocols/task-isolation-migration.md`（从 knowledge 归档）

## 禁止修改范围

- `.ai/scripts/**`、`.ai/schema/**`、`.ai/loop/**`、`.ai/state/**`
- 业务代码与数据库

## 验收标准

- 唯一传输为 spawn_agent message；无 File Inbox、第三方 provider、双写和认领活跃规则
- 一个 TASK/Envelope/child；ACK 校验 dispatchId/taskId/role
- attempt 使用新 dispatchId，TASK 使用稳定 idempotencyKey
- Agent 只产出 criterionProposal/独立结果，主线程独占 Evaluate
- 历史协议归档后不会被当作当前指令
- AGENTS.md 明显瘦身且不再重复协议正文

## 验证

- 全仓搜索活跃文档中的 File Inbox、third-party、认领、双写规则
- 检查所有当前协议引用有效

## 输出

- 独立结果写入 `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-DP-01.md`
