# 影响分析（20260820-ui-refactor）

## 影响范围

- 仅 `st-web` 前端：全局样式、布局组件、文件管理组件、通用组件、首页。
- 无后端 / 数据库 / 接口契约改动；无新增依赖；无 PWA/Electron/Capacitor 构建链路改动。

## 受影响文件（26 个）

- 全局：`index.css`、`tailwind.config.js`、`themes.ts`、`store/theme.ts`、`index.html`
- 布局：`AppLayout`（未改）、`Sidebar`、`TopBar`、`TitleBar`（未改）
- 文件模块：`FileBrowser`、`FileList`、`FileTableView`、`FileGrid`、`FileToolbar`、`FileBreadcrumb`、`Dialogs`、`ContextMenu`、`UploadPanel`、`ConvertDialog`、`MoveDialog`（宽度）
- 通用：`TopProgressBar`、`utils.ts`（类型图标颜色）、`useFileKeyboard`（视图类型）
- 页面：`FileManager`、`HomePage`、`FileCard`
- 删除：`FileTable.tsx`（card 视图，已收敛为 list/grid）

## 风险

| 风险 | 等级 | 缓解 |
|------|------|------|
| 用户本地 fileView 旧值（table/card） | 低 | 初始化映射：table/card 归入 list，grid 保留 |
| 深色模式兼容 | 低 | 保留 dark token，新色提供 dark 变体 |
| 团队空间/分享页共用 FileBrowser | 低 | 仅调整布局容器与视图渲染，业务 props 不变 |

## 结论

影响可控，无跨模块回退风险。
