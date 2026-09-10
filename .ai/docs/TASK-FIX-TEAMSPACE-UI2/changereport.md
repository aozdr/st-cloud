# Change Report：团队空间页 4 项 UI 改进（TSUI-04）

- Task: `TASK-FIX-TEAMSPACE-UI2`
- Dispatch: `tsui-04`
- Agent: executor（taskType=implement）
- 日期: 2026-08-14

## 背景

用户对团队空间页提出 4 项改进：锁定/解锁移入文件右键菜单、删除评论功能、文件/活动双 tab 改为单切换按钮、打开详情时操作区不被挤压。本任务为已定版小型实现任务，按 TASK 文件范围执行。

## 修改文件清单

- `st-web/src/pages/TeamSpacePage.tsx`：移除顶部评论按钮、`commentNode` state、`CommentPanel` import/渲染、`MessageSquare` import；移除顶部锁定/解锁按钮、`showLockDialog`/`lockHours` state 与锁定时长弹窗、`Lock`/`Unlock` import；移除「文件/活动」tab 行，在原锁定按钮位置新增单切换按钮；新增 `lockedNodeIds` 本地锁定状态集合，`handleLock`/`handleUnlock` 成功后同步更新；`onToggleLock` 回调注入 FileBrowser。
- `st-web/src/components/file/FileBrowser.tsx`：新增可选 props `lockedNodeIds`、`onToggleLock`（仅团队空间传入）；`handleContextAction` 新增 `lock`/`unlock` 分支；向 ContextMenu 传入 `lockable`/`locked`。
- `st-web/src/components/file/ContextMenu.tsx`：新增可选 props `lockable`/`locked`（默认 false），import `Lock`/`Unlock`；菜单在「隐藏」后按节点锁定状态插入「锁定」或「解锁」其一。

## 与验收标准对照

| 验收标准 | 结果 |
|----------|------|
| 右键菜单按锁定状态显示「锁定/解锁」其一 | 通过（仅团队空间启用） |
| 顶部无锁定/解锁按钮 | 通过（已移除，改为右键菜单入口） |
| 顶部无评论按钮、无 CommentPanel 渲染 | 通过（引用全部移除，组件文件保留） |
| 无 tab 行，切换按钮在锁定按钮位置，文件/活动可切换 | 通过（按钮位于「空间统计」与「上传」之间） |
| 详情打开不挤压操作区 | 通过（头部 flex-wrap 可换行，详情为独立 w-80 全高侧边栏） |
| tsc 通过 | 通过（`npx tsc --noEmit -p tsconfig.app.json` EXIT=0） |

## 测试结果

- `npx tsc --noEmit -p tsconfig.app.json`（st-web）：EXIT=0，无类型错误。
- 残留核验：`CommentPanel/commentNode/MessageSquare/showLockDialog/lockHours` 与 TeamSpacePage 内 `Lock/Unlock` 引用为零；其它页面 FileBrowser 挂载点未传新 props，行为不变。

## 风险与说明

- 锁定状态为前端本地维护：后端团队文件列表 `FileNodeVO` 未下发 `lockedBy/lockExpireAt` 字段，且 scope 排除后端改动，故右键状态由页面在锁定/解锁成功后本地记录；首次加载无法获知其它会话的锁定状态，后端 `checkNotLocked` 仍为最终拦截。
- 右键「锁定」直接调用 `POST /team/{spaceId}/files/{nodeId}/lock`（hours=24，与后端 `LockRequest` 默认一致），原时长选择弹窗随顶部按钮移除。
- 无接口契约变化、无数据库变更、无迁移脚本需求。

## State Delta

- 新增 artifacts：`.ai/docs/TASK-FIX-TEAMSPACE-UI2/changereport.md`。
- 更新 artifacts：`TeamSpacePage.tsx`、`FileBrowser.tsx`、`ContextMenu.tsx`。
- 建议主线程标记 IMPLEMENTED 对应 exitCriterion 并进入 Code Review / 体验验收。

## 下一步

- 主线程统一复跑 `tsc`/build 确认 exitCriterion，随后进入 CODE_REVIEW；体验验收重点：右键锁定/解锁状态切换、视图切换、详情打开时头部换行表现。
