# TASK：V15 Worktree 隔离基础设施落地

> 依据 `.ai/docs/20260817-worktree-isolation/design.md` 生成。

## 元信息

- Task ID: `TASK-20260817-worktree-isolation-01`
- 关联任务 State: `.ai/state/20260817-worktree-isolation.yaml`
- 关联文档: `.ai/docs/20260817-worktree-isolation/design.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-17

## 目标

落地 design.md 定义的基础设施：`.gitignore`、`worktree.ps1`、`dispatch-template.md` V9、知识库文档、`AGENTS.md`、版本号 V15。

## 修改范围

- `.gitignore`：新增 `.ai/worktrees/`
- `.ai/scripts/worktree.ps1`：新增（create / list / commit-merge / cleanup / verify，UTF-8 with BOM）
- `.ai/templates/dispatch-template.md`：升 V9（新增 worktreeRoot / mainRoot / forbidGitMvn）
- `.ai/knowledge/parallel-dispatch-runtime-v8.md`：追加 V9 章节
- `.ai/knowledge/task-isolation-migration.md`：升 V7
- `AGENTS.md`：并行实现协议补「实现阶段 Worktree 隔离」硬规则小节
- `AI-AGENT-LOOP-VERSION.txt`：V14 → V15

## 禁止修改范围

- `st-*` 任何产品代码（不新增、不修改、不删除）
- `.ai/state/` 与 `.ai/docs/` 其它文件
- 不执行任何 mvn / npm 构建
- 不创建 / 删除 worktree（试点阶段另行执行）

## 验收标准

- [ ] 7 个文件按 design.md 落地
- [ ] `worktree.ps1` PowerShell 语法解析通过，编码为 UTF-8 with BOM
- [ ] `dispatch-template.md` 必填字段含 worktreeRoot / mainRoot / forbidGitMvn
- [ ] 未触碰任何产品代码

## 测试要求

- PowerShell 语法检查（Parser）
- `rg` 复核新增字段与规则关键词

## 输出要求

编码完成后输出 Change Report 并落盘 `.ai/docs/20260817-worktree-isolation/changereport.md`（修改文件清单 / 与验收标准对照 / 测试结果 / 风险）。
