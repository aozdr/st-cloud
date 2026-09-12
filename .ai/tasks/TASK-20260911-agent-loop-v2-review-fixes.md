# TASK-20260911-agent-loop-v2-review-fixes

## 目标

修复 Loop V2 初审发现的假绿、attempt 绑定、Schema、恢复路径和 Phase 6 门禁/迁移缺口。

## include

- `.ai/scripts/loopctl.ps1`
- `.ai/scripts/run-loop-gate.ps1`
- `.ai/scripts/migrate-loop-state-v2.ps1`
- `.ai/scripts/install-hooks.ps1`
- `.ai/githooks/pre-commit`
- `.github/workflows/ai-loop-gate.yml`
- `.ai/schema/**`
- `.ai/tests/loop-v2/**`
- `.ai/knowledge/**`
- `.ai/state/**`
- `.ai/archive/state-v1/**`
- `.ai/docs/20260911-agent-loop-v2/**`
- `.ai/docs/20260910-ai-process-optimization/codereview.md`

## exclude

- 业务代码
- 数据库结构与迁移

## 验收

- 初审 P0/P1 全部有实现和回归证据。
- 本地与 CI 使用同一严格门禁，缺 PowerShell/仓库根时 fail-closed。
- 所有 running/incomplete V1 State 可审计地迁移，完成态历史不变。
