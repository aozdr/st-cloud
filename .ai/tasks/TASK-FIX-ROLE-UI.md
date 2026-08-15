# TASK-FIX-ROLE-UI（角色管理 UI 优化 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-ROLE-UI`
- taskCode: `TSUI-06`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（用户需求，已定版）

优化 `RoleManageDialog` 的 UI（当前"很难看"）：布局清晰（角色列表 + 新建/编辑表单分区）、9 权限点勾选分组展示（查看/下载/上传/删除/重命名/移动/分享/管理成员/管理设置，带图标与分组）、新建默认与编辑回填、保存/删除按钮、间距与视觉层次；保留 upload/download 隐含 view 联动与防御式解析。

## 范围

- include（写）：`st-web/src/components/team/RoleManageDialog.tsx`
- include（读）：`.ai/dispatch/**`
- exclude：其它组件/页面、后端、创建子 Agent

## 验收标准

- 角色列表 + 表单布局清晰；9 权限点分组勾选；新建/编辑/保存/删除正常；隐含 view 联动保留
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build
