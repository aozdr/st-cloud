# EXP_DESIGN 独立复核

- 任务：TASK-20260923-team-search-watch-exp-design
- Dispatch：DISPATCH-20260923-TSW-EXP-DESIGN-A1
- 复核对象：tsw-design-r3
- 结论：FAIL（建议 EXP_DESIGN 保持未通过）

## 背景

独立核对本轮 UI 规格是否在需求、技术设计和当前前端代码中得到覆盖。代码只作只读交叉检查；本次没有运行构建、测试或浏览器验证。

## 输入

- .ai/tasks/TASK-20260923-team-search-watch-exp-design.md
- .ai/state/20260923-team-search-watch.yaml 最小快照：任务为 20260923-team-search-watch，设计修订为 tsw-design-r3，EXP_DESIGN 仍为 pending。
- .ai/docs/20260923-team-search-watch/requirement.md
- .ai/docs/20260923-team-search-watch/uispec.md
- .ai/docs/20260923-team-search-watch/design.md
- .ai/docs/20260923-team-search-watch/architecture-review.md
- 搜索、关注、侧边导航及通知铃铛的当前前端源代码。

## 分项分析

| 核对项 | 需求/交互规格 | 技术设计及代码证据 | 结论 |
|---|---|---|---|
| 团队搜索入口与目标定位 | uispec.md §1 定义空间和文件夹入口、范围切换、结果导航。 | design.md §7.1-2 指定 SearchPage、TeamSpacePage；TeamSpacePage.tsx:287 保留 spaceId/folderId；SearchPage.tsx:681-692 构造团队目录定位参数。 | 覆盖。 |
| 我的关注入口与详情操作 | uispec.md §2 定义详情按钮、列表和导航入口。 | design.md §7.3-4 指定 FileDetailPanel、FollowingPage、Sidebar；Sidebar.tsx:40 添加 /following，App.tsx:91 注册路由，FollowingPage.tsx 区分可用与失效记录。 | 覆盖。 |
| 加载、空、错误及失权状态 | uispec.md §4 列出搜索和关注的状态文案；需求 S05、W02、W07、U01 规定错误、空和权限反馈。 | design.md §3 定义搜索错误标识，§6.2 定义失效通知的通用响应；当前 SearchPage、FollowingPage、NotificationBell 有加载、空、失败及不可用分支。游标过期且已有结果时存在下述遗漏。 | 部分覆盖；有一项明确状态行为未实现。 |
| 键盘操作 | uispec.md:27 要求通知项支持 Enter/Space、Escape 关闭并把焦点还给铃铛。 | design.md §7.5 提到键盘状态；通知项使用 button，NotificationBell.tsx:87-94 处理 Escape 并恢复焦点。搜索结果及关注目标也使用 button。 | 覆盖。 |
| 375px 窄屏 | uispec.md:44 要求 375px 验收，uispec.md:27 要求通知弹层保留视口左右间距。 | design.md §7.4-5 提到移动可达和窄屏；搜索页使用可换行/横向筛选布局，NotificationBell.tsx:169-173 使用 fixed inset-x-3 并动态限制高度。 | 静态设计及代码覆盖；本次按验证约束未做浏览器实测，须留给后续 EXP_ACCEPT。 |
| 权限及安全跳转 | requirement.md §§4.1、4.4 和 uispec.md §3 要求实时权限核验、失权时清除目标且不跳转。 | design.md §§2、3.3、6.2 明确基于当前 DB 授权、目标接口实时核权及不可用响应；SearchPage.tsx 先经团队 source 核验 folder，NotificationBell.tsx:136-150 对新关注通知请求服务端 target 并拒绝不可用目标。 | 覆盖。 |
| 搜索、关注及分页竞态 | uispec.md:13 要求快速切换时丢弃迟到响应、追加分页防重复；详情切换也不得被旧请求覆盖。 | design.md §7.1、§7.3 有请求序号/取消和节点切换约束；SearchPage 使用请求代际和分页锁，useFileWatch.ts:44-95 绑定 nodeId 并取消旧请求，FollowingPage 也用请求代际。 | 已列出的竞态覆盖。NotificationBell 的异步目标跳转另有未解决竞态，见下。 |

## 未通过项

1. 游标过期且当前页已有结果时，规格要求提供重置游标并重新搜索的按钮（uispec.md:37）。SearchPage.tsx:729 只在 results.length === 0 的错误页渲染“重新搜索”；追加请求游标过期后，旧结果仍保留，页面落入 SearchPage.tsx:733 的普通错误横幅分支且不提供重试操作，游标已被清空，加载更多也消失。用户只能自行再次提交搜索，和规格要求的显式恢复操作不符。这是未覆盖的状态体验缺口。

2. 通知目标请求没有进行中的选择锁或请求序号。NotificationBell.tsx:124-150 在异步读取 target 后直接 navigate；通知行仍可再次触发，面板也可重新打开。若先后点击 A、B 而 target 响应按 B、A 到达，最终可能跳到较早点击的 A。服务端对两个 target 都实时核权，所以这是错误落点风险，不是已确认的越权泄漏；但目前设计 §7.5 只说明安全 target 和错误时不跳转，没有处理并发点击，当前实现也没有保护。

## 决策

建议 EXP_DESIGN=fail。入口、键盘、窄屏、权限以及规格明确列出的搜索/关注请求竞态大多有覆盖；但游标过期恢复动作缺失，且通知 target 有未防护的乱序导航体验缺口。按验收门槛，仍有影响体验的缺口时不能建议通过。

建议主线程在后续修订中补齐游标过期且已有结果时的可见“重新搜索”操作，并为 NotificationBell target 加入单请求锁或最新选择代际保护；同时在设计 §7.5 写明目标加载期间的交互。修复后再进行 375px 与键盘流程的浏览器验收。

## State Delta（仅 proposal）

- 建议把 EXP_DESIGN 的 outcome 记为 fail，证据绑定 tsw-design-r3。
- 本子任务未写入 Loop State。

## 风险

- 未运行构建、测试或浏览器；静态检查不能证明 375px 的实际布局和键盘交互通过。
- NotificationBell 乱序风险是基于异步调用路径的静态推断，尚未用浏览器人为延迟响应复现。

## 下一步

由主线程处理上述两项 UI 缺口，更新设计/代码证据，再决定是否需要新的 EXP_DESIGN 复核；视觉与键盘运行验收仍需主线程完成。

## 变更影响

只新增本复核文档和独立结果文件；未修改产品代码、State 或其他任务文档。

## A2 独立复核（tsw-design-r4）

- 任务：TASK-20260923-team-search-watch-exp-design
- Dispatch：DISPATCH-20260923-TSW-EXP-DESIGN-A2
- 复核对象：tsw-design-r4；前端交叉核对对象为当前代码（State 标记 tsw-code-r7）
- 结论：建议 EXP_DESIGN=pass

A1 对 tsw-design-r3 的 FAIL 记录及其证据保持原样，不能据此代表 r4。A2 只读检查了当前需求、交互规格、设计、最小 State 快照及相关前端实现，没有运行构建、测试或浏览器。

| A1 缺口 | r4 设计与当前实现 | 结论 |
|---|---|---|
| 追加页游标过期且已有结果时没有恢复按钮 | design.md §7.1 明确要求保留已有结果并展示“重新搜索”。SearchPage.tsx:570-576 将过期游标置空并停止后续分页；结果非空时 SearchPage.tsx:733 显示恢复按钮。按钮调用 `triggerSearch` 更新 `_t`，SearchPage.tsx:591-612 使旧请求失效并以空游标重新请求第一页。 | 已修复；满足 uispec.md:37 的恢复要求。 |
| 通知 target 请求乱序时较早点击可能覆盖较晚选择 | design.md §7.5 明确要求只有最后一次选择的 target 响应可提示或导航，并丢弃过时响应。NotificationBell.tsx:127-129 为每次选择递增代际，:147-149 在 target 响应后核对代际，:56 在组件卸载时递增代际；过时成功与失败响应均不能导航或提示。 | 已修复；符合“最后一次选择优先”的设计。 |

A1 记录中的其他静态覆盖项仍与当前设计一致：团队搜索及关注入口、权限核验和安全跳转、加载/空/错误/失权状态、键盘语义和窄屏布局均有对应设计与代码。375px 实际排版、键盘操作和人为延迟 target 响应的动态场景没有运行，属于后续 EXP_ACCEPT/运行验收证据，不能声称已实测；本次未发现仍阻止 EXP_DESIGN 通过的设计或静态实现缺口。

## A2 决策与 State Delta（仅 proposal）

- 建议 EXP_DESIGN outcome=pass，证据绑定 tsw-design-r4；A1 对 r3 的 FAIL 审计记录继续有效。
- 本子任务没有写入 Loop State；最终 Evaluate 由主线程执行。

## A2 风险、下一步与变更影响

- 风险：未运行构建、测试或浏览器；静态检查不能证明 375px 实际布局、完整键盘流程或异步请求乱序的运行时表现。
- 下一步：主线程按 A2 结果及当前 revision 执行 EXP_DESIGN Evaluate；浏览器交互验收留在 EXP_ACCEPT。
- 变更影响：只追加本 A2 复核记录并新增 A2 独立结果；没有修改产品代码、State 或 A1 历史记录。

## EXP_ACCEPT 独立验收（tsw-code-r7）

- 任务：TASK-20260923-team-search-watch-exp-accept
- Dispatch：DISPATCH-20260923-TSW-EXP-ACCEPT-A1
- 结论：静态代码与 uiSpec 大体一致；建议 EXP_ACCEPT=blocked，等待可运行的浏览器环境补齐交互验收证据。此结论表示证据未闭合，不表示已发现功能失败。
- A1/A2 的 EXP_DESIGN 结论与历史记录保持原样；本节只记录本阶段。

| 验收项 | 当前证据 | 独立结论 |
|---|---|---|
| 搜索入口、空间/文件夹范围、结果定位 | `TeamSpacePage.tsx:287` 提供团队搜索入口并携带当前 spaceId/folderId；`SearchPage.tsx:704-719` 提供团队范围、空间选择与范围提示；`:677-694` 构造结果目标；`TeamSpacePage.tsx:138-177` 通过 team source 重新核验目标并解析目录。 | 静态符合。入口是否可用及团队子目录跳转未在本轮浏览器实测。 |
| 关注入口、状态与关注列表 | `FileDetailPanel.tsx:61-79` 提供加载/保存禁用、关注状态、说明和错误；`useFileWatch.ts:53-132` 按节点读取、提交并丢弃过时代次；`Sidebar.tsx:40` 与 `App.tsx:91` 注册“我的关注”；`FollowingPage.tsx:160-221` 覆盖加载、错误重试、空、不可用、取消与分页。 | 静态符合；实际权限变化、按钮反馈和列表操作未动态验证。 |
| 搜索和通知各状态 | `SearchPage.tsx:422-437, 726-735` 映射加载、未输入、无空间、文件夹失权、零结果、服务故障、范围过大和游标过期；追加页游标失效时保留结果并显示“重新搜索”。`NotificationBell.tsx:184-199` 覆盖加载、失败重试、空列表和通知项；`:149-157` 对不可用目标不导航。 | 静态符合；各 API 状态没有本轮逐项动态注入/复现。 |
| 键盘和焦点 | 搜索输入 Enter 提交（`SearchPage.tsx:704`），筛选和结果使用原生按钮并为类型筛选暴露 `aria-pressed`（`:723,734`）；通知项为 button/menuitem，Escape 关闭并把焦点还给触发器（`NotificationBell.tsx:90-97,170-199`）。既有 `browser-verification.md` 记录 Space 切换“压缩包”筛选，Escape 后焦点回到铃铛。 | 源码语义符合；既有记录只证明上述两项，不证明通知项 Enter/Space、完整 Tab 顺序或焦点在异常恢复后的行为。 |
| 375px 窄屏与桌面 | 搜索类型筛选在窄屏横向滚动且不换行（`SearchPage.tsx:723`）；通知面板用 `inset-x-3` 并按触发器/视口计算纵向边界（`NotificationBell.tsx:100-118,176`）。既有浏览器记录给出 375×812、文档 scrollWidth=375、通知弹层 left=12px/right=363.2px。 | 部分运行证据支持搜索筛选和通知弹层；我的关注页、详情面板和完整桌面布局未实测。 |
| 迟到响应、权限刷新和跳转安全 | 搜索请求使用 AbortController/请求序号并对追加结果去重（`SearchPage.tsx:528-584,592-612`）；通知 target 使用最后选择代次过滤（`NotificationBell.tsx:128-160`）；团队落点再次通过 team source 核权（`TeamSpacePage.tsx:138-177`）。 | 静态覆盖；未做人为延迟响应或撤权后刷新/点击的动态复现。 |

详细证据、运行边界和后续验收项见 `.ai/docs/20260923-team-search-watch/exp-accept-independent.md`。本轮本地浏览器没有已打开站点；访问 `http://127.0.0.1:5173/` 返回 `net::ERR_CONNECTION_REFUSED`，因此只读复核源码和既有浏览器记录，未运行构建或产品测试。既有记录明确说明未覆盖真实文件服务、MySQL、Elasticsearch 与对象存储组成的端到端链路。

## EXP_ACCEPT 主线程验收（tsw-code-r8）

用户已明确要求当前模型执行剩余工作，授权范围见 [审阅资料索引](approval-evidence.md)。本节为单人体验自检，保留上节 r7 独立验收 blocked 的历史结论，不把它改写为通过。

前端 Vite 在 `127.0.0.1:5173` 可打开；主线程实测搜索入口、团队范围选择、通知错误态与 Escape 焦点恢复。仓库浏览器脚本 `st-web/scripts/team-watch-ui-smoke.py` 用拦截 API 的确定性 fixture 驱动真实 React 页面，在 375px 宽度下验证搜索迟到响应、游标过期后恢复第一页、失效关注项脱敏、取消关注、目标定位、旧通知兼容、通知目标乱序；退出码 0。前端生产构建退出码 0。细节见 [浏览器记录](browser-verification.md) 与 [测试报告](testreport.md)。

按当前 uiSpec 的交互和状态验收，主线程自检通过。由于本地 8080 后端未启动，浏览器脚本不证明生产组合服务的端到端行为；后端权限、事务与索引在单元、H2 和临时 Elasticsearch 集成层另有证据。此结论不称为独立体验评审。
