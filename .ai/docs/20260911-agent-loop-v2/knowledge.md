# Agent Loop V2 知识同步

## 当前事实源

- Dispatch 行为：`.ai/knowledge/agent-dispatch-protocol.md`
- Dispatch 结构：`.ai/schema/dispatch.schema.json`
- State 结构：`.ai/schema/loop-state.schema.json`
- 退出标准与 DAG：`.ai/loop/exit-criteria.yaml`
- Worktree 生命周期：`.ai/scripts/worktree.ps1`
- 统一本地/CI 门禁：`.ai/scripts/run-loop-gate.ps1`

## 关键决策

- 只支持 Codex Direct Message 子 Agent；不保留第三方 provider 和 File Inbox 兼容分支。
- `AGENTS.md` 只保留所有 Agent 必看的入口与硬约束，协议正文不重复。
- child 只返回 proposal；只有 Workflow Manager 可 Evaluate 和写 State。
- 证据必须绑定当前 revision 和当前 dispatch attempt，并指向仓库内真实文件。
- 历史协议与 V1 State 分别保留在 `.ai/archive/protocols/`、`.ai/archive/state-v1/`，不得作为当前指令执行。
- 本地 hook 与 CI 使用同一 `run-loop-gate.ps1`，包含静态校验、State 反例、迁移/hook 反例、Worktree 测试与资源对账。

## 运维入口

- 安装 hook：`pwsh -NoProfile -File .ai/scripts/install-hooks.ps1`
- 完整门禁：`pwsh -NoProfile -File .ai/scripts/run-loop-gate.ps1`
- State 校验：`pwsh -NoProfile -File .ai/scripts/loopctl.ps1 validate <state>`
- V1 未完成 State 迁移预览：`pwsh -NoProfile -File .ai/scripts/migrate-loop-state-v2.ps1`
