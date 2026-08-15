# TASK-FIX-TEAMSPACE-UI（团队空间页面修复：缺失对话框渲染 + 权限入口 + 布局 — executor/implement）

## 元信息

- Task ID: `TASK-FIX-TEAMSPACE-UI`
- taskCode: `TSUI-01`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 背景（诊断结论）

`st-web/src/pages/TeamSpacePage.tsx` 存在功能缺失与布局问题：

1. **4 个对话框未渲染**：`permissionNode`（FolderPermissionDialog）、`showRoleManage`（RoleManageDialog）、`showStats`（StatsPanel）、`commentNode`（CommentPanel）的 state 与按钮均存在，但 JSX 未挂载对应组件——导致顶部"权限/角色/统计/评论"按钮点击无反应（用户反馈"只有锁定文件有用"、"权限配置看不到"）。
2. **权限入口局限**：权限按钮仅 `{space?.ownerId && ...}` 显示且文案写死"空间根目录"，未按当前节点展示，成员/管理员场景不可见。
3. **布局**：顶部按钮行窄屏易换行压缩文件区；FileDetailPanel 右侧详情面板需确认占满可用高度（根容器应为 h-full）。

## 修复清单（定版）

1. **补渲染 4 个对话框**（TeamSpacePage 末尾 `</div>` 前）：
   - `permissionNode && <FolderPermissionDialog spaceId={spaceId} node={permissionNode} onClose={() => setPermissionNode(null)} />`
   - `showRoleManage && <RoleManageDialog spaceId={spaceId} onClose={() => setShowRoleManage(false)} />`
   - `showStats && <StatsPanel spaceId={spaceId} onClose={() => setShowStats(false)} />`
   - `commentNode && <CommentPanel spaceId={spaceId} node={commentNode} onClose={() => setCommentNode(null)} />`（按各组件实际 props 适配）
2. **权限入口**：按钮文案按当前节点（`parentId` 有值时显示"当前文件夹权限"，否则"空间根目录权限"）；显示条件放宽为"空间拥有者或管理员"（如 space.ownerId 或成员 role===0 可判断；保持后端 checkPermission(spaceId,0) 为最终闸门）。
3. **布局**：顶部按钮行加 `flex-wrap`；确认 `FileDetailPanel` 根容器 `h-full`（占满右侧）。

## 范围

- include（写）：`st-web/src/pages/TeamSpacePage.tsx`、`st-web/src/components/file/FileDetailPanel.tsx`（如需要）
- include（读）：各对话框组件 props（FolderPermissionDialog/RoleManageDialog/StatsPanel/CommentPanel）、`.ai/dispatch/**`
- exclude：后端代码、其它页面、创建子 Agent

## 验收标准

- 权限/角色/统计/评论四个按钮点击后弹窗正常打开关闭
- 权限入口按当前节点显示且非 owner 管理员可见
- 文件区/详情占满可用高度（按钮行 wrap，窄屏不压缩）
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build；抽查组件挂载与布局
