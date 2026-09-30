# 本批验证结果（目标尚未完成）
首批标准桌面 npm test：20+76=96项通过。第二批标准 npm test：20+86=106项通过，0失败/跳过，见 desktop-test-final.log。
最后仅收紧 TC04-12 的API故障输入：HTTP 403拒绝、根内路径的回收站状态；定向补强验证进行中，因此暂不提升该项。
本批采用真实生产TS模块、SQLite落盘、临时目录和独立进程；API和watcher为受控替身，不代表服务端网络、租户隔离、真实监听或完整上传任务链已验收。
静态流程校验：loopctl validate PASS；verify-loop FAIL=0/WARN=18，PASS。没有执行新的Java全量、前端构建或生产迁移。

最终补强验证：sync-historical-final.log 的8项全部通过，0失败/跳过。第二批完整回归106项通过后，只收紧本夹具TC04-12分支的两个输入，针对受影响场景完成定向回归。
本轮完整补齐TC04-03、TC04-09、TC04-12、TC04-20。当前累计45 PASS、49 PARTIAL、42 NOT RUN，FAIL 0。91条仍未完整验收，禁止关闭TEST_PASS/ACCEPT。

## 2026-09-28 续接（覆盖上方早期累计数字）
当前逐项状态由results.md生成：66 PASS / 42 PARTIAL / 28 NOT RUN。目标仍未完成，TEST_PASS/ACCEPT不得关闭。
桌面最新完整运行desktop-dedup-regression.log：20+113项，129通过、4个旧下载次数断言失败。按去重要求加强为“第一次下载、第二次复用且完整副本仅一份”后，受影响4项全部通过conflict-reuse-regression.log；生产源码没有在两次执行间再改动。不把初次全量描述成零失败。
管理真实事务17项通过；真实Redis扩展最终39项通过（auth-signed-variants.xml），包括两种角色持锁顺序、签名非法版本与状态、刷新CAS/撤权、登录权限快照。
真实Redis复现Refresh同秒签发完全相同导致双CAS成功，已在JwtUtils仅Refresh加入随机jti修复；auth-real-redis-before.txt保留失败，后续24/28/39项均通过。兼容旧令牌，无新增验证限制。
WS真实网络握手与消息用例运行中；未提前计入通过。没有对生产或共享数据库操作，隔离Redis仍属本TASK并待后续测试后清理。

鉴权收尾：auth-complete-matrix.xml为49项通过；auth-websocket.xml为12项真实WS通过。auth-sync-module-regression.log完整模块回归退出0，报告汇总见auth-sync-module-counts.json；默认无test.redis.port时Redis专项条件跳过，已由专项日志单独证明。CR-01的22条均已补齐逐项证据。
团队新增MVC入口+真实H2 21项，生产Jackson配置补入夹具后全部通过；团队角色引用/删除真实事务8项通过。接受邀请已有有效邀请引用，按既有删除保护规则两种顺序均拒绝删除，这一前置约束不能被改写成允许删除。日志team-role-contract-concurrency-fixed.log。

2026-09-28 增量：team-full-regression.log 退出码0，接口23、并发事务16、成员失效5项均通过。浏览器最终验证 browser-team-final.log 通过且无页面异常，前端构建 web-build-role-retry.log 通过。复制完整数据库夹具13项通过 team-copy-db-complete.log；首轮编译与缺表错误为测试夹具问题，分别保留日志。当前逐项计数92 PASS /31 PARTIAL /13 NOT RUN；未完成验收。
