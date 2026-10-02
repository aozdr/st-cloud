# B01 桌面与页面认证整改

## 背景

桌面主进程只保存轮换后的 access token，旧 refresh 下一轮会被拒绝。页面业务 401、主动定时和启动恢复还分别刷新，可能竞争一次性 refresh；失败处理未区分临时离线与永久拒绝。

## 输入

当前 TASK-20260930-environment-desktop、env-design-r1、AUTH01/AUTH02 用例和体验设计矩阵。Envelope dispatchId 为 DISPATCH-env-desktop-01，角色 executor，skillRefs 为 `-`。源码核对了后端 AuthService、ResultCode 与 GlobalExceptionHandler，HTTP 契约保持不变。

## 分析

事实：后端每次刷新都返回新令牌对；Redis CAS 只接受当前 refresh。TOKEN_EXPIRED/TOKEN_INVALID 业务异常使用 HTTP 200 和 1004/1005，用户失效为 1002。因此只判断 HTTP 401 不足以识别真实失效。临时连接中断、超时或服务 5xx/5001 则不能据此永久退出。

## 决策

- 主进程 api-client 作为桌面唯一轮换者；所有主进程请求和 renderer 刷新 IPC 共用一次刷新 Promise。浏览器模式的三条刷新路径也统一到 auth-session。
- 新 access 和 refresh 同时更新，通知 renderer；renderer 使用单个 `stcloud:auth` JSON 记录保存成对令牌，并兼容旧 access/refresh 键。启动恢复从该记录读取，支持仅有旧 refresh 的安装。
- 认证会话 ID、代次、服务器和令牌修订隔离迟到结果；迟到 401 使用最新 access，仅重放一次。主进程重启后只有双方令牌对相同才重置修订基线。
- 明确认证拒绝清理会话；临时故障保留可恢复状态。落盘故障保留内存最新对，下一次同步重试写入，不记录令牌。
- 主线程已按 desktop-supplement.patch 整合 IPC 类型、处理器与 electron 桥接；desktop-lifecycle.patch 补认证事件驱动的同步引擎停止/恢复，留给主线程应用和集成验证。

## 验证

当前已执行证据见 desktop-validation.log。

| 验证 | 结果 |
| --- | --- |
| `node --test st-desktop/src/auth-rotation.test.cjs st-web/src/store/auth-startup.test.mjs` | 33/33，退出 0 |
| `node st-desktop/node_modules/typescript/bin/tsc --noEmit -p st-desktop/tsconfig.json` | 退出 0 |
| `node st-web/node_modules/typescript/bin/tsc --noEmit -p st-web/tsconfig.app.json` | 退出 0 |

HTTP 用例使用真实本机 Node HTTP 服务与实际 axios：连续两轮、旧 refresh 拒绝、主进程/renderer 并发只刷新一次、迟到 401、失败终止、连接中断恢复、HTTP 200 认证业务码、退出/换服/新登录时旧成功与失败的隔离。使用真实临时磁盘文件作为 Storage 适配，模拟两轮后重启主进程与 renderer 再次成功轮换；还覆盖存储失败及旧 IPC 修订/会话事件。启动与定时测试执行实际 auth-session/store 源码，HTTP 返回受控。

没有在本 child 执行 Maven、Git、数据库操作或真实 Spring/Redis 测试。没有声称 Electron 实机、Chromium localStorage 的真实磁盘恢复或窗口导航验收。本机 `npm` 入口指向不存在的 Roaming npm-cli.js，因此使用 Node 和已安装的本地 TypeScript，未安装依赖、未改 lockfile。

## State Delta（仅 proposal）

建议 B01 子任务部分 `IMPLEMENTED=pass`，by `/root/desktop_auth`，dispatchId `DISPATCH-env-desktop-01`，validatedRevision `env-code-r1`，evidenceRef `.ai/runtime/results/DISPATCH-env-desktop-01.json`。本 child 没有写 State，没有判定完整 Goal 或整体 IMPLEMENTED。

## 风险

受控 HTTP 与真实文件适配器不能代替 Electron/真实后端全链路验收。存储持续不可写期间若立即退出，内存中的新令牌无法保证在下一次启动恢复；恢复写入后已验证最新对可保存。新增 IPC 保持 setAuth 两参兼容，旧桌面桥接没有 refreshAuth 时仍用页面刷新。

## 下一步

主线程应用 desktop-lifecycle.patch，串行复验受影响类型/认证用例，并完成真实后端与 Electron 体验验证及独立审查。

## 变更影响

仅认证 client、主窗口认证通知、preload、认证 store 与 API、认证类型、定向测试入口与本任务产物。同步引擎和后端契约未由本 child 修改；补充 IPC 文件由主线程整合。
