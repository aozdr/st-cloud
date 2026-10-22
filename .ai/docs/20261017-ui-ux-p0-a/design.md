# 程序设计文档：st-web UI/UX P0-A 止血

> 任务 ID：`20261017-ui-ux-p0-a`
> 输入：`.ai/tasks/TASK-20261017-ui-ux-p0-a.md`、2026-10-17 用户确认的三项决策。
> 约束：`AGENTS.md`、`.ai/design/UI_DESIGN_SPEC.md`、`.ai/knowledge/ui-design-system.md`。

## 一、目标与边界

### 目标

在不改变业务/API/权限语义的前提下，完成 UI/UX 评审中的 P0 级止血：

1. 统一设计源，明确现行基线；
2. 修复回收站、分享管理的深色模式不可读；
3. 修复移动端文件列表横向溢出；
4. 修复文本/主按钮对比度与次级按钮 hover 态；
5. 修复核心加载/空态/错误态的可发现性。

### 非目标（本轮不做）

- 不换品牌色、不换布局基线、不做全站换肤。
- 不统一 Dialog/focus trap、不替换原生 `confirm()`、不做 ContextMenu 键盘化（P0-B）。
- 不重构文件页 PageHeader、上传/新建主次、筛选器、表格 ARIA（P1）。
- 不修改后端、数据库、桌面端、同步/上传协议。

### 用户已确认的决策

- 基线：现行 `224 / 60 / 40 / 52 + #4F6EF7`，回写文档。
- 范围：先做一部分，本轮 P0-A。
- 功能：P0 不新增功能。

## 二、现状与问题映射

| 问题 | 证据 | 本轮处理 |
|---|---|---|
| 设计源冲突 | `UI_DESIGN_SPEC.md` v2.0、`ui-design-system.md`、代码三套数值 | 新增 v3 指针与 token 表，v2.0 标注历史 |
| 深色两页不可读 | `RecycleBin` / `ShareManagePage` 使用 `bg-white` + `stone-*` | 语义 token 替换 + 错误态 |
| 移动端 760px | `FileTableView.min-w-[760px]`，默认 list | 断点解除最小宽度 + 移动列隐藏 |
| 文本/按钮对比度 | `text-primary-600`、`bg-primary-600 text-white`、`text-tertiary` | 新增 `primary-strong`，主按钮 700，tertiary 加深 |
| hover 异常 | `hover:bg-muted` 使文字对比度降到 3.12/2.14 | 改 `hover:bg-surface-2` |
| 状态缺失 | FileBrowser 整页 Loader；`FileListSkeleton` 未使用；`EmptyState` 变量不存在 | 接入骨架屏、修变量、错误边界/登录提示 |
| 错误伪装为空 | 两页 `catch { setItems([]) }` | 增加可见错误与重试 |

## 三、方案

### 3.1 设计源与 token

新增 `.ai/context/frontend/ui-design-system.md` 作为 `ui-review` 基准路径的指针，内容只写“现行基线 + 禁止项 + 例外”，不复制大段规范。`UI_DESIGN_SPEC.md` 顶部加历史说明，指向新基线。

新增 `st-web/src/design/tokens.md`，记录：

- 布局：sidebar 224（折叠 64）、header 60、file table header 40、row 52、content padding 20/32。
- 主色：`#4F6EF7`；文本主色：light `primary-700` / dark `primary-400`；实心主按钮：`primary-700`。
- 中性：`bg #F7F8FA`、`surface #FFF`、`surface-2 #F6F7F9`、`fg #20242D`、`muted #687080`、`tertiary #657184`、`border #E8EAF0`。
- 例外白名单：文件类型图标色、`FileTypeIcon` 的固定 SVG 色、语义状态色（success/warning/danger）。

### 3.2 `primary-strong` token

在 `index.css` 增加：

```css
--primary-strong: var(--color-primary-700); /* light #4563E6 */
.dark { --primary-strong: var(--color-primary-400); } /* dark #798FFC */
```

Tailwind 增加 `primary-strong` 颜色映射。全局把 `text-primary-600` 替换为 `text-primary-strong`（含 `hover:`/`group-hover:` 前缀），使文本与图标在浅色/深色下都达到：

- light：primary-700 on white = 5.02:1（7 套主题 ≥5:1）
- dark：primary-400 on surface = 5.74:1（7 套主题 ≥4.99:1）

`text-primary-600` 不再用于正文/链接；保留 `bg-primary-600` 用于纯图标、进度条、装饰块。

### 3.3 白字主按钮

- `.btn-primary`：底色 `primary-700`，hover `primary-800`，active `primary-900`。
- 仅对 `bg-primary-600 text-white` 的按钮/徽标/含白字头像改为 `bg-primary-700`；纯图标容器（Logo、文件类型块）与进度条保留 600。
- `ConfirmDialog` 危险按钮保持 `red-600`（白字对比度 4.83:1），不改语义。
- `hover:bg-muted` 统一改 `hover:bg-surface-2`。

### 3.4 回收站 / 分享管理

不重写业务逻辑，先做语义替换：

| 旧 | 新 |
|---|---|
| `bg-white` | `bg-surface` |
| `text-stone-900/800/700` | `text-fg` |
| `text-stone-600/500` | `text-muted` |
| `text-stone-400` | `text-tertiary` |
| `text-stone-300` | `text-disabled` |
| `border-stone-200` | `border-border` |
| `border-stone-100/50` | `border-border-light` |
| `bg-stone-50/100/200` | `bg-surface-2` |
| `hover:bg-stone-50` | `hover:bg-bg-hover` |
| `hover:bg-stone-200` | `hover:bg-surface-2` |
| `bg-red-50` / `text-red-600` | `bg-danger-light` / `text-danger` |
| `bg-green-50` / `text-green-600` | `bg-success-light` / `text-success` |
| `bg-amber-50` / `text-amber-600` | `bg-warning-light` / `text-warning` |

错误态：在两页增加 `loadError` state；`catch` 中设置 true 并 `showToast('加载…失败', 'error')`；渲染时在空态前插入错误块，提供“重试”按钮调用原 `fetchItems`/`fetchShares`。空态保持现有文案。

### 3.5 移动端文件列表

- `FileTableView` 容器：`min-w-[760px]` 改为 `min-w-0 md:min-w-[760px]`；表格外层仅 `md:` 起横向滚动。
- 表头/行的 `hidden` 断点保持；在 `<md` 只保留复选框、名称、更多操作；名称区显示类型/大小/时间的次要行（复用 `config.label` 与 `formatSize`）。
- `FileBrowser` 外层不加固定宽度；`AppLayout` 的 `h-screen` 改 `h-[100dvh]`，并为 header 增加 `pt-safe` 占位（仅 PWA standalone 可见）。
- 不改变桌面端列宽与行高。

### 3.6 加载/空态/错误

- `FileBrowser` 加载：使用 `FileListSkeleton`，列表/网格按当前视图传入；删除整页 `Loader2`。
- `EmptyState`：把 `--color-surface-3`、`--color-border`、`--color-muted`、`--color-amber-*` 改为现有 token（`--surface-2`、`--border`、`--muted`、`--warning`）。
- `ErrorBoundary`：用户只看到通用文案与“重试/刷新”，错误详情仅 `console.error`，不再渲染 `<pre>{error.message}</pre>`。
- `Login`：错误块加 `role="alert"`、`aria-live="polite"`，颜色改 `bg-danger-light text-danger border-danger/30`，加载按钮保留可见文案“登录中…/注册中…”。

## 四、异常与兼容

- 只改样式/展示，不改请求参数、权限判断、文件操作。
- `text-primary-strong` 是新增类；旧 `text-primary-600` 若漏替，不会构建失败，但会在静态检查中列出并修复。
- `primary-700` 底色在深色模式仍满足白字对比度；如视觉过深，只允许调整到 700/800，不回退 600。
- 移动端 `100dvh` 对旧浏览器回退 `100vh`（CSS 双写）。
- 两页错误态新增 state 不改变成功路径。

## 五、风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 全局替换 `text-primary-600` 涉及文件多 | 误替换 `bg`/`border` | 只替换精确字符串，替换后 grep 验证并 build |
| `primary-700` 让按钮变深 | 视觉变化 | 7 主题对比度均通过；截图对比 light/dark |
| 单行压缩文件手工编辑 | 语法错误 | 最小字符串替换 + `tsc -b`；必要时先只替换 token |
| 移动端隐藏列 | 信息缺失 | 名称行补次要信息；桌面列不变 |
| 无后端运行 | 无法真实截图 | verification 明确限制，只做静态/build 证据 |

## 六、测试计划

1. 静态扫描：`text-primary-600` 残留、`bg-white`/`stone-` 残留、`hover:bg-muted` 残留、`bg-primary-600 text-white` 残留。
2. 对比度：用脚本计算 7 主题 × light/dark 的 primary-strong、primary-700 白字、tertiary on surface。
3. 构建：`st-web` 下 `npm run lint`、`npm run build`。
4. 结构检查：`FileListSkeleton` 被引用、`EmptyState` 无未定义变量、`role="alert"` 存在、ErrorBoundary 不再输出 error.message。
5. 手工矩阵（若可运行）：375×812 / 390×844 / 768×1024 / 1440×900，light/dark，`/files`、`/recycle`、`/shares`、`/login`。
6. `git diff --check`。

## 七、完成标准

见 TASK 验收清单；`verification.md` 逐条给出证据与限制。
