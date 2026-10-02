# 程序设计（20260820-ui-refactor）

> 用户已确认：按推荐执行；收敛为两视图（list/grid）；按 UI_DESIGN_SPEC v1.0 执行。

## 1. Design Token

- `index.css`：light 主色重映射（600=#4F6EF7、700=#4563E6、800=#3D59D1、100=#EEF2FF）；中性色按规范（bg #F7F8FC、surface #FFF、surface-2 #F4F6FA、fg #1F2430、muted #697386、border #E8EBF1、border-light #EEF0F4、tertiary #929AAA、disabled #B8BEC9）；语义色 success/warning/danger/info + light 变体；阴影 sm/md/lg；`--sidebar-width:240px`、`--header-height:68px`。dark 保留并补充新 token。
- `tailwind.config.js`：字体 Inter；新增 bg-hover/bg-active/border-light/tertiary/disabled/success/warning/danger/info 色板。
- `themes.ts`：新增 `indigo` 主题（规范主色），`DEFAULT_THEME=indigo`。
- `store/theme.ts`、`index.html`：默认浅色（防闪烁脚本同步）。

## 2. 布局

- `Sidebar`：240px、白底、右 1px #E8EBF1；Logo 32px r9 纯色；导航项 40px r8，激活 #EEF2FF+#4F6EF7（无左边条）；底部存储卡 #F7F8FC r12 + 6px 线性进度。
- `TopBar`：68px、白底、下边框 #EEF0F4、padding 0 32px；搜索框 320×40 r10，focus 白底 + #C9D2FF 边框 + 3px rgba(79,110,247,.10)；头像 36px 纯色。

## 3. 文件模块

- `FileBrowser`：滚动容器 + 内容区 padding 28/32/40 + PageHeader（title 可选）+ Toolbar + 白卡列表容器（r14、1px 边框）+ 分页。
- `FileTableView`（list 视图）：表头 44px #FCFCFD；列 Name/Modified/Owner/Size + 操作；行 64px；hover #F8FAFF、selected #F1F4FF；复选框 16×16 r4；FileIcon 40×40 r10 类型底色。
- `FileGrid`：卡 14px 圆角、16px 内边距、白底 1px 边框；缩略图 r10。
- `FileToolbar`：两视图切换（list/grid）；40px；sort 仅 grid 显示。
- `Dialogs`：文件弹窗统一 480px、16px 圆角、shadow-lg；EmptyState 56px 图标。
- `ContextMenu`：180px、r10、shadow-md、项 36px r7。
- `utils.ts`：文件类型图标色按规范映射。

## 4. 页面

- `FileManager`：传 `title="我的文件"`。
- `HomePage`：Hero 纯色、快捷卡去渐变/发光/缩放；进度条纯色。
- `FileCard`：边框卡 + hover 仅变色。

## 5. 验证

`npm run build`、`npm run lint`、dev server 冒烟、无头 Chrome CDP 量化测量（见 testreport.md）。
