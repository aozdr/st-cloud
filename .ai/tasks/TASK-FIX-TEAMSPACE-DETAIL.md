# TASK-FIX-TEAMSPACE-DETAIL（详情放大为页面级视图 + 权限 tab — executor/implement）

## 元信息

- Task ID: `TASK-FIX-TEAMSPACE-DETAIL`
- taskCode: `TSUI-02`
- 归属 Agent: executor（taskType=implement）
- 创建者: workflow-manager
- 日期: 2026-08-14

## 目标（已定版）

1. **详情放大为页面级视图**：当前 `FileDetailPanel` 是 FileBrowser 内部 `w-80` 右侧边栏（被文件区域挤压）；改为在 `TeamSpacePage` 文件区（`flex-1` 区域）渲染**占满整个内容区的详情视图**（像"容量"大面板一样），不再挤在文件列表旁。
2. **详情视图含两个 tab**：`详情 | 权限`：
   - **详情 tab**：现有 FileDetailPanel 的内容放大为全宽/全高展示（文件信息、图标、元数据等）。
   - **权限 tab**：当前文件/文件夹的权限配置（复用 FolderPermissionDialog 的规则列表 + 9 权限点勾选逻辑，`folderNodeId = 当前节点 id`，文件与文件夹均可配置）。
3. **保留现状**：顶部"权限"按钮 + FolderPermissionDialog 弹窗做法不变（弹窗与详情权限 tab 并存）。

## 实现要点（定版）

- `FileBrowser`：移除内部 `detailFile`/`FileDetailPanel` 渲染，新增 `onOpenDetail?: (node: FileNode) => void` 回调（"详情"操作调用）；详情由 TeamSpacePage 统一管理。
- `TeamSpacePage`：新增 `detailFile` state + `detailTab: 'info' | 'permission'`；文件区：
  - `detailFile` 为空 → 渲染 FileBrowser（onOpenDetail 打开详情）；
  - `detailFile` 非空 → 渲染详情视图占满 `flex-1 min-h-0 overflow-hidden`：顶栏（文件名/关闭/两个 tab）+ 内容区（info = 放大 FileDetailPanel；permission = 权限配置面板）。
- `FileDetailPanel`：改为占满容器（`h-full w-full`），供详情视图使用（若被其它页面以边栏方式引用，保持兼容或同步调整）。
- 权限 tab 面板：抽 FolderPermissionDialog 的核心为可复用（`variant="panel"` 或抽公共组件），展示/保存当前节点权限规则（GET/PUT `/team/{spaceId}/folder/{nodeId}/permissions`），并保留主体 all/member/role + 9 权限点勾选、upload/download 隐含 view、保存归一化。

## 范围

- include（写）：`st-web/src/pages/TeamSpacePage.tsx`、`st-web/src/components/file/FileBrowser.tsx`、`st-web/src/components/file/FileDetailPanel.tsx`、`st-web/src/components/team/FolderPermissionDialog.tsx`（抽公共/支持面板模式）
- include（读）：各组件 props、`.ai/dispatch/**`
- exclude：后端代码、其它页面、创建子 Agent

## 验收标准

- 详情视图占满文件区（不再被文件列表/侧栏挤压）；"详情/权限"两 tab 可切换
- 权限 tab 可配置当前文件/文件夹权限并保存回显（all/member/role + 9 权限点）
- FileBrowser 列表/详情操作正常；顶部权限弹窗仍可用
- `npx tsc --noEmit` 通过（主线程统一验证）

## 验证

- 主线程统一 tsc/build；抽查结构
