# 当前变更
新增续接 TASK、State、设计沿用核对及独立结果生成器；历史产物保持只读。
扩展 restart-worker.cjs 与 sync-restart.test.cjs：根对账失败/恢复6项、旧版启动升级1项、历史CREATE/MOVE当前状态及403/超时8项、双根迁移故障隔离2项。
均运行生产 TypeScript 引擎与落盘 SQLite、独立 Node 进程；上传入口为拒绝调用的替身，API受控，watcher仍为替身。没有生产代码或 schema 变更。
首次根对账定向6项通过；首批完整桌面20+76项通过。后续新增用例的最终回归进行中，不据此提前更新验收状态。

最终结果：17项新增场景全部通过；标准桌面106项通过；补强TC04-12后定向8项通过。没有发现需要新增生产修复的问题。已更新本轮results/remaining，旧结果不改写。

2026-09-27续跑：真实上传任务链已发现并修复两项核心问题，尚待最终回归确认：
1. keep_both成功后旧localMtime导致重放完成后反向上传覆盖云端，conflict-replay-repro.json记录上传次数1→2及原nodeId替换请求。修复在成功时提交冲突开始的localMtime，失败仍保留旧基线。
2. 全量对账错误清理status=conflict的本地保留副本；修复跳过此类保留内容，避免重复nodeId共用恢复操作而卡住。
conflict-task-repro.log：15项，11通过4失败。首次定向修复13通过2失败；剩余两项另查明夹具没有返回已上传副本，已补全响应。desktop-conflict-watcher.log未完成且进程消失，作中断证据；正在以desktop-conflict-watcher-rerun.log重新执行。
新增UserManageSecurityIntegrationTest及auth既有schema副本security-fixture.sql，尚未运行。包含真实事务、签名JWT、管理/角色/认证服务；Redis与配额依赖替身。

2026-09-28：Refresh JWT加入随机jti，修复真实Redis同秒双CAS成功。测试新增UserSecurityRedisIntegrationTest（39项通过）、WebSocketSecurityIntegrationTest（运行中），st-sync仅测试依赖H2与独立schema副本。桌面keep_both复用经过本地字节/云端当前详情验证的已完成副本，保留未完成原基线；生产API/schema均不变。
