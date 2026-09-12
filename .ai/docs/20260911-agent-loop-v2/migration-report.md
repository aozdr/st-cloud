# Agent Loop V2 迁移报告

## 范围

一次性切换到 Direct Message + V2 State，不保留第三方模型或 File Inbox 活跃兼容路径。

## 结果

- 6 个 `running/incomplete` V1 State 已转换为合法 V2 State。
- 原始文件逐一保存到 `.ai/archive/state-v1/`，只供审计，不作为当前指令加载。
- 迁移后的标准全部保守设为 pending，避免把缺少 V2 证据的历史 done 状态继承为有效结论。
- 已完成的历史 State 未修改。

迁移对象：

- `20260812-schema-versioning.yaml`
- `20260815-new-file.yaml`
- `20260817-transaction-boundary.yaml`
- `20260817-worktree-isolation.yaml`
- `20260820-ui-refactor.yaml`
- `20260910-ai-process-optimization.yaml`

## 恢复

迁移器 `.ai/scripts/migrate-loop-state-v2.ps1` 默认只 dry-run；显式 `-Apply` 才写入。每个目标先生成临时 V2 State 并调用 `loopctl validate`，验证通过后才归档原文件并原子替换。
