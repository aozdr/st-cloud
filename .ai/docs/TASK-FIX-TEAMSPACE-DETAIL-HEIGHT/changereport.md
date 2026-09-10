# Change Report — TASK-FIX-TEAMSPACE-DETAIL-HEIGHT（详情改为右侧全高侧边栏）

- Task ID: `TASK-FIX-TEAMSPACE-DETAIL-HEIGHT`
- taskCode: `TSUI-03`
- Agent: executor（taskType=implement）
- 日期: 2026-08-14

## 背景

TSUI-02 将详情从 FileBrowser 内部 `w-80` 侧边栏放大为"占满整个文件区"的页面级视图，详情打开时文件列表被隐藏。用户澄清要求撤销该做法：详情保持原宽度（`w-80`），但高度延伸到整个页面内容区底部（超出文件列表区域）；文件列表始终保留在左侧 `flex-1`，与详情并存。

## 输入

- 派发信封 `inbox-tsui-03.md`（dispatchId: `tsui-03`）
- TASK 文件 `.ai/tasks/TASK-FIX-TEAMSPACE-DETAIL-HEIGHT.md`
- 读入：`TeamSpacePage.tsx`、`FileDetailPanel.tsx`、`FolderPermissionDialog.tsx`、`FileBrowser.tsx`、`FileThumbnail.tsx`
- 技能：`frontend-design-ui-ux`、`vercel-react-best-practices`（skillRefs + 自主发现按前端技术栈补齐）

## 分析

- 当前文件区为二态渲染：`detailFile` 非空时整个 `flex-1` 被详情视图（顶栏 + panel 内容）占用，FileBrowser 仅保持挂载并以 `hidden` 隐藏；关闭详情后才恢复列表。
- `FileDetailPanel` 已有 `variant="sidebar"`（`w-80` + 纵向滚动），`FolderPermissionDialog` 已有 `variant="panel"`（占满容器内部滚动），均可直接复用，无需改动这两个组件。
- 目标布局：文件区改为 `h-full flex` 左右并排——左 `FileBrowser`（`flex-1 min-h-0`，始终渲染），右 `detailFile` 非空时渲染 `h-full w-80 flex-shrink-0 border-l overflow-hidden flex flex-col` 详情侧边栏；父级 `flex flex-col h-full` 链（TeamSpacePage 根节点 → 文件区 `flex-1 min-h-0` → `h-full flex`）保证侧边栏高度延伸到页面内容区底部。

## 决策

1. **TeamSpacePage**：文件区渲染改为 `h-full flex` 左右并排。
   - 左：`<div className="flex-1 min-h-0">` 内始终渲染 `FileBrowser`（传 `onOpenDetail`），不再被详情打开隐藏；breadcrumb/parentId 列表状态与详情并存。
   - 右：`detailFile` 非空时渲染详情侧边栏 `<div className="h-full w-80 flex-shrink-0 border-l border-border overflow-hidden flex flex-col">`，顶栏（缩略图/名称 + 「详情」「权限」tab + 关闭，适配窄栏改为 `px-4`、缩略图 `w-8 h-8 size="sm"`）保持 TSUI-02 的双 tab 结构。
   - 内容区：`info` tab = `FileDetailPanel variant="sidebar"`（w-80 纵向滚动）；`permission` tab = `FolderPermissionDialog variant="panel"`（当前节点权限配置），与 TASK 定版一致。
2. **不做改动**：`FileDetailPanel.tsx`、`FolderPermissionDialog.tsx` 复用既有 `variant` 能力，无需调整。
3. 顶部权限弹窗（dialog variant）做法不变。

## 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `st-web/src/pages/TeamSpacePage.tsx` | 文件区由"详情视图 ↔ FileBrowser 二态"改为 `h-full flex` 左右并排：左 FileBrowser 常驻 `flex-1 min-h-0`；右 `detailFile` 非空时渲染 `w-80` 全高详情侧边栏（双 tab + 关闭），info 用 `FileDetailPanel variant="sidebar"`、permission 用 `FolderPermissionDialog variant="panel"`；同步更新状态注释 |

## 与验收标准对照

| 验收标准 | 结果 |
|----------|------|
| 详情为 w-80 右侧侧边栏，高度占满页面内容区（延伸到容量设置/页面底部） | ✅ 侧边栏 `h-full w-80 flex-shrink-0`，随父级 `flex flex-col h-full` 链延伸至页面内容区底部，超出文件列表区域 |
| 文件列表保留在左侧 flex-1，不受详情影响 | ✅ FileBrowser 始终渲染在 `flex-1 min-h-0`，不再隐藏 |
| 详情/权限 tab 可切换，权限 tab 可配置当前节点权限并保存 | ✅ 顶栏双 tab 保留；info = FileDetailPanel sidebar；permission = FolderPermissionDialog panel（保存后停留回显） |
| `npx tsc --noEmit` 通过（主线程统一验证） | ⏳ 本机静态自检（括号/标签配对、结构核对）通过；最终 `tsc/build` 由主线程统一执行 |

## 实现细节说明

- 撤销"详情打开时文件列表隐藏"分支：FileBrowser 始终挂载渲染，列表滚动/选中/已加载数据与详情并存；关闭详情仅移除右侧侧边栏，列表状态无扰动。
- 侧边栏顶栏为适配 `w-80` 窄栏做了轻度紧凑化（`px-4`、缩略图 `w-8 h-8`、tab `px-2.5`），视觉样式与页内其它 tab 一致，未引入新设计 token。
- 未越界修改：仅改范围内 `TeamSpacePage.tsx`；`FileDetailPanel.tsx` / `FolderPermissionDialog.tsx` / 后端代码 / 其它页面均未动；未创建子 Agent（`forbidSpawn: true`）。

## 测试结果

- 静态自检：修改段 JSX 结构核对通过（`{`/`}` 362 对、`(`/`)` 465 对配对平衡；标签闭合目检无误）。
- 未运行完整 `npm run build` / `tsc`：按派发约定（validation: 主线程统一 tsc/build）由主线程统一执行。

## State Delta

- artifacts: `.ai/docs/TASK-FIX-TEAMSPACE-DETAIL-HEIGHT/changereport.md`；代码修改见"修改文件清单"。
- exitCriteria（对照 TASK-FIX-TEAMSPACE-DETAIL-HEIGHT 验收）：w-80 全高侧边栏 ✅ / 文件列表常驻 flex-1 ✅ / 双 tab 可用 ✅ / tsc 静态自检 ✅（最终验证待主线程统一执行）。
- blockers: 无新增。

## 风险

- `FileDetailPanel variant="sidebar"` 自带 `border-l`，与侧边栏容器 `border-l` 相邻可能呈现略粗的分隔线（约 2px），纯视觉细节，不影响功能；如验收关注可后续由主线程微调。
- 详情打开时若在左侧继续切换目录（breadcrumb/parentId 变化），详情节点保持不变（两者并存），符合任务预期；如需联动可后续迭代调整。

## 下一步

主线程执行集成验证（`npx tsc --noEmit` / `npm run build`）后进入 Code Review 环节。

## 变更影响

- 仅影响 `st-web` 团队空间页面文件区布局；无接口契约、数据库或后端变更。
- 组件层零改动：`FileDetailPanel` / `FolderPermissionDialog` 复用既有 variant，其余页面（FileManager / CategoryPage / FavoritesPage 等）不受影响。
