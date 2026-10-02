# 代码审查报告：st-cloud UI/UX 视觉与交互收敛

## 审查范围

审查本任务修改的 `st-web` 共享布局、文件管理、首页、传输、同步、管理、登录代码，以及 `st-desktop/src/main.ts` 的托盘和启动入口。

## 结论

未发现新增的 P0/P1 代码问题。改动保持在 UI、可见入口和样式层，未引入 API、数据库或权限契约变化。

## 关键检查

| 检查项 | 结果 | 证据 |
|---|---|---|
| Ctrl/Cmd+F 不被应用抢占 | 通过 | TopBar 不再注册该全局 handler |
| 瀑布流不丢非图片文件 | 通过 | FileBrowser 按当前集合 gating；FileGrid 不再 `files.filter(image)` 后单独渲染 |
| 列表操作可通过 hover/focus/selected 使用 | 通过 | FileTableView 行和操作按钮包含 group/focus-within/selected 路径 |
| 悬浮窗正常入口已切断 | 通过 | main 不再 create/show mini window；TransferManager 无悬浮窗按钮 |
| 运行时移动入口 | 通过 | MobileTabBar 使用 `/search`；AppLayout 在 Electron 隐藏底部 Tab |
| 权限与业务调用链 | 通过 | 权限判断、文件操作回调、传输 IPC 调用保持原有路径 |
| 差异空白 | 通过 | `git diff --check` 无空白错误 |

## 保留项

mini-window、menu-window、preload 与 IPC 类型仍存在，这是用户确认的兼容性决策；本轮只切断正常启动、托盘和页面入口，未做破坏性删除。

## 非阻断观察

- Web ESLint 仍报告 11 个 warning，均位于本任务未修改的 Fast Refresh 或 Hook 依赖位置；无 error。
- 初始验证发现工作区 `node_modules` 缺少 package.json 已声明的 `@tanstack/react-virtual`、`dompurify`；按 lockfile 补齐依赖后已重新验证通过，过程记录见 `testreport.md`。
