# 星云盘前端 UI 重构需求（20260820-ui-refactor）

## 背景

用户提供 `UI_DESIGN_SPEC.md`（星云盘 Figma 风格视觉契约，版本 1.0），要求按该文档重构前端 UI。

当前 `st-web` 为 2026-08-09 起的 PikPak 风格实现（天蓝主色、默认深色、256px 侧栏、52px 密集文件行、三视图），与契约存在系统性差异。本次按契约整体重构视觉体系与文件管理界面。

## 目标

- 客观目标：按 UI_DESIGN_SPEC.md 重构 st-web 的 Design Token、全局布局、文件管理界面与通用组件，达到「现代 SaaS / Cloud Drive」观感。
- 影响范围：`st-web` 全局样式与组件；不含后端、数据库、接口契约。
- 完成标准：
  1. Design Token 全部按规范落地，页面无重复定义全局颜色。
  2. 布局尺寸与规范一致（sidebar 240px / header 68px / 内容边距 32px）。
  3. 文件列表按 FileList/FileRow 规范（64px 行高、Name/Modified/Owner/Size 列、克制 hover/selected）。
  4. 通用组件（Button/Input/Modal/ContextMenu/EmptyState/Skeleton/UploadPanel）与规范一致。
  5. 保留既有业务功能与交互：多选/拖拽/快捷键/URL 同步/三端（web/Electron/Capacitor）兼容。
  6. `npm run build` 通过；`npm run lint` 0 error；dev server 冒烟 200。

## 设计基准

- 视觉契约：用户文档 `D:\文档\下载\UI_DESIGN_SPEC.md`（版本 1.0）。Design Token、布局参数、组件规格、交互规则为强制约束。
- 冲突处理：按规范第 51 节优先级：业务需求 > 可用性 > 页面结构 > Design Token > 组件规范 > 装饰细节。

## 现状差距（核心）

| 项 | 当前 | 规范 |
|----|------|------|
| 主色 | 天蓝 #06A7FF 系（默认主题 violet） | #4F6EF7（hover #4563E6 / active #3D59D1 / light #EEF2FF） |
| 默认外观 | 深色 | 浅色优先 |
| 字体 | Plus Jakarta Sans | Inter（回退 PingFang SC / 微软雅黑） |
| 侧栏 | 256px、浅灰底、渐变 Logo、环形存储组件、激活态左边条 | 240px、白底、简洁 Logo、线性进度存储、激活态无粗边框 |
| 顶栏 | 56px、胶囊搜索、soft 阴影 | 68px、320px 圆角搜索框、轻边框 |
| 页面背景 | #FAFAF9（stone） | #F7F8FC |
| 文件列表 | 52px 行、三视图、表头 Name/Size/Modified | 64px 行、列表+网格（默认列表）、表头 Name/Modified/Owner/Size |
| 圆角/阴影 | rounded-2xl（16px）为主、shadow-float | 8~16px 分层、shadow-sm/md/lg，少阴影 |
| 动画 | 200~300ms、hover 位移/缩放 | 120~180ms ease-out、无位移缩放 |
| 首页快捷卡片 | 渐变 + 发光 + hover 缩放 | 禁止渐变/发光/缩放，纯色克制样式 |

## 修改范围（白名单）

- 全局：`st-web/src/index.css`、`st-web/tailwind.config.js`、`st-web/src/themes.ts`、`st-web/src/store/theme.ts`、`st-web/index.html`（字体）
- 布局：`st-web/src/components/layout/{AppLayout,Sidebar,TopBar,TitleBar}.tsx`
- 文件管理：`st-web/src/components/file/{FileBrowser,FileList,FileTableView,FileTable,FileGrid,FileToolbar,FileBreadcrumb,FileThumbnail,ContextMenu,BlankContextMenu,UploadPanel,Dialogs}.tsx`
- 通用组件：`st-web/src/components/ui/{button,input,Dialog,ConfirmDialog,Toast,select,popover,switch,badge,card,table,ActionSheet}.tsx`
- 页面：`st-web/src/pages/{HomePage,FileManager,CategoryPage,FavoritesPage,RecycleBin,SearchPage,ShareManagePage}.tsx`、`st-web/src/components/home/FileCard.tsx`、`st-web/src/components/StorageAnalysis.tsx`
- 文档：`.ai/docs/20260820-ui-refactor/`

## 禁止修改范围

- 后端（st-api / st-core / st-common / st-auth 等）、数据库与迁移脚本、接口契约
- `st-desktop` 主进程逻辑、`st-web/node_modules`、`st-web/dist`
- 文件权限、分享、上传下载等业务逻辑

## 遗留问题点（Grill Me 收敛，待用户逐项拍板）

1. P1 适用范围：仅核心文件管理界面（布局 + 文件页 + 通用组件），还是全站所有页面（admin / team / transfer / sync / editor / login / 分享访问页）一并重构？
2. P2 深色模式与主题切换：规范第一阶段不要求深色模式且主色固定；当前已有深色模式与 6 主题切换。保留（默认改为浅色 + 规范主色，深色与换主题继续可用）还是按规范收敛（仅浅色 + 单一主色，移除切换入口）？
3. P3 视图模式：当前 table/card/grid 三视图，默认 table；规范为 list/grid 两视图，默认 list。保留三视图（默认改为列表样式）还是收敛为两视图（移除 card）？
