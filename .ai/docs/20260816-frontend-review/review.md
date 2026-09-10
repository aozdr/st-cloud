# 星云盘前端（st-web）审查报告

- 日期：2026-08-16
- 范围：`st-web/`（React 18 + Vite + TS + Tailwind + Zustand + PWA/Capacitor）
- 基线：`npm run lint` 55 问题（35 错误 / 20 警告）；`npm run build` 通过（警告：recharts 534KB、xlsx 500KB 大 chunk）

## 一、结论摘要

- 优点：目录分层清晰（components/pages/hooks/store/lib）、主题系统完整（6 色 + dark/system + 防闪烁）、移动端适配到位（safe-area/touch-action/下拉刷新）、动画均走 transform/opacity 且有 prefers-reduced-motion 兜底。
- 主要问题：1 处可见 UI 缺陷、3 处 XSS 风险面、35 个 ESLint 错误、FileBrowser 超大组件（1265 行）、多处重复代码、部分键盘焦点缺失。

## 二、正确性缺陷

| 位置 | 问题 | 影响 |
|------|------|------|
| `pages/Login.tsx:53` | JSX 文本内出现字面 `\n`，会渲染为可见文本 | 桌面端登录页左面板显示多余的 `\n` |
| `pages/SearchPage.tsx:347,349` | `dangerouslySetInnerHTML` 渲染服务端返回的 `fileName` / `highlight`，未消毒；文件名为用户可控 | 潜在存储型 XSS |
| `components/preview/PreviewModal.tsx:458` | `xlsx` 的 `sheet_to_html` 输出直接 `dangerouslySetInnerHTML` | 单元格内容含 HTML 时可注入 |
| `components/layout/TopBar.tsx:231` | `<label>` 包裹 `<button>`，结构无效，点击文字不触发切换 | 开关点击区域残缺、语义错误 |
| `pages/HomePage.tsx:189` | 三元表达式作为语句（no-unused-expressions） | ESLint 错误、可读性差 |
| `components/file/FileBrowser.tsx:154` | `networkError` 只赋值未读取，注释承诺的重试 UI 未实现 | 死代码 + 功能缺失 |

## 三、可访问性（对照 Vercel Web Interface Guidelines）

- 缺少跳过主内容的 skip link。
- `index.css` 的 `.btn-primary/.btn-secondary/.btn-ghost/.btn-danger` 组件类均无 `focus-visible` 焦点环，大量按钮键盘焦点不可见。
- `FileGrid` 网格项是 `div` + onClick，无 `role`/`tabIndex`，键盘用户只能依赖自定义快捷键。
- `Sidebar.tsx` 对所有 NavLink 无条件设置 `aria-current="page"`，应仅在激活时设置。
- `Login.tsx` 移动端无 `<h1>`；部分装饰图标未加 `aria-hidden`（Login 品牌图标、提交箭头、DashboardTab 卡片图标）。

## 四、性能

- `recharts`（534KB）经 manualChunks 独立且 AdminPage 懒加载，可接受；`xlsx`（500KB）已动态导入，可接受。无需改动。
- `FileGrid`/`FileTable` 已用 `content-visibility`，每页 50 条无需虚拟化。
- 每张缩略图渲染两张同 URL 图片（模糊背景 + 前景），依赖浏览器缓存，可接受。

## 五、代码结构（重构重点）

1. `FileBrowser.tsx`（1265 行）：约 35 个 useState，三视图（table/card/grid）各传 18 个相同 props，`onDoubleClick` 逻辑复制 3 份。应抽取 hooks（selection/clipboard/dialogs/search）与统一渲染入口。
2. `FileThumbnail.tsx` 与 `FileGrid.tsx` 的 `GridThumbnail` 高度重复（缩略图获取 + token 兜底 + 模糊背景），且各自维护 IMAGE_SUFFIXES 与 `lib/utils.ts` 漂移（utils 含 tiff/tif，两处不含）。
3. `HomePage.tsx` 三段卡片（我的收藏/最近访问/最近文件）markup 几乎相同，右键菜单为内联重复实现（ContextMenu 已有公共组件）。
4. 下载/预览 URL 拼接（token 入 query）在 `fileSource.ts:48,79`、`FileThumbnail.tsx:32`、`FileGrid.tsx:64`、`PreviewModal.tsx:207,217` 重复 5 处，应抽 `buildStreamUrl`。
5. ESLint 35 错误：未用导入/变量遍布 15+ 文件，`any` 出现在 `DuplicateFilesPage.tsx:43`、`TeamInvitePage.tsx:21`、`TeamSpacePage.tsx:168`；20 条 hook 依赖警告。
6. `FileBrowser.tsx` 大量 `\uXXXX` 转义字符串与全库中文风格不一致。
7. `AppLayout.tsx:4` 导入 `TransferFloatingWidget` 未使用。

## 六、安全

- 搜索高亮与 Excel 预览的 `dangerouslySetInnerHTML` 未消毒（见第二节）。
- 下载 token 拼入 URL query（浏览器历史/日志可残留），token 短期有效可缓解；建议优先 `Authorization` 头，query 仅作兜底。
- `refreshToken` 存 localStorage，XSS 可窃取；如后端支持建议改 httpOnly cookie（需后端配合，本阶段仅记录）。
- 删除/批量操作均有确认弹窗，符合规范。

## 七、UI/UX 亮点（重构中保留）

- 主题系统（6 色板 + dark/system + 首屏防闪烁脚本）。
- `tabular-nums`、`text-wrap: balance`、`prefers-reduced-motion`、safe-area、`touch-action: manipulation`。
- 空态/骨架屏/错误 toast、删除确认、多选栏、下拉刷新。
- 快捷键（Ctrl+K/Ctrl+F/`?` 快捷键、文件列表方向键）。

## 八、重构方案（待确认后执行）

### P0 正确性与安全（低风险，先做）

1. 修复 `Login.tsx:53` 字面 `\n`。
2. 新增 HTML 消毒工具，替换 SearchPage 2 处 + PreviewModal 1 处 `dangerouslySetInnerHTML`。
3. 修复 TopBar 开关结构。
4. 修复 HomePage 三元表达式语句。
5. 清理全部 35 个 ESLint 错误（未用导入/变量、`any` 类型收敛）。

### P1 结构重构（中风险，单独一批）

6. 抽取 `useFileSelection` / `useFileClipboard` / `useFileDialogs` / `useFolderSearch`，FileBrowser 瘦身。
7. 统一三视图渲染（共享 Props 类型 + 单个 `onDoubleClick`），消除重复。
8. 合并 FileThumbnail / GridThumbnail，统一后缀表引用 utils。
9. HomePage 抽取 `FileCard`，三段卡片复用；右键菜单改用公共 ContextMenu。
10. 抽取 `buildStreamUrl`，替换 5 处重复拼接。

### P2 可访问性（低风险）

11. 组件按钮类补 `focus-visible` 焦点环；补 skip link、缺失 aria 属性；Sidebar `aria-current` 修正。

### 验证方式

- `npm run lint` 0 错误、`npm run build` 通过。
- 启动 dev server 冒烟：登录页、文件浏览（三视图/多选/拖拽/右键）、搜索页、预览弹窗截图比对。

### 风险

- P1 改动面大：FileBrowser 被个人文件、团队空间、收藏、分类多页面复用，需回归文件浏览、多选、拖拽、快捷键、URL 同步。
- 建议提交顺序：P0+P2 一批，P1 单独一批，便于回退。
