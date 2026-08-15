# TASK-FE-ROLE（角色管理权限点校验 — executor/implement，前端）

## 元信息

- Task ID: `TASK-FE-ROLE`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（设计见 `.ai/docs/20260814-permission-ui/design.md`）

## 目标

校验并微调 `st-web/src/components/team/RoleManageDialog.tsx`（已支持 9 权限点勾选）：

1. 9 权限点（view/download/upload/delete/rename/move/share/manage_members/manage_settings）完整；勾选 upload/download 自动补 view（与后端隐含规则一致）。
2. 新建默认值与编辑回填正确（从 `role.permissions` JSON 解析）。
3. 保存提交 `permissions` JSON 不变形；无其它行为回归。

## 范围

- include（写）：`st-web/src/components/team/RoleManageDialog.tsx`
- include（读）：design.md、`.ai/dispatch/**`
- exclude：其它前端文件、后端代码、`types/index.ts`（勿动）、创建子 Agent

## 验收标准

- 9 权限点齐全 + upload/download 隐含 view；新建/编辑回填/保存正确
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一跑 tsc；抽查组件
