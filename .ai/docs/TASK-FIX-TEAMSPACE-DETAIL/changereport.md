# Change Report — TASK-FIX-TEAMSPACE-DETAIL（详情放大为页面级视图 + 权限 tab）

- Task ID: `TASK-FIX-TEAMSPACE-DETAIL`
- taskCode: `TSUI-02`
- Agent: executor（taskType=implement）
- 日期: 2026-08-14

## 背景

团队空间文件区的"详情"原先渲染在 FileBrowser 内部 `w-80` 右侧边栏，被文件列表挤压，信息展示空间不足；且节点权限只能通过顶部按钮弹窗配置。本任务将详情放大为页面级视图（占满整个文件区），并在详情内提供「详情 / 权限」两个 tab，权限 tab 可直接配置当前文件/文件夹的权限规则。

## 输入

- 派发信封 `inbox-tsui-02.md`（dispatchId: `tsui-02`）
- TASK 文件 `.ai/tasks/TASK-FIX-TEAMSPACE-DETAIL.md`
- 读入：`TeamSpacePage.tsx`、`FileBrowser.tsx`、`FileDetailPanel.tsx`、`FolderPermissionDialog.tsx`、`ContextMenu.tsx`、`FileThumbnail.tsx`、`types/index.ts`、`lib/permissions.ts`，以及 FileBrowser 全部调用方（FileManager / CategoryPage / FavoritesPage）
- 技能：`frontend-design-ui-ux`、`vercel-react-best-practices`、`design-guide`、`vercel-composition-patterns`（skillRefs + 自主发现按前端技术栈补齐）

## 分析

- `FileBrowser` 的 `handleContextAction` 中 `details` 分支当前直接 `setDetailFile(node)`，详情面板内嵌于列表右侧；需改为将"详情"上抛给页面（`onOpenDetail`）。
- `FileBrowser` 还被 FileManager（双面板）、CategoryPage、FavoritesPage 使用，均未传详情回调。若直接删除内部渲染，这些页面"详情"菜单将失效且不在本次写范围；故保留未传 `onOpenDetail` 时的内部边栏回退，确保零回归（对应 TASK 中"若被其它页面以边栏方式引用，保持兼容"）。
- `FolderPermissionDialog` 当前为固定遮罩弹窗；其规则列表 + all/member/role 主体 + 9 权限点勾选 + upload/download 隐含 view + 保存归一化逻辑可直接复用，通过 `variant: 'dialog' | 'panel'` 抽出面板模式，避免重复实现。
- 详情视图由 `TeamSpacePage` 统一管理：`detailFile` 非空时文件区整体切换为详情视图（顶栏 + tab），文件列表保持挂载（仅隐藏），关闭详情后无缝恢复列表状态。

## 决策

1. **FileBrowser**：新增可选回调 `onOpenDetail?: (node: FileNode) => void`；`details` 动作优先调用回调，未提供时回退内部边栏（`FileDetailPanel` 仅在 `!onOpenDetail` 时渲染）。
2. **TeamSpacePage**：新增 `detailFile` + `detailTab: 'info' | 'permission'`；文件区渲染：详情打开 → 占满 `flex-1 min-h-0` 的详情视图（顶栏：节点缩略图/名称/「详情」「权限」tab/关闭），`info` = `FileDetailPanel variant="panel"`，`permission` = `FolderPermissionDialog variant="panel"`；详情关闭 → FileBrowser（传 `onOpenDetail`）。
3. **FileDetailPanel**：新增 `variant: 'sidebar' | 'panel'`（默认 sidebar 保持原边栏行为）；panel 模式 `w-full` 占满容器、无自身头部（由详情视图顶栏接管）、内容限宽居中放大展示。
4. **FolderPermissionDialog**：新增 `variant: 'dialog' | 'panel'`；将内容主体（标题/规则列表/添加规则/保存栏）提取为共用 `content`，弹窗模式保持原遮罩居中行为，panel 模式占满容器内部滚动；保存后 panel 模式停留在当前 tab 回显，dialog 模式照旧关闭；标题由"文件夹权限："泛化为"权限："以同时适配文件节点。

## 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `st-web/src/pages/TeamSpacePage.tsx` | 新增 `detailFile`/`detailTab` 状态与 `handleOpenDetail`；文件区改为"详情视图 ↔ FileBrowser"二态渲染，详情视图含节点信息顶栏 + 详情/权限 tab + 关闭；FileBrowser 传 `onOpenDetail` |
| `st-web/src/components/file/FileBrowser.tsx` | 新增 `onOpenDetail` 可选回调；`details` 动作优先调用回调、未提供时回退内部边栏；内部 `FileDetailPanel` 仅在无回调时渲染 |
| `st-web/src/components/file/FileDetailPanel.tsx` | 新增 `variant`（sidebar/panel）；panel 模式 `w-full` 占满容器、无头部、内容放大并限宽居中 |
| `st-web/src/components/team/FolderPermissionDialog.tsx` | 新增 `variant`（dialog/panel）；内容主体抽为共用结构；panel 模式占满容器内部滚动、保存后停留回显；标题泛化为"权限：" |

## 与验收标准对照

| 验收标准 | 结果 |
|----------|------|
| 详情视图占满文件区（不再被文件列表/侧栏挤压） | ✅ 详情打开时文件区整体切换为 `h-full` 详情视图，占满 `flex-1 min-h-0` |
| "详情/权限"两 tab 可切换 | ✅ 顶栏两个 tab 切换 `detailTab`，info 渲染放大详情、permission 渲染权限面板 |
| 权限 tab 可配置当前文件/文件夹权限并保存回显（all/member/role + 9 权限点） | ✅ 复用 FolderPermissionDialog 规则列表与保存逻辑（GET/PUT `/team/{spaceId}/folder/{nodeId}/permissions`，`nodeId=当前节点 id`），panel 保存后停留并回显 |
| FileBrowser 列表/详情操作正常；顶部权限弹窗仍可用 | ✅ TeamSpacePage 详情经 `onOpenDetail` 打开；其它页面未传回调时保留原内部边栏行为；顶部权限按钮 + 弹窗（dialog variant）未改动 |
| `npx tsc --noEmit` 通过（主线程统一验证） | ✅ 本机静态自检 `tsc --noEmit -p tsconfig.app.json` exit 0；最终构建由主线程统一执行 |

## 实现细节说明

- 详情打开时 FileBrowser 保持挂载（`hidden` 隐藏），关闭详情后列表滚动/选中/已加载数据无缝恢复，避免整页重挂载闪烁。
- 权限 tab 对文件与文件夹均可用（`folderNodeId = 当前节点 id`），与后端 FolderPermissionService 现有语义一致；后端不在本次范围。
- 未越界修改：仅改范围内 4 个前端文件；后端代码、FileManager/CategoryPage/FavoritesPage 等其它页面未动；未创建子 Agent（`forbidSpawn: true`）。

## 测试结果

- `npx tsc --noEmit -p tsconfig.app.json`：通过（exit 0）。
- 未运行完整 `npm run build` / 浏览器验证：按派发约定由主线程统一执行 `tsc/build` 验证。

## State Delta

- artifacts: `.ai/docs/TASK-FIX-TEAMSPACE-DETAIL/changereport.md`；代码修改见"修改文件清单"。
- exitCriteria（对照 TASK-FIX-TEAMSPACE-DETAIL 验收）：详情占满文件区 ✅ / 详情+权限 tab ✅ / 权限配置可保存回显 ✅ / FileBrowser 操作与顶部弹窗保留 ✅ / tsc 静态自检 ✅（最终验证待主线程统一执行）。
- blockers: 无新增。

## 风险

- 权限 tab 依赖后端 `/team/{spaceId}/folder/{nodeId}/permissions` 对文件节点（nodeType=1）的支持；若后端仅接受文件夹节点，文件权限保存会失败——属后端能力边界，不在本次前端范围，建议主线程在联调时核验。
- 详情视图顶栏 tab 样式与页内「文件/动态」tab 视觉一致（复用同一套按钮样式），未引入新设计 token。
- FileBrowser 回退分支保留内部边栏：若后续其它页面需要页面级详情，可各自传 `onOpenDetail`，届时可再移除回退分支。

## 下一步

主线程执行集成验证（`npm run build` / `tsc`）后进入 Code Review 环节。

## 变更影响

- 仅影响 `st-web` 团队空间页面、FileBrowser/FileDetailPanel/FolderPermissionDialog 四个组件；无接口契约、数据库或后端变更。
- FileDetailPanel 与 FolderPermissionDialog 均为向后兼容（新增可选 prop，默认行为不变）。
