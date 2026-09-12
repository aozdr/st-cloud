# TASK：Agent Loop V2 Worktree 生命周期事务化

## 元信息

- Task ID: `TASK-20260911-agent-loop-v2-worktree`
- State: `.ai/state/20260911-agent-loop-v2.yaml`
- Design: `.ai/docs/20260911-agent-loop-v2/design.md`
- Testcases: `.ai/docs/20260911-agent-loop-v2/testcases.md`
- Agent: executor（taskType=implement）

## 目标

将 worktree 生命周期改为可重试、可对账、强制 scope 校验的事务化流程。

## 修改范围

- `.ai/scripts/worktree.ps1`
- `.ai/tests/worktree-v2/**`

## 禁止修改范围

- 其他 `.ai/**`
- 业务代码、数据库、Git 配置
- 禁止执行真实 commit/merge/cleanup；测试必须使用临时仓库

## 验收标准

- prepare/commit/merge/verify/cleanup 可分别重试
- 保存并核验 baseSha、commitSha、targetBranch、worktreePath
- commit 前强制 scope include/exclude
- 主树不干净、分支错误、HEAD 漂移和残留 worktree 返回非零
- 并行批次不再自动降级共享目录
- 测试覆盖 commit 成功但 merge 失败、scope 越界和孤儿 worktree

## 验证

- 在临时 Git 仓库执行 `.ai/tests/worktree-v2/` 测试

## 输出

- 独立结果写入 `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-WT-01.md`
