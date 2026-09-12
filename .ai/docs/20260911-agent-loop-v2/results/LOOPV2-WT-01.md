# LOOPV2-WT-01 实现结果

## 背景

旧 Worktree 脚本缺少可恢复台账、scope 强制校验和严格孤儿资源检测，并残留 File Inbox 的 `wait-claim` 路径。

## 输入

- TASK：`.ai/tasks/TASK-20260911-agent-loop-v2-worktree.md`
- Design：`.ai/docs/20260911-agent-loop-v2/design.md`
- Testcases：`.ai/docs/20260911-agent-loop-v2/testcases.md`

## 分析

Windows PowerShell 会把 Git 的正常 stderr 进度信息转换为 `NativeCommandError`。外部命令封装因此临时放宽错误流处理，命令结束后恢复，并始终以进程退出码判断成败。

## 决策

1. 生命周期拆分为 `prepare`、`commit`、`merge`、`verify`、`cleanup`，每一步可独立重试。
2. Git common dir 下保存事务台账，记录 baseSha、commitSha、targetBranch、worktreePath、scope 和阶段状态。
3. commit 前强制检查 include/exclude，越界内容不会进入索引。
4. merge 前检查主树干净、目标分支和 baseSha；commit 已完成但 merge 失败时可以恢复后重试。
5. `reconcile -Strict` 对孤儿 worktree、branch 和 ledger 返回非零。
6. cleanup 必须在 verify 之后执行；删除旧 `wait-claim` 和 File Inbox 逻辑。
7. 不再提供并发批次自动降级到共享目录的执行路径。

## 验证

`powershell -NoProfile -ExecutionPolicy Bypass -File .ai/tests/worktree-v2/Run-Tests.ps1`

结果：退出码 0，6/6 通过，覆盖完整流程与幂等、scope 越界、merge 恢复、主树/分支保护、孤儿资源和 cleanup 门禁。全部测试在临时 Git 仓库执行。

## State Delta

```yaml
dispatchId: DISPATCH-20260911-LOOPV2-WT-01
taskId: TASK-20260911-agent-loop-v2-worktree
status: completed
criterionProposal:
  id: IMPLEMENTED
  outcome: pass
  evidenceRef: .ai/docs/20260911-agent-loop-v2/results/LOOPV2-WT-01.md
  validatedRevision: working-tree-20260911-loop-v2-impl1
blockerProposals: []
```

## 风险

- 事务台账位于 Git common dir，不进入版本库。
- scope 使用 glob 转正则匹配，新增特殊模式前应补测试。

## 下一步

主线程迁移 V2 State、执行完整门禁，并进入独立 Review 与 Security Review。

## 变更影响

只修改 Worktree 工具及其临时仓库测试，没有修改业务代码、数据库或当前仓库 Git 历史。
