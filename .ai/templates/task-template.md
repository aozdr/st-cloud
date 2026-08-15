# TASK：<任务简述>

> 开发前置产物。本文件由 Workflow Manager 在进入 IMPLEMENTED 前，依 `.ai/docs/<task-id>/design.md` 与 `testcases.md` 生成并落盘到 `.ai/tasks/`。工程师编码输入**只接受本文件**（小型直接执行除外）。

## 元信息

- Task ID: `TASK-<task-id>-<序号>`
- 关联任务 State: `.ai/state/<task-id>.yaml`
- 关联文档: `.ai/docs/<task-id>/design.md` / `testcases.md`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: YYYY-MM-DD

## 目标

（本任务要达成的客观目标，1-3 句）

## 修改范围

- 模块 / 目录：
- 涉及文件（尽量列全）：
- 涉及接口 / 数据库：
- 前后端联动：

## 禁止修改范围

（明确列出**不得触碰**的文件、模块、接口、配置，防止越界修改）

## 验收标准

- [ ] 标准 1
- [ ] 标准 2
- [ ] （与 design.md / requirement.md 验收标准对齐）

## 测试要求

- 单元 / 集成测试：
- 前端构建 / 后端编译：
- 手工验证点：

## 输出要求

编码完成后输出 Change Report 并落盘 `.ai/docs/<task-id>/changereport.md`（修改文件清单 / 与验收标准对照 / 测试结果 / 风险）。
