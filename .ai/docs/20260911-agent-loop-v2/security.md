# Agent Loop V2 安全审查

## 初审

结论：`FAIL`。独立 reviewer 发现以下风险：

- Evaluate 未把 proposal 完整绑定到当前 attempt 的 task、role、taskType、criterion、child 与 resultRef。
- `acceptanceEvidence[*].validatedRevision` 未强制等于当前代码 revision。
- pre-commit 可被纯删除或 staged-invalid/working-valid 组合绕过。
- CI workflow 自身变更未触发门禁。
- Worktree ledger 缺少 taskCode/path/branch/SHA/scope 不变量校验，祖先重解析点检查不足。
- Dispatch 引用路径未拒绝绝对路径与 `..`。
- State 批量迁移未完整使用 ShouldProcess，归档冲突与中途失败可能留下半迁移。

## 修复

- Evaluate 现在强制核对上述全部 attempt 身份字段，且 `evidenceRef == attempt.resultRef`。
- ACCEPT 逐项证据必须匹配 `state.revision.code`。
- hook 包含删除项，相关路径存在未暂存差异时直接拒绝，并移除对 `grep` 的依赖。
- CI `paths` 已包含 `.github/workflows/ai-loop-gate.yml`。
- Worktree 每次读取 ledger 都校验受控路径/分支/SHA/scope，并检查目标路径全部祖先的重解析点。
- Dispatch 所有引用拒绝绝对路径和 `..`。
- 迁移器先全量预检与验证，再写入；支持 `-WhatIf`，失败时回滚已迁移项。

## 证据

- Loop V2 状态/安全/迁移/hook 测试：2 个 suite 全部通过。
- Worktree V2：8 组测试全部通过。
- 统一门禁：`LOOP_GATE_PASS`。

## 复审

最终独立复审结论：`PASS`，validated revision 为 `loop-v2-r3-20260912`。上轮 8 项阻断全部关闭；复审还执行了 `git diff --check` 和当前 State validate，均通过。
