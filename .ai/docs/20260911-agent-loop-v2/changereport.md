# Agent Loop V2 Change Report

## 变更

- 统一 Dispatch 为 Direct Message；File Inbox 与第三方模型兼容协议移入 archive，不再参与运行。
- 新增 V2 State/Dispatch Schema、`loopctl` 状态机、严格证据与 revision 门禁。
- 重写 worktree 生命周期，增加幂等事务台账、scope 校验、资源对账和恢复路径。
- 新增统一本地/CI 门禁、fail-closed pre-commit、V1 未完成 State 迁移器。
- 压缩 `AGENTS.md` 与 workflow-manager，只保留入口和硬约束；详细协议集中到唯一事实源。

## 安全修复

- proposal 与当前 dispatch attempt 的 task/role/taskType/criterion/child/resultRef 全绑定。
- evidence、confirmationArtifact 必须是仓库内真实文件，且路径不得经过重解析点。
- pre-commit 防止删除、未暂存或未跟踪文件造成暂存快照假绿。
- Worktree ledger 每次读取均验证受控路径、分支、SHA 与 scope。
- State 迁移支持 dry-run/WhatIf、全量预检、故障回滚和重试。

## 影响范围

只修改 `.ai/**`、`AGENTS.md` 与 `.github/workflows/ai-loop-gate.yml`。未修改业务代码、API、数据库或部署配置。
