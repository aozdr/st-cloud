# TASK-20260816-frontend-refactor-01

- taskId: TASK-20260816-frontend-refactor-01
- 任务：st-web 前端审查与重构（P0 正确性/安全 + 移除 Word/Excel 本地预览 + P1 结构重构 + P2 可访问性）
- 依据：`.ai/docs/20260816-frontend-review/review.md`（用户已确认全量执行）
- 规模：中型

## Goal

- 客观目标：消除前端正确性缺陷与 XSS 风险面；删除 docx-preview/xlsx 本地预览并统一 OnlyOffice；压缩 FileBrowser 复杂度；补齐键盘焦点等可访问性；ESLint 0 错误。
- 影响范围：`st-web/src/**`、`st-web/package.json`、`st-web/package-lock.json`；禁止修改后端与 `st-web/dist` 之外目录。
- 完成标准：`npm run lint` 0 错误；`npm run build` 通过；dev server 冒烟 200；改动不改变既有接口契约。

## 修改范围（白名单）

- `src/pages/Login.tsx`、`src/pages/HomePage.tsx`、`src/pages/SearchPage.tsx`、`src/pages/SyncPage.tsx`、`src/pages/TeamPage.tsx`、`src/pages/TeamInvitePage.tsx`、`src/pages/TeamSpacePage.tsx`、`src/pages/DuplicateFilesPage.tsx`、`src/pages/EditorPage.tsx`、`src/pages/HiddenFilesPage.tsx`
- `src/components/preview/PreviewModal.tsx`、`src/components/file/FileBrowser.tsx`、`src/components/file/FileList.tsx`（新增）、`src/components/file/FileGrid.tsx`、`src/components/file/FileTable.tsx`、`src/components/file/FileThumbnail.tsx`、`src/components/file/ArchiveDialog.tsx`、`src/components/file/MoveDialog.tsx`（仅警告保留）
- `src/components/home/FileCard.tsx`（新增）、`src/components/layout/AppLayout.tsx`、`src/components/layout/Sidebar.tsx`、`src/components/layout/TopBar.tsx`、`src/components/StorageAnalysis.tsx`、`src/components/TransferFloatingWidget.tsx`、`src/components/admin/DashboardTab.tsx`
- `src/components/team/CommentPanel.tsx`、`FolderPermissionDialog.tsx`、`NotificationBell.tsx`、`StatsPanel.tsx`
- `src/hooks/useFileSelection.ts`（新增）、`useFileClipboard.ts`（新增）、`useFileDialogs.ts`（新增）、`useFolderSearch.ts`（新增）
- `src/lib/api.ts`、`src/lib/fileSource.ts`、`src/lib/utils.ts`、`src/index.css`
- `package.json` / `package-lock.json`（移除 docx-preview、xlsx）

## 禁止修改范围

- 后端任何文件、`st-web/dist`、`st-web/node_modules`、数据库/迁移脚本。

## 验收标准

1. `npm run lint`：0 errors（react-refresh 等既有 warning 允许保留）。
2. `npm run build`：通过；产物不再包含 xlsx / docx-preview chunk。
3. Office（docx/xlsx/pptx）与 PDF 预览一律跳转 OnlyOffice（个人 `/file/:id/editor?mode=view`，分享 `/share/:shareCode/editor`）。
4. 文件浏览三视图行为不变（多选/拖拽/快捷键/URL 同步），FileBrowser 行数明显下降。
5. 搜索高亮与预览不再直接渲染未消毒 HTML。

## 验证命令

```text
cd st-web
npm run lint
npm run build
npm run dev  （curl 冒烟 /login、/src/main.tsx 等返回 200）
```
