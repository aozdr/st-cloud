# 验证记录：弹窗与键盘可访问性（P0-B）

> Task：`20261017-ui-ux-p0-b`
> 设计修订：`p0b-design-F5C122DDE006-E0440E18E291`
> 代码修订：`p0b-381ee4b99a53-393B846DC40E`
> 日期：2026-10-17
> 结论：**IMPLEMENTED + VERIFIED（构建 + 真实 Chrome 键盘/视觉走查）**；证据见 §3、§6。

## 1. 修改摘要

| 类别 | 内容 |
|---|---|
| 统一 hook | 新增 `st-web/src/components/ui/useModalA11y.ts`：focus trap、Escape、body 滚动锁（计数支持嵌套）、初始焦点、关闭后焦点还原 |
| 共享 Dialog | `Dialog.tsx` 改用 `createPortal` + hook，删除旧 focus trap/Escape effect |
| 弹窗接入 | `ConfirmDialog`、`PromptDialog`、`SettingsDialog`、`ShortcutHelpDialog`、`Dialogs.tsx` 三个弹窗、`MoveDialog`、`ConvertDialog`、`VersionHistoryDialog`、`TransferSettingsDialog`、`SyncPage` 两个 modal、`PreviewModal`、`ShareDialog`、`SaveShareDialog`、`DownloadDialog`、`ArchiveDialog`、`BatchRenameDialog`、`RoleManageDialog`、`FolderPermissionDialog`、`ActionSheet`、`TeamPage` 创建空间、`TeamSpacePage` 空间设置/成员管理、`TransferManager` 删除任务、`RoleManagePanel`/`SpeedLimitPanel` 管理弹窗 |
| 语义 | 弹窗面板补 `role="dialog"`、`aria-modal="true"`、`aria-label`、`tabIndex={-1}`；预览页补 dialog 语义 |
| 原生 confirm | 6 处 `confirm('...')` 全部替换为 `useConfirm()`，带标题/说明/危险操作文案 |
| 右键菜单 | `ContextMenu` 补 `role="menu"`/`menuitem`/`separator`、`aria-label`、打开聚焦首项、↑/↓/Home/End/Escape、禁用项跳过、关闭焦点还原 |

改动范围：`st-web/src` 共 76 files changed（含此前 P0-A 改动），本轮新增/修改约 20 个弹窗相关文件。

## 2. 验收对照

| 验收项 | 状态 | 证据 |
|---|---|---|
| 新增 `useModalA11y` 并被本轮弹窗使用 | 通过 | `useModalA11y` 引用 55 处；`modal-overlay` 文件缺失 hook = 0 |
| 所有弹窗有 `role="dialog"` 与 `aria-modal` | 通过（范围内） | `role="dialog"` 30、`aria-modal` 29；唯一差值为 NotificationBell 非模态通知浮层（不在 modal 范围） |
| Tab/Shift+Tab 循环、Escape、滚动锁、焦点还原 | 通过（真实浏览器） | Settings/CreateFolder/Confirm/Preview 走查 14/14 |
| PreviewModal dialog 语义与焦点管理 | 通过（真实浏览器） | 预览页 dialog 打开、body 锁定、Escape 关闭 |
| 原生 `confirm('...')` 为 0 | 通过 | 静态扫描 0 |
| ContextMenu 方向键/Home/End/Escape + menu 语义 | 通过（真实浏览器） | 右键菜单 ↑/↓/End/Escape 走查通过 |
| `npm run lint` / `npm run build` | 通过 | 0 errors；11 个既有 warning；build 成功 |
| `git diff --check` | 通过 | exit 0 |
| 真实键盘/视觉验收 | **已执行** | `browser-walkthrough-results.json` 14/14；`browser-visual-results.json` 6/6 |

## 3. 命令与结果

在 `st-web` 下执行：

```text
npm run build
→ tsc -b && vite build 成功；PWA 64 entries 生成

npm run lint
→ 0 errors, 11 warnings（均为既有 react-refresh / exhaustive-deps）

npm run test:hash
→ 6/6 pass（本轮未改 hash 逻辑，回归确认）

git diff --check
→ exit 0
```

真实浏览器走查（本地 `st-api` dev + Vite + Chrome headless CDP）：

```text
node .ai/docs/20261017-ui-ux-p0-b/browser-walkthrough.mjs
→ 14/14 PASS：登录；Settings 打开焦点/滚动锁/Tab 陷阱/Escape/焦点还原；
   右键菜单 role+首项焦点/↓/End/Escape；新建文件夹初始焦点/Escape；
   清空回收站 ConfirmDialog 焦点/滚动锁/Escape 取消；PreviewModal 语义/滚动锁/Escape

node .ai/docs/20261017-ui-ux-p0-b/browser-visual.mjs
→ 6/6 PASS：深色 /files 无大面积白底；/recycle、/shares 深色可渲染；
   390px 移动端 pageOverflow=0、tableOverflow=0、移动端次要信息行存在
```

证据文件：`browser-walkthrough-results.json`、`browser-visual-results.json`、`browser-*.png`。
走查中的“清空回收站”仅打开确认框后按 Escape 取消，没有执行删除。

## 4. 静态扫描

| 检查 | 结果 |
|---|---:|
| `modal-overlay` 文件未接入 `useModalA11y` | 0 |
| `useModalA11y` 引用 | 55 |
| 原生 `confirm('...')` | 0 |
| `role="dialog"` 面板 | 30 |
| `aria-modal` 面板 | 29 |
| `ContextMenu` `role="menu"` | 1 |
| `ContextMenu` 键盘处理函数 | 2（定义 + 绑定） |
| `PreviewModal` `role="dialog"` | 1 |

说明：`role="dialog"` 比 `aria-modal` 多 1 处，为 `NotificationBell` 的非模态通知面板；它不是本轮 modal，未强制 `aria-modal`。

## 5. 行为说明

- `useModalA11y` 打开时锁定 `body.overflow`，关闭/卸载恢复；模块级计数支持“弹窗上再开确认框”。
- 初始焦点优先级：`initialFocusRef` → 面板内已有焦点 → 第一个可聚焦元素 → 面板本身。
- Escape 在面板内关闭；焦点在面板外时由各弹窗原有 window 监听兜底（PreviewModal）。
- `ContextMenu` 打开后聚焦第一个可用菜单项；↑/↓ 循环、Home/End 跳转、Escape 关闭；Tab 不离开菜单。
- 所有改动不改变 API 调用、权限判断、文件操作和弹窗业务内容。

## 6. 真实浏览器走查结果与限制

**已执行（2026-10-17 20:47）**：本地启动 `st-api` dev（8080，MySQL/Redis/MinIO 可用）、Vite（5173），用 Chrome headless + CDP 登录 `admin` 后走查。

| 场景 | 结果 |
|---|---|
| Settings 打开：焦点在弹窗内、body 锁定 | PASS |
| Settings：连续 16 次 Tab 不逃逸 | PASS |
| Settings：Escape 关闭、body 恢复、焦点回到触发按钮 | PASS |
| ContextMenu：`role=menu`、首项 `menuitem` 聚焦 | PASS |
| ContextMenu：↓ 移动到下一项、End 到末项 | PASS |
| ContextMenu：Escape 关闭 | PASS |
| 新建文件夹：初始焦点在输入框、body 锁定、Escape 关闭 | PASS |
| 清空回收站 ConfirmDialog：焦点/滚动锁、Escape 取消 | PASS |
| PreviewModal：dialog 语义、body 锁定、Escape 关闭 | PASS |
| 深色 `/files` 无大面积白底 | PASS |
| 深色 `/recycle`、`/shares` 正常渲染 | PASS |
| 390px `/files`：pageOverflow=0、tableOverflow=0 | PASS |

证据：`browser-walkthrough-results.json`（14/14）、`browser-visual-results.json`（6/6）、`browser-files-light.png`、`browser-files-dark.png`、`browser-recycle-dark.png`、`browser-shares-dark.png`、`browser-files-mobile-light.png`。

**仍未覆盖**：

- 30 个弹窗未逐个走查；本轮覆盖 Settings / CreateFolder / Confirm / Preview / ContextMenu 五类代表场景。
- 未测 7 套主题色、768/1024 宽度、iOS Safari、嵌套“弹窗上再开确认框”的滚动锁计数。
- ActionSheet 的移动端键盘/滚动锁未单独走查。
- 没有执行真实删除/清空：ConfirmDialog 仅打开后 Escape 取消。


## 7. 剩余风险与后续

- `Sidebar` 移动抽屉、`FileBrowserDialogs` 容器未按 modal 处理；抽屉不是模态对话框，`FileBrowserDialogs` 只渲染子弹窗，暂不强制。
- P1 仍未做：表格 ARIA/grid 语义、触控目标 40px、PageHeader、筛选器、导航收敛、PWA 品牌细节。
- `useModalA11y` 的滚动锁使用 `body.overflow`，对 iOS Safari 的完全固定滚动仍有限；后续可改为 `position: fixed` + 滚动位置恢复。
- 本轮未改后端/桌面端；工作区原有未提交改动未触碰。

## 8. 回滚

本轮代码改动位于 `st-web/src` 的弹窗/预览/右键菜单/团队确认相关文件；文档位于 `.ai/docs/20261017-ui-ux-p0-b/`、`.ai/tasks/TASK-20261017-ui-ux-p0-b.md`、`.ai/knowledge/ui-design-system.md` 的 P0-B 规则段。回滚这些文件即可；不影响后端/桌面端。

## 9. 知识同步

- `.ai/knowledge/ui-design-system.md` 新增“弹窗与浮层可访问性（2026-10-17）”：`useModalA11y`、`role/aria-modal`、禁止原生 confirm、ContextMenu 键盘规则。
- 无需数据库/API 知识同步。
