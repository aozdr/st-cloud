# TASK-20260912-agent-loop-v2-knowledge

## 目标

核对 Agent Loop V2 的唯一事实源、关键决策和运维入口，形成可供后续 Agent 直接使用的知识文档。

## include

- `.ai/docs/20260911-agent-loop-v2/knowledge.md`
- `.ai/knowledge/agent-dispatch-protocol.md`
- `.ai/loop/exit-criteria.yaml`
- `.ai/scripts/run-loop-gate.ps1`
- `.ai/scripts/loopctl.ps1`
- `.ai/scripts/worktree.ps1`

## exclude

- 业务代码
- 数据库
- 当前协议之外的历史资料改写

## 验收

- 知识文档列出当前事实源、关键决策和运维入口。
- 第三方模型与 File Inbox 只作为已移除的历史兼容背景出现。
- 文档引用的路径真实存在。

## 输出

- `.ai/docs/20260911-agent-loop-v2/knowledge.md`
- `.ai/runtime/results/DIRECT-20260912-LOOPV2-KNOWLEDGE.json`
