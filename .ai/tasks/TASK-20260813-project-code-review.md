# TASK：项目级 Code Review（2026-08-13）

> Review 专用任务，仅只读审查，不修改任何代码。

## 元信息

- Task ID: `TASK-20260813-project-code-review`
- 关联任务 State: `.ai/state/20260813-project-code-review.yaml`
- 关联文档: `.ai/docs/20260813-code-review-fix/`、`.ai/docs/20260813-block-sync/`、`.ai/docs/20260812-schema-versioning/`、`.ai/docs/20260812-sync-full-reconcile/`、`.ai/docs/20260813-upload-rate-throttle/`
- 归属 Agent: reviewer
- 创建者: workflow-manager
- 日期: 2026-08-13

## 目标

对当前工作区自 HEAD（213136c）起的全部未提交变更执行两轴 Code Review（标准符合度 + 需求符合度），输出带文件:行与等级的问题清单，并给出通过/不通过结论。

## 审查范围（只读）

- 已跟踪修改：`git diff HEAD`（85 个文件，含删除项，如 st-sync BlockSyncService 系列）
- 未跟踪源码：`git status --porcelain` 中 `??` 项（排除 `*.zip`、`.codex/`、`.ai/` 基础设施文件）
- 规格依据：近期迭代文档（见关联文档），核心为 design.md / requirement.md / testcases.md / uispec.md
- 标准依据：`.ai/knowledge/conventions.md`、`architecture.md`、`frontend.md`、`testing.md`、`AGENTS.md`

## 禁止修改范围

- 禁止修改/删除任何源码与文档（只读审查）
- 不运行 mvn / npm / tsc 构建（由 TEST_PASS 阶段负责）

## 验收标准

- 问题清单含编号 / 文件:行 / 等级（Critical / Major / Minor / Suggestion）/ 建议
- 区分硬性违规（违反文档化标准）与判断项（代码坏味道基线）
- 两轴（标准 / 需求）结论清晰，含必须修改项

## 输出要求

子 Agent 不写文件，审查结果在回复中完整返回（含 Agent 输出规范各节）；由 Workflow Manager 汇总后落盘 `.ai/docs/20260813-project-code-review/codereview.md`。
