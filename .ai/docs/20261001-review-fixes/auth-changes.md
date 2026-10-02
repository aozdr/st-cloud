# 浏览器认证修复独立结果

- dispatchId：DISPATCH-review-fixes-auth-01
- taskId：TASK-20261001-review-fixes-auth
- 执行者：/root/review_fixes_auth
- 验证修订：review-fixes-code-r1
- 本轮验证时间：2026-10-01 11:55 UTC

## 背景

事实：原浏览器模块仅在初始化时读取 stcloud:auth，每页独立保存 refreshToken，续期没有跨页排队，也没有 storage 事件同步。服务端一次性 refresh 已提交但成功响应尚未落盘时，另一页可能用旧 refresh 收到 401 并删除共享记录。

## 输入

读取本次 Envelope、TASK-20261001-review-fixes-auth.md、定版 review-fixes-design-r1、testcases.md、当前 State 的相关标准与 revision；逐项核对当前 dispatch schema 的必填、常量、枚举和白名单。skillRefs 为 -，没有额外技能。本任务仅实现认证分支，不定义或判定全局 Goal，不写 State。

## 分析

读取认证上下文时会重新核对共享成对记录，事件尚未送达也能获取完整最新 pair。storage 回调重读当前共享记录，而不是采用事件载荷，避免迟到事件回滚；相同会话低修订不回滚内存较新 pair。删除记录使本页旧上下文失效并清空 sessionStorage。新会话推进 generation，并先通知旧用户状态失效，覆盖退出和换账号事件合并送达的情形。

## 决策

浏览器优先使用 Web Locks。普通 HTTP 缺少该能力时，每页在 localStorage 写独立 bakery ticket，先声明选号，再按号码及页 ID 排队；复核 ticket 键集合避免释放期间索引变化漏读。ticket 仅保存页、服务器、会话、修订和租约元数据，不保存另一份 token。获得排他权后重读共享 pair，若本次等待期间其它页已经完成轮换则直接复用结果。

租约为 45 秒，大于 HTTP 30 秒超时；等待超过租约返回临时错误。发现同会话同修订的过期所有者、协调存储失效或自己的租约不再有效时，认证拒绝只返回错误并保留会话。有效排他权内的真实永久拒绝仍退出。新登录/退出后迟到的成功和拒绝均先核对当前上下文，不能覆盖或清除新会话。桌面继续由主进程 IPC 轮换。

## 验证

本轮真实执行，退出码均为 0：

| 命令 | 结果 | 证据重点 |
| --- | --- | --- |
| node --test st-web/src/store/auth-tabs.test.mjs st-web/src/store/auth-startup.test.mjs | 22/22，失败0、跳过0 | 新增14项共享存储/独立 VM 多标签回归，既有启动8项 |
| node --test st-desktop/src/auth-rotation.test.cjs | 25/25，失败0、跳过0 | 桌面及浏览器既有认证回归，包含桌面真实磁盘重启和落盘失败恢复 |

新增测试使用两个独立 VM 和共享存储，HTTP fixture 在检验 refresh 后立即更新服务端状态，再延迟成功响应，模拟 CAS 提交后的真实窗口。Web Locks 与无锁路径分别证明第二页不会在该窗口提交旧 refresh，最终使用同一完整 pair；额外重入测试覆盖选号期间另一页进入。租约失效测试让旧 token 的 401 先返回，确认共享记录未清除，随后成功响应落盘并被另一页采用。事件暂停、退出传播、合并换账号、旧会话迟到成功/拒绝、低修订、503、浏览器写失败均有断言。

## State Delta（仅 proposal）

建议主线程核对当前 attempt、范围、修订和证据后接受认证 TASK 的 IMPLEMENTED/pass proposal；由主线程执行全局集成、独立审查、TEST_PASS 与最终验收。未写 Loop State。

## 风险

租约提供有界协调，不保证挂起页或存储不可用时永不发生重复 HTTP；这类不确定场景通过保留会话避免旧拒绝误登出。持久化失败保留内存最新 pair，重新加载可能只能恢复最后成功写入的记录，沿用现有失败语义。测试模拟 Web Locks 调度和 storage 事件，没有声称已进行真实浏览器 UI 验证。崩溃遗留过期 ticket 在同会话同修订下会使自动拒绝清理保持保守，显式退出仍可执行。

## 下一步

主线程固定当前源文件修订后执行集成与独立复核。本 child 不触碰 core、数据库、部署或 Git index。

## 变更影响

仅修改 st-web/src/auth-session.ts、st-desktop/src/auth-test-harness.cjs，新增 st-web/src/store/auth-tabs.test.mjs 与本任务独立文档/结果。HTTP、IPC、DesktopAuthSnapshot 和原认证存储 key/pair 格式兼容；只新增临时协调 key。
