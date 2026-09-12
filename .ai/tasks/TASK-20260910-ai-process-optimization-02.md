# TASK：R1 双轴 Code Review（Standards / Spec）

> 评审任务。执行者 = reviewer（taskType=review）。**独立执行者要求**：不得由 R1 的实现者承担（角色分离，见 `.ai/loop/exit-criteria.yaml` 的 `separationOfDuty`）。

## 元信息

- Task ID: `TASK-20260910-ai-process-optimization-02`
- 关联任务 State: `.ai/state/20260910-ai-process-optimization.yaml`
- 关联文档: `.ai/docs/20260910-ai-process-optimization/optimization-plan.md`
- 被评审的实现 TASK: `.ai/tasks/TASK-20260910-ai-process-optimization-01.md`
- 归属 Agent: reviewer（taskType=review）
- 日期: 2026-09-10

## 目标

对 R1 的全部改动做独立评审，回答两个问题：**是否遵守本仓库成文规范**（Standards）、**是否忠实实现了既定方案**（Spec）。输出 PASS / BLOCK 结论与可复现的问题清单。

## 评审基准（固定点）

R1 改动**尚未提交**，因此固定点 = `HEAD`，被评审对象 = 工作区改动：

- `git status --short`（33 项，全部在 `.ai/` 内）
- `git diff HEAD`（已跟踪文件的改动）
- 未跟踪新文件：`.ai/loop/exit-criteria.yaml`、`.ai/githooks/pre-commit`、`.ai/scripts/install-hooks.ps1`、`.ai/docs/20260910-ai-process-optimization/optimization-plan.md`、`.ai/tasks/TASK-20260910-ai-process-optimization-01.md`、`.ai/state/20260910-ai-process-optimization.yaml`

## 标准来源（Standards 轴）

- `AGENTS.md`：代码修改强制约束 8 条（尤其第 1/2/4/6/7 条）、数据库版本管理章节
- `.ai/knowledge/document-management.md`：编码规范（`.md`/`.yaml` UTF-8 **无 BOM**；含非 ASCII 的 `.ps1` 用 **UTF-8 with BOM**）、模板对应关系
- `.ai/knowledge/loop-state-model.md`：State 结构、状态取值、回填记录格式
- `.ai/knowledge/loop-verification-checklist.md`：验证项与判定标准
- `.ai/knowledge/conventions.md`：工程约定
- 对脚本类改动（`verify-loop.ps1` / `install-hooks.ps1` / `pre-commit`）另加 Fowler 气味基线（见技能 `C:\Users\Administrator\.agents\skills\code-review\SKILL.md` 第 3 步原文），且**仓库成文规范优先于气味基线**；工具已强制的不报。

## Spec 来源（Spec 轴）

- `.ai/docs/20260910-ai-process-optimization/optimization-plan.md`：决策 D1–D20、R1 范围与验收
- `.ai/tasks/TASK-20260910-ai-process-optimization-01.md`：R1 验收标准与禁止范围

## 必查项（对抗性，须给结论）

1. **口径一致是否真成立**：定义文件与三份文档的项数/终态是否一致；校验脚本是否可能把不一致判成 PASS（例如短语匹配过宽）。
2. **State 诚信校验的假阴性**：试构造以下情形并给结论——流式与块式两种写法、顶层缺 `status`、`exitCriteria: []`、`ref` 指向 `.ai/` 之外的路径、`status: done` 但产物 `pending`、产物 `missing` 与 `pending` 的区分是否会被绕过。
3. **回填的诚实性**：8 份 State 的状态改写是否有留痕（`backfill`）、是否与产物实际存在性一致；是否存在「用回填掩盖真实完成度」的情形。
4. **编码规范**：新增/修改的 `.ps1` 是否 UTF-8 with BOM、`.md`/`.yaml` 是否无 BOM；有无 GBK 混入。
5. **门禁强度与副作用**：`core.hooksPath` 方案是否会误伤无关提交、是否可被静默绕过、钩子失败时是否有清晰提示；是否存在权限外溢。
6. **脚本健壮性**：重复执行是否幂等；异常路径（找不到定义文件、State 目录为空、YAML 写法异常）是否给出明确 FAIL 而非静默通过。
7. **范围合规**：改动是否越出 `.ai/**`（业务代码零改动是否属实）。

## 验证命令

```powershell
# 正向：应退出码 0
powershell -ExecutionPolicy Bypass -File .ai\scripts\verify-loop.ps1

# 反向：复制 .ai 到临时目录、删除任一已标 done 的产物，应 FAIL（不得改动仓库原文件）
```

## 禁止修改范围

- 除输出文件 `.ai/docs/20260910-ai-process-optimization/codereview.md` 外，**不得修改任何文件**（评审只读）
- 禁止 `git add` / `git commit` / `git checkout` / 任何 git 写操作
- 禁止修改 `st-*/**` 业务代码
- 禁止创建子代理（由本线程顺序完成两轴）

## 验收标准

- [ ] 输出 `codereview.md`，含 `## Standards` 与 `## Spec` 两节，两轴分别列出发现（引用规范原文或 spec 原文）
- [ ] 给出总体结论 PASS 或 BLOCK；BLOCK 项附可复现步骤
- [ ] 「必查项」7 条逐条给出结论（通过 / 不通过 / 不适用 + 依据）
- [ ] 区分硬违规（成文规范违反）与判断项（气味基线）

## 输出要求

1. 落盘 `.ai/docs/20260910-ai-process-optimization/codereview.md`
2. 回复主线程：结论、BLOCK 清单（若有）、文件路径
