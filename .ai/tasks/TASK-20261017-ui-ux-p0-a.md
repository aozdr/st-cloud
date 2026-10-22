# TASK：TASK-20261017-ui-ux-p0-a st-web UI/UX P0-A 止血

> 依据：`.ai/docs/20261017-ui-ux-p0-a/design.md`。
> 本轮只执行用户确认的“先做一部分”中的 P0-A；P0-B 与 P1 文件页重构留给后续任务。

## 元信息

- Task ID：`20261017-ui-ux-p0-a`
- 关联 State：`.ai/state/20261017-ui-ux-p0-a.yaml`
- 归属 Agent：executor（taskType=implement）
- 创建者：workflow-manager
- 日期：2026-10-17

## 目标

在不改变 API、权限、上传下载、文件操作和同步协议的前提下，完成 st-web 的 P0-A：

1. 落盘现行设计基线，消除“三套设计口径”；
2. 修复回收站、分享管理两页的深色模式不可读；
3. 修复移动端文件列表固定 760px 导致的横向滚动；
4. 修复文本/主按钮的 WCAG AA 对比度与 `hover:bg-muted` 异常；
5. 修复文件页加载骨架屏未接入、空态变量失效、错误被伪装为空、登录错误提示与错误边界暴露堆栈。

## 用户已确认的边界

- 设计基线：保持现行紧凑布局 `224 / 60 / 40 / 52 + #4F6EF7`，回写文档，不切换 V2.0 的 `#5865F2 / 240 / 64`。
- 范围：先做一部分；本轮只做 P0-A，不一次性改 21 个页面。
- 功能边界：P0 不新增筛选器、PageHeader 标题、移动端手势等新功能。

## 修改范围（include）

- 设计文档与护栏：
  - `.gitignore`（仅放行 `st-web/src/design/tokens.md`，避免 design/ 规则漏提交契约）
  - `.ai/design/UI_DESIGN_SPEC.md`（顶部加“现行基线”说明，不重写全文）
  - `.ai/context/frontend/ui-design-system.md`（新增指针，修复 ui-review 的失效基准路径）
  - `st-web/src/design/tokens.md`（新增现行 token 与例外白名单）
- 共享样式与 token：
  - `st-web/src/index.css`
  - `st-web/tailwind.config.js`
  - `st-web/src/themes.ts`（仅当需要补充 strong token 说明）
- 深色旧页：
  - `st-web/src/pages/RecycleBin.tsx`
  - `st-web/src/pages/ShareManagePage.tsx`
- 移动端与文件页状态：
  - `st-web/src/components/file/FileTableView.tsx`
  - `st-web/src/components/file/FileBrowser.tsx`
  - `st-web/src/components/layout/AppLayout.tsx`
  - `st-web/src/components/layout/TopBar.tsx`
  - `st-web/src/index.css`（safe-area / 100dvh 辅助类）
- 空态、错误与提示：
  - `st-web/src/components/EmptyState.tsx`
  - `st-web/src/components/ErrorBoundary.tsx`
  - `st-web/src/pages/Login.tsx`
- 文本对比度机械替换：
  - `st-web/src/**/*.tsx` 中 `text-primary-600` → `text-primary-strong`
  - `hover:bg-muted` → `hover:bg-surface-2`
  - `bg-primary-600 text-white` 组合 → `bg-primary-700 text-white`（仅按钮/徽标/含白字头像，不含纯图标与进度条）
- 任务文档：
  - `.ai/docs/20261017-ui-ux-p0-a/design.md`
  - `.ai/docs/20261017-ui-ux-p0-a/verification.md`

## 禁止修改范围（exclude）

- 后端 Java 模块、数据库 schema/迁移、API 契约、权限语义、上传/下载/同步协议。
- `st-desktop`、`st-preview`、`st-share`、`st-team` 等非 st-web 模块。
- P0-B：统一 Dialog/focus trap、预览页 dialog 语义、原生 `confirm()` 替换、ContextMenu 键盘化——留到下一任务。
- P1：PageHeader 重构、上传/新建主次调整、筛选器、表格 grid 语义、导航收敛。
- 不新增第三方 UI 库；不改变路由、状态机、虚拟列表算法。
- 不删除 `st-web/dist-desktop-check` 等用户未跟踪产物。

## 验收标准

- [ ] `.ai/context/frontend/ui-design-system.md` 存在，并明确现行基线为 `224 / 60 / 40 / 52 + #4F6EF7`。
- [ ] `RecycleBin`、`ShareManagePage` 不再出现 `bg-white` 与 `stone-*` 视觉类；深色模式下文字/表格/按钮使用语义 token。
- [ ] 两页加载失败不再静默显示为空；至少给出可见错误提示与重试入口。
- [ ] `FileTableView` 在 `< md` 不再强制 760px 最小宽度；移动端文件页无页面级横向溢出。
- [ ] 文本主色使用 `text-primary-strong`（light=primary-700、dark=primary-400）；白字主按钮底色使用 `primary-700`。
- [ ] `--text-tertiary` 在浅色 surface 上对比度 ≥ 4.5:1。
- [ ] `FileBrowser` 加载态使用 `FileListSkeleton`，不再使用整页 Loader。
- [ ] `EmptyState` 不再引用不存在的 CSS 变量。
- [ ] 登录错误条有 `role="alert"`；`ErrorBoundary` 不再直接展示原始 error message。
- [ ] `npm run lint`、`npm run build` 通过（既有无关失败需如实记录）。
- [ ] `git diff --check` 通过；无后端/桌面端改动。

## 测试要求

- Web：`npm run lint`、`npm run build`。
- 静态检查：
  - `Select-String` 扫描 `bg-white`、`stone-`、`hover:bg-muted`、`text-primary-600` 残留。
  - 对比度矩阵脚本或等价计算记录在 `verification.md`。
  - `git diff --check`。
- 手工/运行（若环境允许）：375×812、390×844、768×1024、1440×900，light/dark；`/recycle`、`/shares`、`/files`、`/login`。
- 若无法启动后端/登录，记录限制，不声称已完成浏览器验证。

## 输出要求

编码完成后产出 `.ai/docs/20261017-ui-ux-p0-a/verification.md`，包含修改文件、逐条验收对照、命令与结果、UI 检查、剩余风险。
