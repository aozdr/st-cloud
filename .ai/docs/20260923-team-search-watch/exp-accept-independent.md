# EXP_ACCEPT 独立验收

- 任务：`TASK-20260923-team-search-watch-exp-accept`
- Dispatch：`DISPATCH-20260923-TSW-EXP-ACCEPT-A1`
- 验收对象：State 与变更报告声明的 `tsw-code-r7`
- 日期：2026-09-23
- 结论建议：`EXP_ACCEPT=blocked`

## 背景与输入

独立核对搜索、关注和通知 UI 是否符合定版 `.ai/docs/20260923-team-search-watch/uispec.md`，逐项检查入口、状态、键盘、焦点、窄屏、迟到响应和安全跳转。仅只读前端代码、设计及浏览器记录；未运行构建或测试，也没有修改产品代码或 State。

输入包括 TASK、State 最小快照、`uispec.md`、`design.md`、`browser-verification.md`、`changereport.md`、先前 EXP_DESIGN A1/A2 结论，以及 `SearchPage.tsx`、`TeamSpacePage.tsx`、`FileDetailPanel.tsx`、`useFileWatch.ts`、`FollowingPage.tsx`、`NotificationBell.tsx`、`Sidebar.tsx` 与 `App.tsx`。

State 和变更报告将当前代码称为 `tsw-code-r7`。Git 当前 HEAD 是 `3ef1ad0cee6ed03c30410a5cd1a6feb1f615dc7f`，但 `tsw-code-r7` 不是可解析的 Git ref，相关前端文件处于工作树修改状态。因此，本复核按 State 标识检查当前工作树；无法把证据绑定到一个不可变 Git 对象。

## 分项验收

| uiSpec 条款 | 源码及浏览器证据 | 结论 |
|---|---|---|
| §1 团队搜索入口、空间和文件夹范围 | `TeamSpacePage.tsx:287` 从团队空间工具区进入 `/search?scope=team`，携带 spaceId 和当前 folderId。`SearchPage.tsx:704-719` 提供关键词输入、个人/团队模式、空间选择和文件夹范围提示；`:499-520` 先通过 `teamFileSource` 校验目录，`:677-694` 按文件/文件夹构造目标地址。`TeamSpacePage.tsx:138-177` 再读取目标、目录和面包屑；无权或目标不一致时提示并回到空间根。 | 静态符合。入口操作与通知/搜索结果进入团队子目录未在本轮动态验证。 |
| §1 加载、切换、结果、分页与游标过期 | `SearchPage.tsx:528-584` 为请求设置 AbortController、代次和分页锁，成功后去重；`:592-612` 在查询条件变化时取消并使旧响应失效；`:726` 展示加载骨架；`:729,733` 游标失效时清游标并展示“重新搜索”；`:734-735` 使用按钮结果和加载更多。 | 静态符合，A1 曾提出的游标恢复入口在 r7 源码中存在。搜索切换的乱序响应和真实游标过期未动态复现。 |
| §1 零结果、错误、空间/目录失权 | `SearchPage.tsx:422-437` 将 SEARCH_UNAVAILABLE、SEARCH_SCOPE_TOO_LARGE、游标错误与权限错误分开映射；`:727-731` 区分未选空间、无权目录、未输入和零结果；`:729,733` 错误显示为提示而非零结果。 | 静态符合。未连接后端验证权限撤销后刷新、Elasticsearch 故障或候选预算错误的浏览器反馈。 |
| §2 详情关注操作 | `FileDetailPanel.tsx:61-79` 显示读取/保存状态，期间禁用按钮，呈现“关注变更/已关注”、文件夹/文件说明和错误；`useFileWatch.ts:53-97,101-132` 按 nodeId 获取状态，节点切换或卸载时取消/忽略旧请求，提交失败保留服务端状态并提示。 | 静态符合。个人/团队详情面板在 375px 的可达性及关注/取消的真实反馈未动态验证。 |
| §2 我的关注入口和列表状态 | `Sidebar.tsx:40` 有“我的关注”入口；`App.tsx:91` 注册 `/following`。`FollowingPage.tsx:160-221` 包含加载、错误重试、空列表、可用目标、不可用内容、取消与加载更多；`:191-207` 仅在服务端 `available === true` 时渲染名称/路径和跳转控件，否则显示通用文案。 | 静态符合。未动态检查失权后列表刷新是否立即隐藏旧名称，也未在窄屏操作列表。 |
| §3 通知状态和目标跳转 | `NotificationBell.tsx:62-75,184-199` 区分加载、加载失败/重试、空列表和通知行；`:128-160` 保留已读失败时未读计数、只对新 FILE_CHANGE 请求安全 target，目标不可用或请求失败时不导航。`:26-40` 由服务端目标字段构造内部路由；`TeamSpacePage.tsx:138-177` 再核权。 | 静态符合。通知真实目标、失权目标和团队子目录定位未动态验证。 |
| §3 键盘语义与焦点恢复 | 搜索输入 Enter 提交（`SearchPage.tsx:704`）；搜索范围/类型和结果、关注目标/取消、通知项均使用原生 button/select，通知项标记 `role=menuitem`（`SearchPage.tsx:712-723,734`；`FollowingPage.tsx:191-210`；`NotificationBell.tsx:170-199`）。`NotificationBell.tsx:90-97` 处理 Escape 并将焦点还给铃铛。既有浏览器记录验证 Space 可切换“压缩包”，Escape 关闭后焦点回到触发按钮。 | 控件语义和 Escape 恢复静态符合；运行记录只覆盖筛选 Space 与通知 Escape，未覆盖通知项 Enter/Space、完整 Tab 顺序及焦点在查询错误后的恢复。 |
| §5 375px 与桌面布局 | `SearchPage.tsx:723` 使用窄屏横向筛选栏；`NotificationBell.tsx:100-118,176` 依据视口调整顶部和高度，左右留出 `inset-x-3`。既有 `browser-verification.md` 报告 375×812、`scrollWidth=375`、通知按钮可见、弹层 left=12px/right=363.2px。 | 既有主线程自检只支持团队搜索筛选和通知弹层的部分窄屏行为；没有本轮独立运行证据，也没有我的关注页、详情面板及完整桌面布局证据。 |
| §5 失权刷新、请求竞态与通知定位 | 源码使用请求代次/取消保护搜索及关注状态；`NotificationBell.tsx:128-160` 仅允许最后一次选中的 target 请求提示或导航，组件卸载时也作废代次；`TeamSpacePage.tsx:138-177` 对目标节点和父目录再次读取并核权。 | 静态行为符合，A1 指出的 target 乱序导航已有实现保护。没有人工延迟响应、撤权后刷新或通知团队子目录导航的动态证据。 |

## 浏览器与真实端到端边界

本线程的 Codex 内嵌浏览器没有打开的标签页。检查 `http://127.0.0.1:5173/` 时浏览器返回 `net::ERR_CONNECTION_REFUSED`，故不能独立重放交互。现有 `browser-verification.md` 是主线程自检，未保存截图；其中记录的 DOM/视口测量作为既有证据引用，不冒充本轮独立实测。

既有浏览器记录明确说明：布局、键盘与空通知状态使用当时的登录状态/前端响应验证，不能证明文件服务、MySQL、Elasticsearch、对象存储构成的真实端到端链路通过；后端 H2/单元测试也不能替代浏览器到真实依赖的链路验收。本轮没有运行构建、测试或后端服务。

## 决策

源码逐项覆盖了交互说明中的主要入口、状态、键盘原生语义、Escape 焦点恢复、窄屏布局、请求代次和安全目标核验；本次静态检查未发现新的明确 UI 实现缺陷，且游标过期恢复与通知目标乱序两项先前缺口已有修复证据。

建议 `EXP_ACCEPT=blocked`，因为本轮无法打开本地应用，现存浏览器记录也未覆盖搜索切换竞态、失权后刷新、通知目标定位、通知项 Enter/Space、详情/关注页窄屏等验收场景。该建议是证据缺口，不是把这些场景判为功能失败。另因 `tsw-code-r7` 不是 Git ref，当前树与此标签的不可变对应关系尚未证明。

## State Delta（仅 proposal）

- 建议 `EXP_ACCEPT` outcome=`blocked`，validatedRevision=`tsw-code-r7`；原因是本轮缺少可访问的本地浏览器服务，必须由主线程补齐当前修订的浏览器证据后 Evaluate。
- 本子任务未写入 Loop State。

## 风险与下一步

- 动态状态与权限场景未验证，不能据此宣称浏览器交互验收或真实端到端通过。
- Git 工作树中相关文件有未提交改动且无 `tsw-code-r7` ref，故本次证据只能对应 State 声明的当前工作树标签。
- 主线程可在可用的登录态浏览器和服务环境中补测桌面/375px 入口、搜索状态、快速切换与过期游标、撤权后刷新、通知目标及团队子目录、键盘 Enter/Space/Escape 与焦点返回，并把实际结果追加到浏览器验证记录。若需要声称真实端到端通过，还需明确验证文件服务、MySQL、Elasticsearch 与对象存储链路。

## 变更影响

本子任务只追加 `exp-review.md` 的 EXP_ACCEPT 阶段记录，新增本文件及独立结果 JSON；没有修改产品代码、State 或既有 EXP_DESIGN A1/A2 内容。
