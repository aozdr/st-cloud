# 星云盘前端 UI/UX 全面审查报告

> 审查基准：`ui-ux-pro-max` 技能 v1.0.0（React/TypeScript 前端 Pro Max 级审查）
> 审查日期：2026-08-23
> 范围：`st-web` 前端（React 18 + Vite + Zustand + Tailwind + Radix UI）

## 背景

用户要求对前端 UI/UX 做一次全面 review。本次按 ui-ux-pro-max 的四条轴（性能 / 可访问性 / 安全 / 架构）执行静态审查，未改动任何源码，只做诊断与报告。

## 输入

- 审查基准：ui-ux-pro-max SKILL.md
- 代码路径：`E:\code\st-cloud\st-web\src`
- 技术栈：React 18.3、Vite 5、Zustand 5、Tailwind 3.4、Radix UI、recharts、react-router-dom 7
- 样本量：89 个 TSX 组件文件、32 个 TS 逻辑文件，头部组件 `FileBrowser.tsx` 1412 行
- 运行验证：`npm run lint`，结果 `0 errors, 7 warnings`

## 结论（TL;DR）

整体工程质量中等偏好，编码习惯有不少加分项（安全 sanitize、PWA 缓存策略、键盘导航、对话框焦点管理、语义 HTML、lint 0 errors）。但存在 **2 个 P0 安全风险** 和 **4 个 P1 性能/架构问题**，集中在 token 存放方式、组件未 memo、列表未虚拟化、Zustand 全量订阅、超大组件。建议按「安全 → 性能核心 → 可访问性 → 工程化」推进。

---

## 一、安全（Security）

### P0-1 Token 存放于 localStorage

`accessToken` 与 `refreshToken` 均持久化在 `localStorage`，违反技能 3.2「NEVER store tokens in localStorage」。

风险：一旦发生 XSS，攻击者可读取 `accessToken`（7 天有效）与 `refreshToken` 长期凭证，实现会话劫持与持续访问。`refreshToken` 是高价值目标。

证据：
- `st-web/src/store/auth.ts`：39、44-45、61、94、99-100、109-110、131-132、140
- `st-web/src/lib/api.ts`：41、94、99-100、109-110
- `st-web/src/components/preview/PreviewModal.tsx`：111
- `st-web/src/pages/TextEditorPage.tsx`：32
- `st-web/src/lib/electron.ts`：18-19

建议：迁移到 `httpOnly` + `SameSite` cookie（优先），或至少缩短 access token 有效期、refresh token 存入 sessionStorage 并配合后端刷新轮换与设备绑定。

### P2-3 下载/预览 token 进入 URL query（低风险观察项）

`buildStreamUrl` 将下载令牌放入 URL query 作为兜底。

经现场核查：该令牌来自后端 `download-token` 接口返回的短时单次下载令牌，**并非 access token**（见 `FileThumbnail.tsx` 注释「download 令牌后端允许 URL query，绝不暴露 access token」），风险显著低于初判。

剩余风险：令牌仍会短暂出现在 URL / 浏览器历史 / 服务端访问日志。建议保持短时单次、绑定节点/IP，并在可携带 `Authorization` 头的场景优先用头部下发。

### P2-1 无 CSP

`index.html` 未配置 Content-Security-Policy，XSS 缺乏第二道防线。

证据：`st-web/index.html`（仅 meta description / theme-color）。

建议：在部署层（Nginx/网关）加 CSP，`script-src 'self'`，限制第三方字体域名。

### P2-4 开发服务器暴露面

`vite.config.ts` 使用 `host: '0.0.0.0'` 与 `allowedHosts: true`，开发服务器对局域网暴露且接受任意 Host 头，有 DNS rebinding 风险。

建议：开发模式锁定 `allowedHosts`，`host` 改为 `127.0.0.1` 或按需开放。

### ✅ 安全加分项

- `sanitizeHighlight`：用 `DOMParser` 白名单，仅保留 `<em>`、清除全部属性，能防存储型 XSS（`st-web/src/lib/utils.ts`）。
- `vite.config.ts` 的 PWA workbox：带 token 的 `/api/file/.../stream`、下载 zip、query 带 token 的请求强制 `NetworkOnly` 不落 Cache Storage，避免文件内容与令牌被缓存。
- 无 `console.log` 敏感数据（仅 DEV 下 `console.error` 记录请求错误）。
- `SearchPage` 的 `dangerouslySetInnerHTML` 全部经过 `sanitizeHighlight`，无裸用。

---

## 二、性能（Performance）

### P1-1 组件几乎未用 React.memo

89 个 TSX 组件文件，仅 `ShareSecurityPanel.tsx` 1 处使用 `memo`（`export default memo(ShareSecurityPanel)`），占比约 1%。

违反技能 1.1「Every exported component MUST be wrapped with React.memo」。父组件（尤其文件列表、表格、表格行内嵌按钮）re-render 时会级联重渲染。

证据：`rg "React.memo|memo\(" src` 仅命中 `src/components/admin/ShareSecurityPanel.tsx:252`。

建议：优先对高频子组件加 `memo`：`FileTableView`、`FileGrid`、`FileThumbnail`、`TopBar`、`Sidebar` 导航项、`TransferManager` 行项。

### P1-2 长列表无虚拟滚动

全项目搜索 `FixedSizeList / VariableSizeList / react-window / virtual / overscan` 为 0 命中。文件列表、搜索页、审计日志、传输列表均用 `files.map(...)` 全量渲染。

违反技能 1.4。`FileBrowser` 默认页大小 100（可选 50/100/150/200），`AuditLogPanel` 渲染审计日志，大目录会卡顿。

证据：`st-web/src/components/file/FileTableView.tsx`（`files.map`）、`st-web/src/components/file/FileGrid.tsx`、`st-web/src/components/admin/AuditLogPanel.tsx`。

建议：对「单页 > 50 行」的列表（审计日志、搜索结果、大目录）引入 `react-window` / `@tanstack/react-virtual`，或降低默认页大小并做增量渲染。

### P1-3 Zustand 全量订阅

8 处使用 `const { ... } = useXxxStore()` 全量解构订阅，违反技能 1.6。store 任一字段变化都会触发整个组件 re-render。

证据：
- `Login.tsx:10` `const { login, register } = useAuthStore();`
- `HomePage.tsx:42` `const { user } = useAuthStore();`；`45` `const { favorites, fetchFavorites, toggleFavorite } = useFavoritesStore();`
- `AppLayout.tsx:17` `const { user, fetchUser } = useAuthStore();`
- `TopBar.tsx:33` `const { user, logout } = useAuthStore();`；`43` `const { mode, setMode } = useThemeStore();`
- `FileBrowser.tsx:138` `const { toggleFavorite, isFavorite } = useFavoritesStore();`
- `FileTableView.tsx:40` `const { user } = useAuthStore();`

建议：改为字段级 selector `const user = useAuthStore((s) => s.user)`，多字段用 `useShallow`。

### P1-4 超大组件 / 状态过度集中

- `FileBrowser.tsx` 1412 行（超 500 上限），为典型 god component。
- `TeamSpacePage.tsx` 30 个 `useState`（超 20 上限）；`SyncPage` 19 个、`SearchPage` 14 个、`ShareAccessPage` 14 个。
- `AuditLogPanel.tsx` 624 行、`TransferManager.tsx` 523 行。

违反技能 4.1。建议：`FileBrowser` 拆分文件列表区、工具栏区、详情区、各 Dialog 为独立子组件 + `useFileBrowser` hook；`TeamSpacePage` 把活动流、成员管理、设置提取为子组件与 hook。

### P3-1 手动 chunk 不充分

`vite.config.ts` 的 `manualChunks` 仅拆分 `recharts`。可再分 `react/vendor`、`lucide`、`plyr` 减小首包。

### ✅ 性能加分项

- Route-level code splitting：`App.tsx` 全部页面 `lazy()` + `Suspense`。
- `useFileKeyboard` / `useDragSelect` / `useFileSelection` 等 hook 已被拆出，逻辑有分层意识。

---

## 三、可访问性（Accessibility）

### P2-3 文件行不可聚焦，键盘焦点依赖全局监听

`FileTableView` 的 `<tr>` 没有 `tabIndex`，无法用 Tab 到达文件行本身；行内收藏/更多按钮可 Tab。键盘导航依赖 `useFileKeyboard` 的 `window keydown` 全局监听（`st-web/src/hooks/useFileKeyboard.ts`）。当焦点落在非列表区域（如顶部搜索框外、dialog）时，方向键/Enter 无法操作列表。

建议：为表格行加 `tabIndex={0}` + roving tabindex，或对列表容器做可聚焦 `role="listbox"` + `aria-activedescendant`，并结合 `onKeyDown` 在列表内部处理导航。

### P2-4 focus-visible 覆盖不全

全局 `.btn-*` 有 `focus-visible` outline（`st-web/src/index.css:182-185`），`Sidebar`/`Dialog` 用 `focus-visible:ring-2 ring-ring`。但：
- 排序表头按钮用 `focus-visible:text-primary-600`，仅文字颜色变化，对色弱/色盲不可感知。
- 表格全选按钮、收藏按钮、更多按钮未统一 ring。
- `.input-field:focus` 仅边框 + 阴影，`outline-none` 被移除后若无系统焦点可见性，对视障用户指示偏弱。

建议：统一 `focus-visible:ring-2 ring-ring ring-offset-2`，避免仅用颜色变化。

### P2-5 eslint 未配 a11y 插件

`eslint.config.js` 只启用 `react-hooks` / `react-refresh`，未启用 `eslint-plugin-jsx-a11y`，CI 无法阻塞可访问性问题。

建议：接入 `eslint-plugin-jsx-a11y` 规则集（至少 `alt-text`、`label-has-associated-control`、`aria-*`）。

### P2-6 表单验证以 submit 为主

`Login`、`ServerConfigPage` 用 `onSubmit` + HTML `required/minLength` + 服务端校验；缺少 `onBlur` 字段级即时校验与错误位置提示。`TeamSpacePage` 成员邀请搜索已用 `onFocus/onBlur`，但多数表单未用。

违反技能 3.3「validate on submit AND on blur」。建议引入字段级校验（onBlur 时校验并高亮错误，submit 汇总）。

### ✅ 可访问性加分项

- 键盘导航功能全面：`useFileKeyboard` 覆盖 `Ctrl+A/C/X/V`、`Delete`、`F2`、`Enter`、方向键、`Home/End`、`Backspace`、`Alt+←/→`、字母快速定位；`AppLayout` 支持 `?` 打开快捷键帮助。
- 自定义 `Dialog` 有 focus trap（Tab 循环）、Escape 关闭、`role="dialog"`、`aria-modal="true"`、`aria-label`（`st-web/src/components/ui/Dialog.tsx`）。
- 语义结构良好：`<label htmlFor>`、`<th scope="col">`、`<nav aria-label>`、`<aside aria-label>`、`<main id="main-content">`、装饰图标 `aria-hidden`。
- `EmptyState` 有标题 + 描述 + 可选操作，SVG `aria-hidden`，空状态有语义文本。

---

## 四、架构（Architecture）

### P2-7 组件职责过载

- `FileBrowser`（1412 行）承担文件加载、排序、视图切换、拖拽、多选、详情、预览、分享、版本、批量重命名、归档、转码、下载、上传、右键菜单等全部职责，违反技能 4.2 分层（Page ≤ 30KB → Section ≤ 20KB → Element ≤ 10KB）。
- hook 已部分拆出（`useUpload`、`useFileSelection`、`useFileKeyboard`、`useDragSelect`、`useFileDialogs`、`useFolderSearch`），但 `FileBrowser` 仍把大量 state 压在一起。

建议：将 `FileBrowser` 拆为 `useFileBrowser` hook + 若干 Section（工具栏、列表、详情、对话框群），并把各 Dialog 独立到单独文件。

### P2-8 内联回调导致新引用

大量 `onClick={(e) => setSortBy(...)}` 内联函数（如 `FileBrowser.tsx:988-1005`、`FileTableView` 行内多个 `(e)=>{...}`），每次渲染产生新引用，抵消 memo 收益。

建议：抽取为 `useCallback`，配合子组件 `memo`。

### P3-2 快速刷新警告（7 处）

`npm run lint` 有 7 个 `react-refresh/only-export-components` warning（`ConfirmDialog`、`OperationProgress`、`PromptDialog`、`Toast`、`badge`、`button`、`useUpload`），非错误，但影响开发热更新边界。可将 context/类型拆到独立文件。

---

## 五、修复优先级路线

| 阶段 | 事项 | 优先级 |
|------|------|--------|
| 1 安全 | token 迁 httpOnly cookie（跨端契约，需确认）；补 CSP；收紧 dev server；`buildStreamUrl` query token 待观察 | P0/P2 |
| 2 性能核心 | `FileBrowser` 拆组件 + hook；`FileTableView/FileGrid/FileThumbnail` 加 memo；长列表虚拟化；store 字段级 selector | P1 |
| 3 可访问性 | 文件行可聚焦 + roving tabindex；统一 focus-visible ring；接入 jsx-a11y；表单 onBlur 校验 | P2 |
| 4 工程化 | 手动 chunk 拆分；清理 react-refresh warning | P3 |

## 六、风险与限制

- 本次为静态审查，未运行 `npm run build`，也未做运行时 Profiler / 真实用户测试；`memo` 缺失与 store 全量订阅的实际影响需 Profiler 量化。
- `sanitizeHighlight` 依赖浏览器 `DOMParser`，仅限浏览器环境，SSR 不可用（本项目为 CSR，无影响）。
- 高优先级修复涉及鉴权契约变更，需与后端沟通 token 传输方式兼容策略（AGENTS.md 要求接口契约变化说明向后兼容）。建议修复前先向用户确认迁移方案。

## 七、下一步

1. 确认是否启动「安全修复」批次（token 迁移 + stream token 兜底移除，属跨端契约变更，需用户裁决）。
2. 确认是否启动「性能重构」批次（拆 `FileBrowser` + memo + 虚拟化）。
3. 建议随后补一轮运行时性能与屏幕阅读器实测，再收敛。

---

## 八、已落地修复（2026-08-23 执行批次）

本轮「执行」只落地了前端内部、不改变鉴权契约的高价值改动，全部通过 `npm run lint`（0 errors）、`tsc -b`、`vite build`。

### 改动清单

| 文件 | 改动 |
|------|------|
| `st-web/src/pages/Login.tsx` | Zustand `useAuthStore()` 全量订阅改字段级 selector |
| `st-web/src/pages/HomePage.tsx` | `useAuthStore()` / `useFavoritesStore()` 改字段级 selector |
| `st-web/src/components/layout/AppLayout.tsx` | `useAuthStore()` 改字段级 selector |
| `st-web/src/components/layout/TopBar.tsx` | `useAuthStore()` / `useThemeStore()` 改字段级 selector |
| `st-web/src/components/file/FileBrowser.tsx` | `useFavoritesStore()` 改字段级 selector |
| `st-web/src/components/file/FileTableView.tsx` | 同上；补齐表头/行内按钮 `focus-visible` ring；全选按钮补 `role="checkbox"` + `aria-checked` 三态 |
| `st-web/src/components/file/FileThumbnail.tsx` | 高频叶子组件加 `React.memo` |
| `st-web/src/components/file/FileTypeIcon.tsx` | 高频叶子组件加 `React.memo` |
| `st-web/src/components/file/FileGrid.tsx` | 选择/收藏按钮补 `focus-visible` ring |

### 验证结果

- `npm run lint`：0 errors、7 warnings（均为既有 `react-refresh/only-export-components`，未新增）。
- `tsc -b --pretty false`：通过，无类型错误。
- `npm run build`：通过；PWA 生成 102 个 precache 条目。

### 未纳入本轮（需单独批次）

- **`FileBrowser`（1412 行）拆分 + 回调 `useCallback` 稳定化**：改动面大，需完整回归，建议独立批次。
- **长列表虚拟化**：需引入 `react-window` / `@tanstack/react-virtual` 依赖并评估分页策略。
- **token 迁 httpOnly cookie**：跨端契约变更，需先与后端确认兼容方案。
- **CSP / dev server 收紧**、**jsx-a11y ESLint 插件**：建议随安全批次处理。
- 构建提示：`recharts` chunk 533KB（gzip 152KB）超 500KB，可在 `manualChunks` 进一步拆分或对图表页做动态加载。

---

## 九、第二轮落地：FileBrowser 拆分 + 回调稳定化（同日）

按用户指定独立执行 `FileBrowser` 拆分与回调 `useCallback` 稳定化，未改动任何业务逻辑，仅做结构性重构。全部通过 `npm run lint`（0 errors，7 个既有 react-refresh warning）、`tsc -b`、`vite build`。

### 拆分结果

| 文件 | 行数变化 | 职责 |
|------|---------|------|
| `st-web/src/components/file/FileBrowser.tsx` | 1412 → 1272 | 保留数据加载、排序、选择、拖拽、上传、下载、路径等核心逻辑与主列表编排 |
| `st-web/src/components/file/FileBrowserDialogs.tsx` | 新增 302 | 承载全部浮层/对话框：右键菜单、空白菜单、归档、批量重命名、新建、重命名、转码、移动、预览、分享、版本、下载队列、下载、框选、zip 进度 |
| `st-web/src/components/file/FileBrowserPagination.tsx` | 新增 83 | 分页条（每页条数 / 页码跳转 / 上一页 / 下一页） |

### 回调 useCallback 稳定化清单

- `handleNewFile`、`handleSortChange`、`handleEdit` 改为 `useCallback`。
- 新增 `handleToolbarEdit/SortChange/SortDirToggle/Download/Move/Copy/Delete/ToggleFoldersFirst/NewFolder/BatchRename` 等工具栏回调。
- 新增 `handleListNavigate`、`handleListDoubleClick`（列表导航/双击）。
- 新增 `handlePrevPage`、`handleNextPage`（分页）。

收益：`FileToolbar`、`FileList`、`FileBrowserPagination` 等子组件的 props 回调引用稳定，配合已有 `FileThumbnail`/`FileTypeIcon` 的 `memo`，能减少因无关 state（分页输入、路径编辑、拖拽态等）变化引起的无效重渲染。

### 待办 / 建议

- `FileBrowser` 仍有约 1272 行，逻辑密度高；可进一步提取 `useFileBrowser` hook，把 state/effects 整体下沉到独立 hook（技能 4.3 模式），使主组件只做 JSX 编排。该步涉及大量状态迁移，建议作为独立批次并辅以 Profiler 回归。
- 建议对「文件浏览 / 右键菜单 / 各类对话框 / 分页 / 列表拖拽」做一次手工冒烟回归，确认视觉与交互无回归。

---

## 十、第三轮落地：提取 useFileBrowser hook（同日）

按用户“继续拆分”要求，将 `FileBrowser` 的全部状态/副作用/派生缓存/回调下沉到独立 hook，使组件只剩 JSX 编排（技能 4.3 推荐结构）。业务逻辑与交互行为保持不变，仅做代码组织迁移。全部通过 `npm run lint`（0 errors，7 个既有 react-refresh warning）、`tsc -b`、`vite build`。

### 拆分结果

| 文件 | 行数 | 职责 |
|------|------|------|
| `st-web/src/hooks/useFileBrowser.ts` | 930 | 全部 `useState` / `useEffect` / `useMemo` / `useCallback` / `useRef`，数据加载、排序、选择、拖拽、上传、下载、路径、收藏、快捷键、滚动缓存等 |
| `st-web/src/components/file/FileBrowser.tsx` | 335 | 纯编排：调用 `useFileBrowser` + 解构 + JSX 渲染，无业务逻辑 |

### 关键迁移点

- `useFileBrowser(props)` 接收 `FileBrowserProps`，返回渲染所需的全部状态、refs、回调、派生值（约 70 个字段）。
- 原本普通的 `handle*` 函数同步改为 `useCallback`（含依赖数组），保证子组件回调引用稳定。
- 处理了迁移中的 TDZ：`detailFile` 声明提前至 `handleContextAction` 之前；`navigateToPath` 前置到 `handlePathSubmit` 之前。
- `FileBrowserProps` 接口迁入 `useFileBrowser.ts` 导出，`FileBrowser.tsx` 复用，无循环依赖（接口仅本项目内使用）。

### 收益

- `FileBrowser` 组件体从 1272 行降到 335 行，职责单一，方便阅读与后续扩展。
- 逻辑与视图彻底分离，hook 可单测。
- 所有传给子组件的回调均为 `useCallback` 稳定引用，配合 `FileThumbnail` / `FileTypeIcon` 的 `memo`，减少无效重渲染。

### 待办 / 建议

- 仍建议对「文件浏览 / 右键菜单 / 各对话框 / 分页 / 列表拖拽 / 下拉刷新 / 键盘快捷键」做一次手工冒烟回归，因为这是大规模结构迁移。
- 后续可用 React DevTools Profiler 对比拆分前后渲染次数，量化性能提升。
