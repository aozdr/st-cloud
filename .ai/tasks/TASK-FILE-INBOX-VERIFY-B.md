# TASK-FILE-INBOX-VERIFY-B（文件收件箱端到端验证 B）

## 元信息

- Task ID: `TASK-FILE-INBOX-VERIFY-B`
- 关联 State: 无（仅读取白名单知识文档）
- 归属 Agent: frontend-engineer（演练角色）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 模式: verify（只读，禁止修改任何业务代码）

## 目标

验证文件收件箱投递的完整执行链路：

1. 从收件箱读到 dispatchId 后，只读取 scope.include 白名单内的文件。
2. 在 `.ai/docs/20260814-file-inbox-dispatch/` 下写 `changereport-verify-b.md`，包含：dispatchId、读取的文件清单、State Delta 摘要、风险。
3. 返回完整 State Delta（背景/输入/分析/决策/State Delta/风险/下一步/变更影响）。

## 范围（只读）

- include（允许读取）：
  - `.ai/knowledge/frontend.md`
  - `.ai/knowledge/ui-design-system.md`
  - `.ai/templates/ui-design-template.md`
  - `.ai/knowledge/role-context.md`（executor 职责要点）
  - `.ai/docs/20260814-file-inbox-dispatch/`（仅写 changereport-verify-b.md）
- exclude（禁止读取/修改）：
  - `st-web/**`、`st-desktop/**`、`st-sync/**`、`st-core/**`、`st-team/**`、`st-common/**`、`st-search/**`
  - 业务代码、数据库脚本、接口契约

## 验收标准

- 回复首句包含角色与任务类型声明：我是 frontend-engineer，任务类型 verify，开始执行 <objective>。
- `changereport-verify-b.md` 存在且包含本任务的 dispatchId。
- 未读取白名单外目录；未创建子 Agent；未修改业务代码。
- 返回完整 State Delta。

## 验证

- 由 Workflow Manager 校验 changereport 内容、dispatchId 匹配、`list_agents` 无后代 Agent。
