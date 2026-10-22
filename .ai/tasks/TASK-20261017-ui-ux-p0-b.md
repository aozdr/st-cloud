# TASK：TASK-20261017-ui-ux-p0-b 弹窗与键盘可访问性

> 依据：`.ai/docs/20261017-ui-ux-p0-b/design.md`。
> 本轮是 P0-A 之后的第二个切片，只做 P0-B；P1 文件页重构不在本轮。

## 元信息

- Task ID：`20261017-ui-ux-p0-b`
- 关联 State：`.ai/state/20261017-ui-ux-p0-b.yaml`
- 归属 Agent：executor（taskType=implement）
- 创建者：workflow-manager
- 日期：2026-10-17

## 目标

在不改变业务/API/权限语义的前提下，补齐弹窗与文件操作的键盘可访问性：

1. 新增统一的 modal a11y hook：focus trap、Escape、body 滚动锁、打开/关闭焦点还原；
2. 将现有 `modal-overlay` 弹窗接入 hook，并补齐 `role="dialog"` / `aria-modal` / 可访问名称；
3. PreviewModal 增加 dialog 语义与焦点管理；
4. 用 `useConfirm` 替换 6 处原生 `confirm()`；
5. ContextMenu 增加 `role="menu"`、方向键/Home/End/Escape 与焦点管理。

## 修改范围（include）

- `st-web/src/components/ui/useModalA11y.ts`（新增）
- `st-web/src/components/ui/Dialog.tsx`
- `st-web/src/components/ui/ConfirmDialog.tsx`
- `st-web/src/components/ui/PromptDialog.tsx`
- `st-web/src/components/ui/ShortcutHelpDialog.tsx`
- `st-web/src/components/SettingsDialog.tsx`
- `st-web/src/components/file/Dialogs.tsx`
- `st-web/src/components/file/MoveDialog.tsx`
- `st-web/src/components/file/ConvertDialog.tsx`
- `st-web/src/components/file/VersionHistoryDialog.tsx`
- `st-web/src/components/TransferSettingsDialog.tsx`
- `st-web/src/pages/SyncPage.tsx`（仅两个 modal）
- `st-web/src/components/preview/PreviewModal.tsx`
- `st-web/src/components/file/ContextMenu.tsx`
- `st-web/src/components/team/CommentPanel.tsx`
- `st-web/src/components/team/RoleManageDialog.tsx`
- `st-web/src/pages/ShareManagePage.tsx`
- `st-web/src/pages/TeamSpacePage.tsx`
- 任务文档

## 禁止修改范围（exclude）

- 不改后端/数据库/桌面端/上传下载/同步协议。
- 不重构 P1 文件页信息架构、筛选器、表格 ARIA。
- 不删除现有弹窗业务内容；不改 API 调用。
- 不新增第三方依赖。

## 验收标准

- [ ] 新增 `useModalA11y`，并被所有本轮列出的弹窗使用。
- [ ] 本轮列出的弹窗面板有 `role="dialog"` 与 `aria-modal="true"`；有可访问名称。
- [ ] Tab/Shift+Tab 在弹窗内循环；Escape 关闭；关闭后焦点回到触发元素；打开时背景不可滚动。
- [ ] PreviewModal 具备 dialog 语义与焦点管理。
- [ ] `confirm(` 原生调用在 `st-web/src` 中为 0（`useConfirm`/`confirm` 变量除外）。
- [ ] ContextMenu 支持 ↑/↓/Home/End/Escape，具备 `role="menu"`/`menuitem`。
- [ ] `npm run lint`、`npm run build` 通过。
- [ ] `git diff --check` 通过；无后端/桌面端改动。

## 测试要求

- `npm run lint`、`npm run build`。
- 静态扫描：`confirm(`、`modal-overlay`、`role="dialog"`、`useModalA11y`。
- 手工/运行（若环境允许）：键盘 Tab/Escape、弹窗打开后焦点、背景滚动、ContextMenu 方向键。
- 无浏览器/后端时在 verification.md 记录限制，不声称已完成视觉验收。

## 输出

`.ai/docs/20261017-ui-ux-p0-b/verification.md`。
