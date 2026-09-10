# D1 Electron 外壳与 IPC Code Review

> 审查人：主线程 reviewer（ox-alpha）/ 日期：2026-08-22 / 方式：静态人工审查
> 范围：`st-desktop/src/main.ts`、`preload.ts`、`ipc-handlers.ts`、`mini-window.ts`、`menu-window.ts`、`electron-builder.json`

## 模块概述

- `main.ts`(189行)：窗口创建、app:// 特权协议、诊断日志、启动编排（DB→IPC→传输恢复→同步恢复）
- `preload.ts`(236行)：contextBridge 暴露 electronAPI，约 50 个固定通道封装
- `ipc-handlers.ts`(282行)：全部 ipcMain.handle 注册
- `mini-window.ts`(329行)/`menu-window.ts`(133行)：悬浮窗与右键菜单小窗，含拖拽/边界钳制/位置持久化

## 问题清单

| 编号 | 严重度 | 位置 | 问题 | 证据(代码片段) | 建议 |
|---|---|---|---|---|---|
| D1-1 | **P0** | src/main.ts:60 | 渲染进程禁用同源策略 `webSecurity: false`（为绕 CORS）。Electron 安全基线明令禁止；一旦渲染层出现 XSS（见 W1-3 高亮注入），攻击者可不受限跨源读取与请求 | `webSecurity: false,` | 删除该配置；CORS 问题改由主进程代理请求或服务端白名单解决 |
| D1-2 | P1 | src/ipc-handlers.ts:107-124,208-218 | IPC handler 不校验 event.senderFrame；路径类参数（upload filePath / download savePath / openPath / trashItem）零校验，配合 D1-1 的 XSS 链可打开或回收站删除任意文件 | `ipcMain.handle('shell:trashItem', (_e, filePath) => shell.trashItem(filePath))` | 校验 sender；路径限定在用户经 dialog 选择过的集合或下载目录内 |
| D1-3 | P1 | src/main.ts:134-183 | 未启用单实例锁。双开实例并发操作同一 userData 下的 sql.js 库与配置文件，存在数据损坏风险 | 缺少 `app.requestSingleInstanceLock()` | 启动时获取锁，失败则聚焦已有实例退出 |
| D1-4 | P2 | src/main.ts:57 | `sandbox: false`，但 preload 仅用 ipcRenderer/contextBridge，完全可开沙箱 | `sandbox: false` | 改 sandbox:true 收敛 preload 能力 |
| D1-5 | P2 | src/main.ts:139-150 | app:// 协议直接 join pathname 后返回文件，未做 resolve 后前缀断言（URL 规范化已挡住大部分穿越，属加固项） | `path.join(process.resourcesPath, 'web', pathname)` | `path.resolve` 后校验 startsWith(resourcesPath/web) |
| D1-6 | P2 | src/main.ts:79-85 | setWindowOpenHandler 对非 http(s) 的 url 返回 `{action:'allow'}`，应默认拒绝 | `return { action: 'allow' };` | 其余一律 deny |
| D1-7 | P2 | src/main.ts:65-67 | 渲染进程 console 全量落盘 desktop-log.txt，无轮转上限，可能记录敏感信息 | `appendLog(\`[renderer] ${message}\`)` | 加大小上限轮转；过滤含 token 的行 |
| D1-8 | P2 | src/ipc-handlers.ts:275-282 | setupTaskUpdateForwarding 为空实现死代码 | 函数体仅注释 | 删除 |
| D1-9 | P2 | electron-builder.json | Windows 目标无代码签名配置，用户侧会有 SmartScreen 告警 | `"win": { "target": ["nsis"] }` | 接入签名证书或文档说明风险 |

## 亮点

- preload 暴露面收敛良好：全部固定通道名，无任意 channel 转发；事件监听均返回退订函数，前端不会泄漏监听器
- contextIsolation:true + nodeIntegration:false 三窗口一致执行
- mini/menu 小窗工程质量高：尺寸钉回、显示器边界钳制、失焦闪烁抑制（300ms 窗口）、预加载隐藏窗口，注释详尽解释 Windows 平台坑位
- app:// 特权协议替代 file:// 解决 ES module 加载，注释说明了动机

## 结论

窗口管理与 preload 设计是亮点，但 **D1-1 webSecurity:false 是全项目最高优先级安全问题**，与 W1 的 XSS 点组合成真实攻击链，必须修复；D1-2/D1-3 作为配套加固紧随其后。

统计：P0×1　P1×2　P2×6
