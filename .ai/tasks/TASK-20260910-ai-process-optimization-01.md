# TASK：R1 — Agent Loop 门禁可执行化（定义一个源 + 校验脚本升级 + State 回填 + 预提交门禁）

> 开发前置产物。本文件由 Workflow Manager 在进入 IMPLEMENTED 前落盘。执行者输入**只接受本文件**。

## 元信息

- Task ID: `TASK-20260910-ai-process-optimization-01`
- 关联任务 State: `.ai/state/20260910-ai-process-optimization.yaml`
- 关联文档: `.ai/docs/20260910-ai-process-optimization/optimization-plan.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-09-10

## 目标

把 `.ai` 流程从「靠文档自律」推进到「有可执行门禁」：建立退出标准的唯一定义文件，让校验脚本用它检查文档口径、State 诚信与产物存在性，回填历史失真记录，并把校验接到提交路径上。

## 修改范围

- 模块 / 目录：`.ai/**`、仓库级 `core.hooksPath`
- 涉及文件：
  - 新增 `.ai/loop/exit-criteria.yaml`、`.ai/githooks/pre-commit`、`.ai/scripts/install-hooks.ps1`、本文件、优化计划文档
  - 重写 `.ai/scripts/verify-loop.ps1`
  - 修改 `.ai/state/*.yaml`（8 份回填）、`.ai/tasks/TASK-20260817-*.md`（10 份标注）
  - 修改 `.ai/knowledge/{loop-state-model,loop-verification-checklist,skill-mapping,conventions,ui-design-system}.md`、`.ai/workflows/feature-development.md`
- 涉及接口 / 数据库：无
- 前后端联动：无

## 禁止修改范围

- `st-*/**`（全部业务代码）、`docker/**`、`st-web/**`、`st-desktop/**`：本轮零业务改动
- `.ai/dispatch/**`：运行时现场，不改写历史认领文件
- 既有 ADR 正文与历史 `changereport.md`：只读

## 验收标准

- [ ] `.ai/scripts/verify-loop.ps1` 在仓库根执行退出码 0（基线由 FAIL 93 转绿）
- [ ] `large/medium/small` 项数在定义文件与三份文档间一致，并由脚本强制
- [ ] 引用不存在产物的 State 已回填 `incomplete`/`abandoned` 且含 `backfill` 记录
- [ ] `core.hooksPath = .ai/githooks`，涉及 `.ai/**` 或 `AGENTS.md` 的提交触发校验
- [ ] 反向验证：删除任一已标 done 的产物后校验必须 FAIL

## 测试要求

- 单元 / 集成测试：无（本次为零业务改动）
- 前端构建 / 后端编译：不适用
- 手工验证点：`verify-loop.ps1` 正/反向各跑一次；钩子空跑一次

## 输出要求

完成后更新 `.ai/docs/20260910-ai-process-optimization/optimization-plan.md` 的「R1 执行记录」章节（修改文件清单 / 与验收标准对照 / 验证结果 / 风险）。
