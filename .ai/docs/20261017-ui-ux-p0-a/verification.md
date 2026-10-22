# 验证记录：st-web UI/UX P0-A 止血

> Task：`20261017-ui-ux-p0-a`
> 设计修订：`p0a-design-C8BDBF239C81-A657D59A7A53`
> 代码修订：`p0a-885d0ddcf9a2-3B5DFC9F2708`
> 日期：2026-10-17
> 结论：**IMPLEMENTED + VERIFIED（静态/构建 + 真实 Chrome 视觉/移动端走查）**；深色与 390px 证据见 §6。

## 1. 修改摘要

本轮只做用户确认的 P0-A，不改后端、数据库、桌面端、上传/下载/同步协议。

| 类别 | 内容 |
|---|---|
| 设计基线 | 新增 `st-web/src/design/tokens.md`、`.ai/context/frontend/ui-design-system.md`；`UI_DESIGN_SPEC.md` 顶部标注历史；`.ai/knowledge/ui-design-system.md` 增加现行基线；`.gitignore` 放行 `st-web/src/design/tokens.md`（`design/` 规则会忽略该目录） |
| Token | 新增 `--primary-strong`（light primary-700 / dark primary-400）；`--text-tertiary` 改为 `#657184`；`.btn-primary` 底色 700、hover 800、active 900；Tailwind 增加 `primary-strong` |
| 文本/按钮 | 65 个源文件中 `text-primary-600` → `text-primary-strong`（167 处）；`hover:bg-muted` → 语义 hover；含白字的 primary-600 底色统一 700；`hover:bg-primary-700` → 800 |
| 深色旧页 | `RecycleBin.tsx`、`ShareManagePage.tsx` 去除全部 `bg-white`/`stone-*`（静态扫描 0）；增加 `loadError` 状态、`role="alert"` 错误条与重试按钮 |
| 移动端 | `FileTableView` 的 `min-w-[760px]` 改为 `min-w-0 md:min-w-[720px]`；移动端名称行补类型/大小/时间次要信息；`AppLayout` 使用 `app-viewport`（100dvh）、`pb-bottom-nav`；`TopBar` 增加 `pt-safe`；`MobileTabBar` 改 `min-h-16` |
| 状态 | `FileBrowser` 接入已有 `FileListSkeleton`，移除整页 Loader；`EmptyState` 修复未定义 CSS 变量；`ErrorBoundary` 不再展示原始错误；`Login` 错误条增加 `role="alert"` 与加载文案 |

改动范围：`git diff --stat -- st-web/src` = 70 files changed, 303 insertions(+), 275 deletions(-)（含新增 `tokens.md` 未跟踪文件）。后端/桌面端无本轮改动。

## 2. 验收对照

| 验收项 | 状态 | 证据 |
|---|---|---|
| 设计基线 v3 落盘，现行 224/60/40/52 + `#4F6EF7` | 通过 | `st-web/src/design/tokens.md`；`.ai/context/frontend/ui-design-system.md`；`UI_DESIGN_SPEC.md` 顶部历史说明 |
| 回收站/分享管理无 `bg-white`/`stone-*` | 通过 | 静态扫描 0；见 §5 |
| 两页加载失败可见且可重试 | 通过（静态） | 两页 `role="alert"` 错误条 + `fetchItems`/`fetchShares` 重试；`loadError` state |
| 移动端不再强制 760px | 通过（真实浏览器） | `min-w-0 md:min-w-[720px]`；390px 实测 pageOverflow=0、tableOverflow=0 |
| 文本主色 `primary-strong` | 通过 | 167 处替换；`text-primary-600` 残留 0 |
| 白字主按钮 `primary-700` | 通过 | `.btn-primary`、`bg-primary-600 text-white`/`text-white bg-primary-600` 残留 0 |
| 浅色 tertiary ≥4.5 | 通过 | `#657184`：white 4.94、surface-2 4.61 |
| FileBrowser 使用骨架屏 | 通过 | `FileBrowser.tsx` 引用 `FileListSkeleton`（2 处） |
| EmptyState 无未定义变量 | 通过 | `--color-(border|surface|muted|amber)` 残留 0 |
| ErrorBoundary 不展示原始错误 | 通过 | `error.message` 输出 0；仅 `console.error` |
| Login 错误可被读屏播报 | 通过 | `role="alert"` 1 处；加载态保留“登录中…/注册中…” |
| `npm run lint` / `npm run build` | 通过 | 0 errors；11 个既有 warning；build 成功 |
| `git diff --check` | 通过 | exit 0 |
| 真实浏览器深色/移动端截图 | **已执行** | `browser-visual-results.json` 6/6；见 §6 |

## 3. 命令与结果

在 `st-web` 下执行：

```text
npm run lint
→ 0 errors, 11 warnings（均为既有 rule：react-refresh / exhaustive-deps；
   本轮新增的两条 showToast 依赖 warning 已修复）

npm run build
→ tsc -b && vite build 成功；3905 modules transformed；PWA 64 entries 生成

npm run test:hash
→ # tests 6 / # pass 6 / # fail 0

git diff --check
→ exit 0（仅有 LF/CRLF 提示，无空白错误）
```

生成 CSS 检查（`dist/assets/index-*.css`）：

- `text-primary-strong`、`--primary-strong`、`app-viewport`、`pb-bottom-nav`、`bg-primary-700` 均存在。
- 搜索 focus 阴影编译为 `rgb(var(--primary-strong)/.1)`，不再硬编码 `rgba(79,110,247,...)`。

真实浏览器视觉/移动端走查（2026-10-17 20:46，本地 `st-api` dev + Vite + Chrome headless CDP）：

```text
node .ai/docs/20261017-ui-ux-p0-b/browser-visual.mjs
→ 6/6 PASS：深色 /files 无大面积白底；深色 /recycle、/shares 正常；
   390px /files pageOverflow=0、tableOverflow=0、移动端次要信息行存在
```

证据：`browser-visual-results.json`、`browser-files-dark.png`、`browser-recycle-dark.png`、`browser-shares-dark.png`、`browser-files-mobile-light.png`。
说明：该走查在 P0-B 合并后的当前树上执行（当前树仍包含 P0-A 全部改动）；P0-A 的代码修订 `p0a-885d...` 是当时的快照，浏览器证据为后续补充。

## 4. 对比度矩阵

按 WCAG 2.1 相对亮度计算（正文阈值 4.5:1）：

| 主题 | primary-700 on white | primary-400 on dark surface | white on primary-700 |
|---|---:|---:|---:|
| indigo | 5.02 | 5.74 | 5.02 |
| red | 6.41 | 5.36 | 6.41 |
| blue | 6.93 | 4.99 | 6.93 |
| emerald | 5.48 | 8.77 | 5.48 |
| violet | 7.10 | 6.20 | 7.10 |
| orange | 5.18 | 7.45 | 5.18 |
| teal | 5.47 | 9.06 | 5.47 |

- 浅色 `text-tertiary #657184`：white 4.94、`surface-2` 4.61。
- 深色 `text-tertiary #8D94A0`：`surface` 5.52。
- 选中态/次要文本使用的 primary tint 背景已从 `primary-100` 调到 `primary-50`（Sidebar 激活项、版本历史徽标、搜索快捷条件），避免 4.49 的边界值。

## 5. 静态扫描

| 检查 | 结果 |
|---|---:|
| `text-primary-600` 残留 | 0 |
| `hover:bg-muted` 残留 | 0 |
| `bg-primary-600 text-white` / `text-white bg-primary-600` 残留 | 0 |
| 回收站/分享管理 `stone-` 或 `bg-white` | 0 |
| `FileTableView` 移动端最小宽度保护 | 1 |
| `FileBrowser` 引用 `FileListSkeleton` | 2 |
| `EmptyState` 未定义 `--color-*` 变量 | 0 |
| `ErrorBoundary` 输出 `error.message` | 0 |
| `Login` `role="alert"` | 1 |
| `AppLayout` `app-viewport` / `pb-bottom-nav` | 1 / 1 |
| `TopBar` `pt-safe` | 1 |
| `MobileTabBar` `min-h-16` | 1 |

说明：`bg-primary-600` 仍有 36 处，均为开关、进度条、复选框、纯图标/装饰块，不承载正文白字；不属于本轮白字对比度问题。

## 6. 真实浏览器走查结果与限制

**已执行（2026-10-17 20:46）**：本地启动 `st-api` dev（8080）、Vite（5173），用 Chrome headless + CDP 登录 `admin` 后走查。

| 场景 | 结果 |
|---|---|
| 深色 `/files`：无大面积白底 | PASS |
| 深色 `/recycle`：可渲染，header 背景 `rgb(27,29,34)` | PASS |
| 深色 `/shares`：可渲染，header 背景 `rgb(27,29,34)` | PASS |
| 390px `/files`：pageOverflow=0、tableOverflow=0 | PASS |
| 390px 移动端次要信息行存在 | PASS |

证据：`browser-visual-results.json`（6/6）、`browser-files-dark.png`、`browser-recycle-dark.png`、`browser-shares-dark.png`、`browser-files-mobile-light.png`。

**仍未覆盖**：

- 未测试 768/1024/1440 全尺寸矩阵，未逐套测试 7 种主题色。
- 深色两页仅验证渲染与背景色，未做逐字对比度扫描；对比度结论仍以 §4 的 token 计算为准。
- 移动端只测 390px，未测 375px 与 iOS Safari。


## 7. 剩余风险与后续

- **P0-B 未做**：Dialog/focus trap、PreviewModal dialog 语义、原生 `confirm()`（仍有 6 处）、ContextMenu 键盘化。
- **P1 未做**：PageHeader 重构、上传/新建主次、筛选器、表格 ARIA/grid 语义、触控目标 40px、导航收敛。
- **全局替换风险**：167 处 `text-primary-strong` 已通过 build，但个别图标/头像的深浅效果需目视确认；当前仅静态与构建证据。
- **PWA/品牌**：`theme_color:#0093e0`、`orientation:portrait`、`/vite.svg` favicon、路由 `document.title` 仍待 P1/P3。
- **本任务未改后端/桌面端**；工作区原有后端改动未触碰。

## 8. 回滚

本轮代码改动仅位于 `st-web/src`；文档位于 `.ai/docs/20261017-ui-ux-p0-a/`、`.ai/tasks/TASK-20261017-ui-ux-p0-a.md`、`.ai/context/frontend/`、`.ai/design/UI_DESIGN_SPEC.md` 顶部说明、`.ai/knowledge/ui-design-system.md`。回滚 st-web 源码即可恢复原视觉；不影响后端/桌面端。

## 9. 知识同步

- 新增 `st-web/src/design/tokens.md` 作为现行 token 单一源。
- 新增 `.ai/context/frontend/ui-design-system.md`，修复 `ui-review` 基准路径不存在的问题。
- `UI_DESIGN_SPEC.md` V2.0 标注历史；`.ai/knowledge/ui-design-system.md` 增加“现行实现基线（2026-10-17 起）”并声明冲突以 `tokens.md` 为准。
- 无需数据库/API 知识同步。
