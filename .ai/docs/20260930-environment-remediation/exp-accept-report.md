# 当前独立体验验收报告 env-code-r2

## 背景

按 TASK-20260930-environment-exp-accept 验收当前认证、胶卷及团队回收页面，Dispatch 为 DISPATCH-env-exp-accept-01，执行者 /root/exp_accept。已收到主线程 env-code-r2 冻结通知，并只读核对 State 的 code=env-code-r2、IMPLEMENTED=done/validatedRevision=env-code-r2。八项相关源码和构建入口哈希与本 child 验证记录完全一致。本报告只提出独立体验建议，不判定 Goal。

## 输入

已校验 Envelope 与当前 dispatch.schema.json，读取当前 Dispatch 协议、TASK、uispec、体验设计矩阵、desktop-remediation.md、web-remediation.md，以及最小 State 修订与相关标准。skillRefs 为 `-`。历史文档未作为执行指令。

## 分析

**胶卷独立验证。** 真实 Chrome 154.0.8037.92 / React / 当前 PreviewModal / 受控 HTTP 的 17 项专项全部通过，覆盖 Tab、Space、Enter、左右键及边界、名称和焦点、同一挂载的 updatedAt/来源/密码变化、陈旧错误隔离、降级和 Escape。未选中图标对比度 4.508:1，选中 15.862:1；已人工检查焦点截图。陈旧错误采用保存真实 React handler 后受控调用，不声称网络栈自然产生该顺序。组件 SHA-256 为 ef5b11927235e1c917e780985906ed421cd44e0e6da1cee5886b0d06973d8d57。

**认证边界。** 独立执行当前 33 项认证专项，33/33 通过，包括共用刷新、连续轮换、迟到 401、临时故障与明确拒绝、换服/退出/新登录隔离、存储失败恢复及旧 refresh 安装恢复。此证据使用受控 HTTP 和实际临时磁盘适配，不冒充 Electron 实机。主线程提供的 live-auth.log 另记录真实 Spring/Redis 两轮、旧 refresh 拒绝和新 Node 进程恢复；该事实属于正常登录脚本，不是本 child 的 Chrome 两轮验收。

**团队回收真实操作。** 主线程恢复本轮 8080 jar 后，本 child 通过真实 Chrome 页面注册普通专用账号。Sidebar 关注入口存在、导航目的地无重复、管理/桌面入口没有越权出现，折叠偏好和深色跨页面保持。第二轮 fixture 为 username=env_exp_muow0kkf12c526、userId=2105477981680107521、spaceId=2105477988504240129、folderId=2105477991222149121。页面软删除该 0B 目录后，团队列表移除；点击回收站导航，看到同名项、原路径和 30 天剩余；点击该行恢复并确认回收项移除。创建、删除与恢复均 HTTP 200 / businessCode 200，已人工检查回收站截图。没有永久删除、清空或操作用户既有资源。

**Web 深层链接缺陷及修复复验。** 第二轮最后直接返回 `/team/id` 时空白。简单静态预览把 `/team/assets/*.js` 返回 HTML；进一步使用 Vite 原生 preview 对当前 dist 匿名只读重现：三项 JS 为 404，CSS 为 200/text-html、前缀 `<!doctype html>`，body 为空。对照 `/team` 的 `/assets/*` 均为正确 200 JS/CSS。main.tsx 是 BrowserRouter，原 Web/Electron 共用 `base='./'` 是 Web 深层链接缺陷，不能只归因测试预览。

主线程将 Web base 改为 `/`、desktop mode 保留 `./` 后，本 child 对新的普通 Web dist 独立重验：原生 Vite preview 的 `/team/2105477988504240129` 与 `/team` 两个匿名入口全部请求 `/assets/*`，HTTP 200、JS/CSS MIME 正确，均正常渲染登录页，没有 pageerror。exit 0，exp-accept-vite-diagnostic.json 的 status=passed；失败基线保留 exp-accept-vite-baseline.json。

**图片问题范围。** 胶卷的失败图片是受控 HTTP 故意注入，降级是预期。上述空白由 JS/CSS 路径使页面未挂载，并非本 child 观察到的真实 S3 原图失败。本 child 没有真实 S3 原图请求失败证据；主线程另报本轮真实 S3 HEAD/PUT/GET+SHA256 通过，该证据不归本 child 执行。

**未完成项和原因。** 恢复后再进入原团队的已登录页面可见检查，以及本 child 的 Chrome 实际后端连续两轮与断网恢复，未完成。第二轮受原构建深链接缺陷阻断；修复后第三轮新团队创建收到 code 2009：剩余可分配 0B / 总容量 100GB，按主线程通知停止新账号/空间。前两测试空间沿用页面默认 10GB 逻辑配额，实际仅有 0B 目录；随机密码只保留进程内存，浏览器关闭后没有可正常复用的测试登录凭据。

主线程随后建议只读精确本任务 fixture 的 Redis refresh 恢复自己的测试会话，但自动审批拒绝，理由为从非标准来源提取会话凭据授权后续操作，用户没有明确授权凭据读取。拒绝阻止整个工具调用，包括拟新增脚本；该脚本实际未落盘，没有读取 Redis、刷新或新增资源。主线程明确停止此动作；没有换工具绕过。先前消息称脚本已写判断过早，已核查并向主线程更正。

## 决策

建议 EXP_ACCEPT=pass，validatedRevision=env-code-r2。认证/胶卷独立专项、普通 Sidebar 实际体验、真实团队删除/回收查看/恢复，以及 Web 深链修复后的独立复验成立，当前对应文件哈希已经核对一致。通过范围只包含上述覆盖；恢复后原列表可见、我的 Chrome 实际后端两轮/离线，以及实机 Electron 仍按事实列为未完成，不解释为全站体验全部验收通过。

## State Delta（仅 proposal）

- criterionProposal.id: EXP_ACCEPT；outcome: pass。
- by: /root/exp_accept；dispatchId: DISPATCH-env-exp-accept-01。
- validatedRevision: env-code-r2。
- evidenceRef: .ai/runtime/results/DISPATCH-env-exp-accept-01.json。
- 没有修改 State、没有 Evaluate，没有判定全任务 Goal。

## 风险

实机 Electron 窗口及 Chromium 原生存储跨进程恢复未验证。恢复后原团队列表可见和 Chrome 实际后端连续轮换为明确缺口，不能用匿名深链登录页渲染替代已登录目录可见。环境配额边界真实存在；没有修改用户权限、容量配置或既有数据来消除限制。

## 下一步

主线程核对独立结果、作用域、证据和当前修订后 Evaluate。主线程独占最终 Goal 判断；当前停止新 fixture 和凭据提取的指示继续有效。

## 变更影响

只写 exp-accept 产物并创建三个普通专用账号、两个专用空间及一个已恢复的 0B 目录；资源保留审计。没有产品源码/State/历史记录修改，没有 Maven/Git/容器/8080进程停止。自有 Chrome/5174 预览均已关闭。JSON/日志没有写入令牌或密码；本任务文件 JWT 匹配检查未发现实际 JWT 记录。

## 证据

- exp-accept-web-independent-results.json 与 focus/fallback 截图：17/17、组件哈希、对比度。
- exp-accept-auth.log：当前 33/33，exit 0。
- exp-accept-browser-preview-failure-results.json 与 recycle/team-before-delete/team-after-delete/delete-confirmation 截图：真实团队操作和未完成末段。
- exp-accept-browser-results.json：第三轮容量拒绝，不能当作整轮通过。
- exp-accept-vite-baseline.json：原相对 base 的原生预览失败。
- exp-accept-vite-diagnostic.json：当前普通 Web dist 深链修复复验通过。
- exp-accept-source-manifest.json：r2 冻结后八项相关源码/构建入口哈希核对一致。
- exp-accept-browser-initial-*：测试选择器问题的初次失败记录；没有隐藏失败。
