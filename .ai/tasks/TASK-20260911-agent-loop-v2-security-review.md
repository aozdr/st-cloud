# TASK-20260911-agent-loop-v2-security-review

## 目标

独立审查 Agent Loop V2 的 State、Dispatch、Worktree、hook、迁移与 CI 安全边界。

## include

- `AGENTS.md`
- `.ai/scripts/loopctl.ps1`
- `.ai/scripts/worktree.ps1`
- `.ai/scripts/run-loop-gate.ps1`
- `.ai/scripts/migrate-loop-state-v2.ps1`
- `.ai/scripts/install-hooks.ps1`
- `.ai/githooks/pre-commit`
- `.ai/schema/**`
- `.github/workflows/ai-loop-gate.yml`

## exclude

- 所有业务代码和数据库

## 检查重点

- 路径穿越、scope 绕过、Git 参数注入、符号链接/重解析点。
- State 证据伪造、旧 attempt 覆盖、职责分离、hook fail-open。
- 迁移半写、归档覆盖、CI 与本地规则漂移。

## 输出

只读返回发现与结论，不直接改文件。主线程写入 `.ai/docs/20260911-agent-loop-v2/security.md`。
