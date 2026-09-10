# Change Report — TASK-FIX-TEAMSPACE-UI（团队空间页面修复）

- Task ID: `TASK-FIX-TEAMSPACE-UI`
- taskCode: `TSUI-01`
- Agent: executor（taskType=implement）
- 日期: 2026-08-14

## 背景

`TeamSpacePage.tsx` 存在功能缺失与布局问题：4 个对话框（权限/角色/统计/评论）的 state 与按钮已存在但 JSX 未挂载，导致点击无反应；权限入口仅按 `space?.ownerId` 显示且文案写死"空间根目录"；顶部按钮行窄屏易换行压缩文件区。

## 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `st-web/src/pages/TeamSpacePage.tsx` | ① 挂载 FolderPermissionDialog / RoleManageDialog / StatsPanel（外层弹窗容器）/ CommentPanel；② 权限入口改为"空间拥有者或管理员"可见，文案按当前节点显示；③ 挂载时拉取成员列表用于管理员识别；④ 顶部按钮行加 `flex-wrap justify-end` |
| `st-web/src/components/file/FileDetailPanel.tsx` | 根容器 `aside` 增加 `h-full`，占满右侧详情区域高度 |

## 与验收标准对照

| 验收标准 | 结果 |
|----------|------|
| 权限/角色/统计/评论四个按钮点击后弹窗正常打开关闭 | ✅ 已挂载；StatsPanel 因组件自身无 onClose，按现有 `bg-black/50` 遮罩+居中卡片模式包一层弹窗容器并补齐关闭按钮 |
| 权限入口按当前节点显示且非 owner 管理员可见 | ✅ 文案：`parentId` 有值时显示"当前文件夹权限"，否则"空间根目录权限"；可见性 = 拥有者（`space.ownerId === currentUserId`）或成员 `role===0`（挂载时拉取成员列表判定）；后端 `checkPermission(spaceId,0)` 保持为最终闸门 |
| 文件区/详情占满可用高度（按钮行 wrap，窄屏不压缩） | ✅ 顶部按钮行加 `flex-wrap justify-end`；FileDetailPanel 根容器加 `h-full` |
| `npx tsc --noEmit` 通过（主线程统一验证） | ✅ 本机静态自检 `tsc --noEmit -p tsconfig.app.json` exit 0；最终构建由主线程统一执行 |

## 实现细节说明

- `CommentPanel` 的 `canComment` 传 `true`：后端 `addComment` 以 `checkPermission(spaceId, 2)` 为闸门（成员即可评论），前端不重复收窄。
- 权限弹窗节点名称：根目录用空间名（兜底"空间根目录"），子文件夹用面包屑末级名称（兜底"当前文件夹"），供 `FolderPermissionDialog` 标题展示。
- 未越界修改：后端代码、其它页面未动；未创建子 Agent（`forbidSpawn: true`）。

## 测试结果

- `npx tsc --noEmit -p tsconfig.app.json`：通过（exit 0）。
- 未运行完整 `npm run build` / 浏览器验证：按派发约定由主线程统一执行 `tsc/build` 验证。

## 风险

- 管理员识别依赖成员列表（分页 size=50、按 role 排序）；成员超过 50 人且当前用户不在首屏时，非 owner 管理员可能不显示权限入口——后端仍会拒绝无权限请求，属前端启发式兜底。
- 挂载时新增一次 `/team/{spaceId}/members` 请求，代价极小。

## 下一步

主线程执行集成验证（`npm run build` / `tsc`）后进入 Code Review 环节。

## 变更影响

- 仅影响 `st-web` 团队空间页面与文件详情面板；无接口契约、数据库或后端变更。
- 权限入口可见性逻辑与后端 `checkPermission(spaceId,0)` 权限模型保持一致。
