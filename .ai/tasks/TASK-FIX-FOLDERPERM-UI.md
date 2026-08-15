# TASK-FIX-FOLDERPERM-UI（权限设置搜索修复 + UI 优化 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-FOLDERPERM-UI`
- taskCode: `TSUI-05`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（用户需求，已定版）

1. **人员搜索真实生效**：`FolderPermissionDialog` 当前"输入没有搜索"——修复输入框与 `handleSearch` 的绑定（输入即时触发 `/team/{spaceId}/users/search?keyword=`，建议 300ms debounce；无输入清空结果；结果可点击选中）。
2. **权限设置 UI 优化**：dialog 与 panel 两种形态都优化——布局层次清晰（主体选择、权限点勾选、规则列表）、间距/分组、规则行展示（主体 + 权限点 chips）、保存/删除按钮合理；去掉拥挤感。

## 范围

- include（写）：`st-web/src/components/team/FolderPermissionDialog.tsx`
- include（读）：types（UserSearch）、现有搜索接口、`.ai/dispatch/**`
- exclude：其它页面/组件、后端、创建子 Agent

## 验收标准

- 输入关键词触发真实搜索（debounce），结果可选；清空恢复
- dialog/panel UI 优化（布局、间距、权限点展示、规则列表）
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build
