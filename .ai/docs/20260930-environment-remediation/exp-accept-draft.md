# 当前独立体验验收进展

## 背景

按 TASK-20260930-environment-exp-accept 对当前界面验证，不使用历史验收记录替代本轮结果。Dispatch 身份为 DISPATCH-env-exp-accept-01，reviewer 为 /root/exp_accept。当前 State 的代码修订仍为 env-code-r1；等待主线程冻结 env-code-r2，尚未提出正式 EXP_ACCEPT 通过建议。

## 输入

已校验完整 Envelope 与当前 dispatch.schema.json，读取当前协议、TASK、uispec、体验设计矩阵、桌面和胶卷整改说明、最小 State 修订及相关标准。skillRefs 为 `-`。写入均为 exp-accept 前缀产物及专用 fixture，没有改产品源码、State、历史记录或运行 Maven/Git。

## 分析

事实：独立执行当前 PreviewModal 的真实 Chrome/React/受控 HTTP 专项，17/17 通过，Chrome 154.0.8037.92。源文件 SHA-256 为 ef5b11927235e1c917e780985906ed421cd44e0e6da1cee5886b0d06973d8d57。Tab、Space、Enter、方向键及边界、名称和焦点、同一挂载更新、陈旧错误、来源和密码变化、降级均通过。图标未选中对比度 4.508:1，选中 15.862:1；已人工检查焦点截图。

事实：独立执行当前认证 33 项，33/33 通过，日志为 exp-accept-auth.log。包括连续轮换、主进程/renderer 并发共用、迟到 401、永久拒绝与临时故障、旧会话成功/失败隔离、落盘失败恢复以及旧 refresh 安装恢复。使用受控 HTTP 和实际临时磁盘适配，不宣称实机 Electron。

事实：8080 旧 PID 21224 已消失。主线程恢复本轮不可变 jar（通知 PID 26304）后，真实 Chrome 页面注册新专用账号，Sidebar 关注入口、无重复/越权入口、折叠持久和深色跨页面保持均通过。第二轮专用空间 2105477988504240129、专用目录 2105477991222149121，在真实页面完成软删除、回收站显示、点击恢复及回收列表移除。创建、删除和恢复均为 HTTP 200 / businessCode 200，已人工检查回收站截图。没有永久删除、清空或操作用户既有资源。

当前缺口：恢复后重新进入团队及后续 Chrome 两轮认证验收未完整结束。首轮测试选择器误识别空态和菜单两个“新建文件夹”按钮，已修正并保留记录。第二轮直达 `/team/id` 时，relative base `./` 使嵌套资产请求错误，未完成最后的可见节点检查。第三轮再次建团队收到明确 code 2009：“剩余可分配 0 B，云盘总容量 100GB”。已按主线程通知停止注册/新团队，不绕过容量限制或自行提升权限。

进一步事实：不能将深链接空白只归因自建静态服务。独立使用 Vite 原生 preview（configFile=false 避免产品临时文件写入，base/root/dist 与当前源码一致），匿名只读重现 `/team/2105477988504240129`。请求 `/team/assets/index-Dciv9fJT.js`、react-vendor 和 lucide 都为 404，CSS 返回 200/text-html，body 以 `<!doctype html>` 开头；页面 body 为空。对照 `/team` 请求 `/assets/*` 全为 200 正确 JS/CSS，成功显示登录页。当前 main.tsx 是 BrowserRouter，Web 构建全局相对 base 是真实深链接缺陷；已通知主线程最小区分 Web 与 Electron 构建目标，当前不能给产品体验通过建议。

## 决策

胶卷与认证边界当前证据成立；真实团队删除、查看、恢复操作已经发生。末段继续执行需要主线程提供本任务专用小额 fixture 条件或恢复第二轮专用账号的测试登录能力。所有随机测试密码仅在进程内存，关闭 Chrome 后无法重登录，未留令牌或密码在产物中。前两空间沿用页面默认 10GB 配额，实际只创建 0B 目录；相关 fixture 保留以供主线程审计。

## State Delta（仅 proposal）

无正式 criterionProposal；没有写 State，尚未判定 EXP_ACCEPT 或 Goal。等待 r2 冻结及当前外部条件处理。已向主线程报告真实条件和实际 HTTP 证据。

## 风险

实机 Electron 窗口导航、Chromium 原生磁盘跨进程恢复未验证；受控 IPC/存储适配和真实 Node 重启链路不能冒充该证据。胶卷的陈旧错误通过保存真实 React handler 后受控调用验证，不能宣称网络栈自然产生该顺序。深链接已在 Vite 原生 preview 重现，须修复和复验。容量拒绝是当前环境事实；不将测试未完成误判为产品通过。

本 child 的胶卷失败图是受控 HTTP 故意注入，预期显示降级；“页面看不到”是上述 JS/CSS 深链接问题，页面未挂载。没有真实 S3 原图请求失败证据。主线程另报本轮真实 S3 HEAD/PUT/GET+SHA256 通过，该证据归主线程，不能称本 child 已独立执行。

## 下一步

主线程核对专用 fixture 后明确继续条件，随后补可见恢复末段与 Chrome 真实连续轮换；冻结 r2 后再给正式体验验收建议。

## 变更影响

新增独立浏览器脚本、胶卷专项副本、认证日志、JSON 和截图；三个随机普通测试账号、两空间及一个恢复后的 0B 文件夹，无既有用户资源操作。失败产物 exp-accept-browser-initial-*、exp-accept-browser-preview-failure-* 留存。当前 exp-accept-browser-results.json 为容量拒绝轮，不能当作完整通过。
