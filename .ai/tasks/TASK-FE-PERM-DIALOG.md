# TASK-FE-PERM-DIALOG（文件夹权限配置 UI 对齐 — executor/implement，前端）

## 元信息

- Task ID: `TASK-FE-PERM-DIALOG`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 迭代: 20260814-permission-ui（设计见 `.ai/docs/20260814-permission-ui/design.md`）

## 目标

改造 `st-web/src/components/team/FolderPermissionDialog.tsx`，对齐权限模型：

1. 主体选择：`all`（全体成员，管理员除外）/ `member`（搜索用户）/ `role`（角色下拉，含自定义角色）。
2. 权限：由单值下拉改为 **9 权限点勾选**（view/upload/download/delete/rename/move/share/manage_members/manage_settings），映射 `permissions` JSON；勾选 upload/download 自动补 view（提示）。
3. 规则列表：新增规则带 `permissions`；保存 `PUT /team/{spaceId}/folder/{nodeId}/permissions` 的 `rules[].permissions`；删除/覆盖沿用现有语义。
4. 回显：加载规则时优先用 `permissions` 集合展示权限点。

## 范围

- include（写）：`st-web/src/components/team/FolderPermissionDialog.tsx`
- include（读）：`.ai/docs/20260814-permission-ui/design.md`、`st-web/src/types/index.ts`（已改）、`.ai/docs/20260814-permission-model/design.md`、`.ai/dispatch/**`
- exclude：其它前端文件、后端代码、`types/index.ts`（已由主线程更新，勿动）、创建子 Agent

## 验收标准

- 主体支持 all/member/role；9 权限点勾选；保存 payload 带 permissions
- upload/download 勾选自动补 view
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一跑 tsc/build；抽查组件代码
