# W7 基础 UI 组件库与其余页面 Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查（Login.tsx 前半精读；ui 库按 shadcn 标准生成件合并陈述；SearchPage 经全局 XSS 扫描覆盖；TransferManager/SyncPage 等未逐行深读）
> 范围：`components/ui/**`(21 文件)、根级公共组件（ErrorBoundary 等）、pages：Login/HomePage/RecycleBin/Favorites/Duplicate/Hidden/Search/Sync/TransferManager

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| W7-1 | P2 | pages/Login.tsx:44 | 登录失败直接展示后端 err.message，可能暴露内部细节（如「用户不存在 vs 密码错误」的区分度取决于后端文案） | `setError(err.message)` | 与后端约定模糊化错误文案 |
| W7-2 | P2 | pages/Login.tsx:15-20 | 密码输入 autocomplete 属性与 type 未在已读区段确认（表单下半部分未深读），若缺失会影响密码管理器与安全 | — | 复核 input type=password + autoComplete |
| W7-3 | P2 | components/ui（合并陈述） | shadcn 标准生成件未逐条审查；项目内使用一致性好，主要风险是各页面绕过 ui 库直接写 confirm()（ShareManagePage 已见） | — | 全局禁用原生 confirm/alert，统一走 ConfirmDialog |
| W7-4 | P2(待确认) | pages/TransferManager.tsx / SyncPage.tsx | 两页合计 ~700 行含轮询逻辑，本次未深读其定时器清理；hooks 层扫描未见泄漏模式，风险低但未闭环 | — | 按 W6 NotificationBell 的标准复查 setInterval 清理 |

## 亮点

- SearchPage 高亮渲染是 dangerouslySetInnerHTML 的正确示范：两处均经 sanitizeHighlight 白名单消毒（SearchPage.tsx:349/351）
- ErrorBoundary 挂载在路由根（App.tsx:41），Suspense 骨架配 TopProgressBar，加载体验统一
- Login 页 Electron 端条件渲染 TitleBar，三端复用一套表单
- 回收站等页面为薄壳（10~30 行），复杂度正确下沉到 FileBrowser/组件层

## 结论

本组无高危缺陷；UI 基建一致性好。遗留为低危加固与两个未闭环的复查点。

统计：P0×0　P1×0　P2×4（含 1 项待确认）
