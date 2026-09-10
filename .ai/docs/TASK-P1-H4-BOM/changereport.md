# Change Report — TASK-P1-H4-BOM（全仓去除 UTF-8 BOM）

- Task ID: `TASK-P1-H4-BOM`
- Agent: executor（taskType=implement）
- dispatchId: p1-bom-001
- 日期: 2026-08-14

## 背景

全量 Code Review H4 发现 73 个文件带 UTF-8 BOM，不符合 `.ai/knowledge/conventions.md` 编码规范（文本文件统一 UTF-8 无 BOM）。本任务将全仓文本文件 BOM 前缀清除，只改字节前缀，不改任何内容/逻辑/换行。

## 输入

- Dispatch Envelope：`.ai/dispatch/archived/inbox-p1-bom-001.md`
- TASK 文件：`.ai/tasks/TASK-P1-H4-BOM.md`
- 编码规范：`.ai/knowledge/conventions.md`（含非 ASCII 的 `.ps1` 脚本保留 UTF-8 with BOM）

## 分析

扫描范围：全仓文本文件（.ts/.tsx/.js/.json/.css/.sql/.md/.yaml/.yml/.java/.xml 等），排除 `node_modules/`、`target/`、`dist/`、`.git/`、`.ai/dispatch/`、`.agents/`、`.codex/` 及二进制文件。

扫描结果：86 个文件带 BOM 前缀（EF BB BF）。其中：

- 84 个按范围去 BOM：69 st-web 文件 + `docker/mysql/init/17_team_invite.sql` + 根目录 `_fix1.py`、`_patch_be.py`、`_patch_be2.py`、`_patch_be3.py`、`_patch_desktop.py`、`_patch_web.py` + `.ai/scripts` 以外杂项（`vite_dev.txt`、`_split.js`、`.ulpi/design/DESIGN.md`）
- 2 个白名单保留：`.ai/scripts/compare-schema.ps1`、`.ai/scripts/verify-loop.ps1`（含中文 467 字符，按 conventions.md 须保留 BOM 以兼容 Windows PowerShell 5.1）

## 决策

采用字节级操作：读取文件全部字节，剥离前 3 字节 `EF BB BF` 后原样写回。不经过文本编解码，保证内容、换行、结尾完全不变。

## State Delta

- 84 个文件完成 UTF-8 BOM 移除（详见下方文件清单）
- 2 个 `.ps1` 白名单文件保留 BOM（符合项目编码规范）
- 复扫结果：全仓 BOM 文件数 2（均为白名单排除项），非排除项 BOM 文件数 0
- 抽查首字节：`FileManager.tsx` -> `69 6D 70`（imp）、`index.css` -> `40 74 61`（@ta）、`17_team_invite.sql` -> `2D 2D 20`（-- ）、`vite.config.ts` -> `69 6D 70`（imp），均已非 BOM
- git diff 核对：仅首行 BOM 前缀移除（如 `theme.ts`、`17_team_invite.sql` 均显示 `-﻿...` / `+...`），无内容变化
- 验证：`npx tsc --noEmit`（st-web）通过，exit 0

### 修改文件清单（84）

根目录：`_fix1.py`、`_patch_be.py`、`_patch_be2.py`、`_patch_be3.py`、`_patch_desktop.py`、`_patch_web.py`

SQL：`docker/mysql/init/17_team_invite.sql`

st-web 根：`capacitor.config.ts`、`index.html`、`tailwind.config.js`、`vite.config.ts`、`vite_dev.txt`、`_split.js`、`.ulpi/design/DESIGN.md`

st-web/src：`index.css`、`themes.ts`、`store/theme.ts`、`hooks/useFileKeyboard.ts`、`hooks/useLongPress.ts`、`hooks/useMobile.ts`、`hooks/usePullToRefresh.ts`、`hooks/usePwaInstall.ts`、`lib/capacitor.ts`、`lib/runtime.ts`、`lib/utils.ts`、`pages/AdminPage.tsx`、`pages/DuplicateFilesPage.tsx`、`pages/FavoritesPage.tsx`、`pages/FileManager.tsx`、`pages/HomePage.tsx`、`pages/RecycleBin.tsx`、`pages/ServerConfigPage.tsx`、`pages/ShareAccessPage.tsx`、`pages/SyncPage.tsx`、`pages/TransferManager.tsx`

st-web/src/components：`EmptyState.tsx`、`ErrorBoundary.tsx`、`SettingsDialog.tsx`、`StorageAnalysis.tsx`、`TransferFloatingWidget.tsx`、`TransferSettingsDialog.tsx`

st-web/src/components/admin：`AdminDialogs.tsx`、`AuditLogPanel.tsx`、`DashboardTab.tsx`、`RoleManagePanel.tsx`、`SpeedLimitPanel.tsx`、`StorageManageTab.tsx`、`UserManageTab.tsx`

st-web/src/components/file：`ArchiveDialog.tsx`、`BatchRenameDialog.tsx`、`BlankContextMenu.tsx`、`ContextMenu.tsx`、`DownloadDialog.tsx`、`FileBrowser.tsx`、`FileGrid.tsx`、`FileTable.tsx`、`FileTableView.tsx`、`FileToolbar.tsx`、`MoveDialog.tsx`、`UploadPanel.tsx`、`VersionHistoryDialog.tsx`

st-web/src/components/layout：`AppLayout.tsx`、`MobileTabBar.tsx`、`PwaInstallBanner.tsx`、`Sidebar.tsx`

st-web/src/components/share：`ShareDialog.tsx`

st-web/src/components/ui：`ActionSheet.tsx`、`badge.tsx`、`button.tsx`、`calendar.tsx`、`card.tsx`、`ConfirmDialog.tsx`、`Dialog.tsx`、`input.tsx`、`MultiSelectBar.tsx`、`popover.tsx`、`PromptDialog.tsx`、`QrCode.tsx`、`RoleMultiSelect.tsx`、`select.tsx`、`ShortcutHelpDialog.tsx`、`switch.tsx`、`table.tsx`、`Toast.tsx`

## 风险

- 工作区存在大量既有未提交改动，本任务仅字节前缀级改动，未触碰任何内容；git diff 中非 BOM 差异均属既有改动，与本任务无关
- `.ps1` 白名单保留 BOM 属项目规范要求，如主线程复扫按“全仓 BOM=0”判定需注意排除这两项

## 下一步

主线程复扫 BOM 计数与抽查首字节，确认后标记本环节 exitCriteria done。

## 变更影响

- 编码规范一致性：全仓文本文件统一 UTF-8 无 BOM（除规范允许的含中文 `.ps1`）
- 前端构建：`npx tsc --noEmit` 通过，无类型/语法影响
- SQL 脚本：`17_team_invite.sql` 仅去 BOM，内容不变，不影响 MySQL 执行
