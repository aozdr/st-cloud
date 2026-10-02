# TASK：TASK-20260912-ui-ux-polish-01 UI/UX 视觉与交互收敛

> 依据：`.ai/docs/20260912-ui-ux-polish/design.md`、`.ai/docs/20260912-ui-ux-polish/testcases.md`。
> 本 TASK 在设计确认后进入实现；编码输入只接受本文件及其关联文档。

## 元信息

- Task ID：`TASK-20260912-ui-ux-polish-01`
- 关联任务 State：`.ai/state/20260912-ui-ux-polish.yaml`
- 归属 Agent：executor（taskType=implement）
- 创建者：workflow-manager
- 日期：2026-09-12

## 目标

在不改变 API、权限、文件操作、上传下载和同步协议的前提下，完成 st-web 共享 UI 的视觉收敛和 P0 UX 修复，并切断 Electron 传输悬浮窗的用户可达路径。

## 修改范围

- 共享样式：`st-web/src/index.css`、`st-web/tailwind.config.js`。
- 共享外壳：`st-web/src/components/layout/AppLayout.tsx`、`Sidebar.tsx`、`TopBar.tsx`、`MobileTabBar.tsx`。
- 文件管理：`st-web/src/components/file/FileBrowser.tsx`、`FileToolbar.tsx`、`FileTableView.tsx`、`FileGrid.tsx`、`FileList.tsx`、`FileBreadcrumb.tsx`，必要的共享文件图标组件。
- 页面：`st-web/src/pages/HomePage.tsx`、`TransferManager.tsx`、`SyncPage.tsx`、`AdminPage.tsx`、`Login.tsx`、`st-web/src/components/home/FileCard.tsx`。
- 桌面入口：`st-desktop/src/main.ts`；只按设计切断 mini window 创建、托盘显示入口与页面入口。
- 文档：本任务 `changereport.md`、`codereview.md`、`security.md`、`testreport.md`。

## 禁止修改范围

- 禁止修改后端 API、数据库 schema、迁移、权限业务语义、上传/下载协议、同步协议和 OnlyOffice/媒体预览内核。
- 禁止新增第二套桌面 React UI、Bento、玻璃拟态、默认深色、渐变、neon、大面积灰背景、普通卡片重阴影。
- 禁止删除 mini-window IPC 类型或底层文件，除非设计确认改为完整清理；本 TASK 只要求用户不可达。
- 禁止大规模重构 Zustand、路由、文件选择状态机或业务组件。
- 禁止使用 `eslint-disable`、`any`、`ts-ignore` 绕过验证；保留用户现有未跟踪改动。

## 验收标准

- [ ] P0-DS/NAV/FILE/TRANSFER/SYNC/ADMIN/MOBILE/A11Y 全部完成。
- [ ] P1 中不扩大范围的导航、首页、网格、登录和 token 项完成。
- [ ] 1440x900、1280x800、960x600、390x844 无页面级横向溢出，文件表格只允许内部滚动。
- [ ] Electron 不自动创建或展示传输悬浮窗，传输管理本身仍可用。
- [ ] 无新增无语义设计体系硬编码，Web/Electron 仍共享 st-web UI。

## 测试要求

- Web：`npm run lint`、`npm run build`、`npm run test:hash`。
- Desktop：`npm run lint`、`npm run test`、`npm run build:main`。
- 静态检查：Ctrl+F、mini window、waterfall filter、硬编码色值、`git diff --check`。
- 手工检查：testcases TC-001~TC-014，至少覆盖浅色/深色和四种尺寸。

## 输出要求

编码完成后产出 `.ai/docs/20260912-ui-ux-polish/changereport.md`，包含修改文件、验收对照、验证命令、未完成项和风险。

