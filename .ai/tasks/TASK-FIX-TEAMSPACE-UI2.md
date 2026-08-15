# TASK-FIX-TEAMSPACE-UI2（团队空间页 4 项改进 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-TEAMSPACE-UI2`
- taskCode: `TSUI-04`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（用户需求，已定版）

1. **锁定/解锁移入文件右键菜单**：顶部锁定/解锁按钮移除；在团队文件右键菜单（FileBrowser/ContextMenu）按节点锁定状态展示"锁定"或"解锁"其中一个；操作调 `POST /team/{spaceId}/files/{nodeId}/lock|unlock`。
2. **删除评论功能**：移除顶部评论按钮、`commentNode` state 与 `CommentPanel` 渲染/引用（组件文件可保留，页面不再使用）。
3. **"文件/活动"双 tab 改为单按钮**：删除顶部 tab 行，改为一个切换按钮（放原锁定按钮位置）在"文件视图/活动视图"间切换。
4. **详情不被文件操作按钮挤压**：打开详情侧边栏时，顶部操作按钮区与详情共存不挤压（布局调整：操作区可换行/详情侧边栏全高已实现，确保无重叠压缩）。

## 范围

- include（写）：`st-web/src/pages/TeamSpacePage.tsx`、`st-web/src/components/file/FileBrowser.tsx`、`st-web/src/components/file/ContextMenu.tsx`（右键菜单加锁定/解锁）
- include（读）：现有锁定/解锁逻辑、右键菜单结构、`.ai/dispatch/**`
- exclude：后端代码、其它页面、FolderPermissionDialog/RoleManageDialog、创建子 Agent

## 验收标准

- 右键菜单按锁定状态显示锁定/解锁其一；顶部无锁定/解锁按钮
- 顶部无评论按钮；无 CommentPanel 渲染
- 无 tab 行，切换按钮在锁定按钮位置；文件/活动视图可切换
- 详情打开不挤压操作区
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build
