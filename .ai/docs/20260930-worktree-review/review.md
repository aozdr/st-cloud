# 未提交代码 Review

日期：2026-09-30。基线：`912996e1b8f34904b792f29ceacab2023844e038`（HEAD）。范围：72 个已跟踪修改文件及未跟踪新增源码、迁移、测试，暂存区为空。沿用用户的单线程要求，由主线程分别检查 Standards 和 Spec。

结论：当前不能按“整改全部通过”验收。Standards 发现 1 项 P2；Spec 发现 5 项 P1、3 项 P2。以下均为尚未修复的问题。

本轮没有修改业务源码。独立比对上一批 675 个源码/测试哈希，变化为 0；仅新增本目录的审查报告和诊断证据。没有提交、推送或部署。

## Standards

### S1 [P2] 新字段迁移不能重复执行

位置：[45_user_security_version.sql](E:/code/st-cloud/docker/mysql/init/45_user_security_version.sql:4)。标准：[开发约定](E:/code/st-cloud/.ai/knowledge/conventions.md:139) 明确要求 DDL 幂等。

脚本直接 ADD COLUMN，没有检查字段是否已存在。首次执行成功后再执行会报 1060 Duplicate column，阻断迁移重试。隔离 MySQL 中原脚本第一次退出 0、第二次退出 1，见 [migration-probe.log](E:/code/st-cloud/.ai/docs/20260930-worktree-review/migration-probe.log)。应以 MySQL 支持的元数据检查保护新增字段；不能直接照搬不支持的 ADD COLUMN IF NOT EXISTS 语法。

## Spec

### F1 [P1] 注册可签发带已撤销权限的有效会话

位置：[AuthService.java](E:/code/st-cloud/st-auth/src/main/java/com/stcloud/auth/service/AuthService.java:102)。要求：CR-01 R01-3；CR-01 design 第 4、5 节。

register 的普通查询先建立事务快照，再等待租户锁。MySQL 默认 REPEATABLE READ 下，管理员在等待期间撤销默认角色权限并提交后，注册仍按旧快照分配角色、读取旧权限。新用户在撤权时尚不存在，不会被递增版本，随后以 0 签发。UserSecurityService 只核对用户/租户状态和版本，无法识别此旧权限。应落实已确认的锁后当前决策状态、提交后签发快照边界。

隔离 MySQL 的相同查询交错得到 `registration_snapshot_permissions=file:delete`、当前有效权限数 0、新用户版本 0。由此交错和现有校验代码可推出上述有效会话问题；此证据是最小 SQL 复现，不冒充完整 AuthService/MySQL/JWT 端到端测试。

### F2 [P1] 空间锁不能阻止角色留下悬空引用

位置：[TeamServiceImpl.java](E:/code/st-cloud/st-team/src/main/java/com/stcloud/team/service/impl/TeamServiceImpl.java:1009)。要求：CR-02 R02-6。

checkPermission 在空间 FOR UPDATE 之前执行普通查询。等待另一个邀请/成员写事务提交后，锁后的 selectById/selectCount 仍读旧快照，漏掉刚提交的引用，随后删除角色。反向顺序也可用旧角色快照继续创建引用。仓库未设置 READ COMMITTED。应保证锁后角色和引用判断使用当前状态，而非仅增加锁。

真实 MySQL 8、REPEATABLE READ、两个连接的最小 SQL 复现：锁后引用计数 0，最终已提交悬空引用数 1。现有 H2 并发测试未验证此隔离级别行为。

F1/F2 证据：[mysql-probes.cjs](E:/code/st-cloud/.ai/docs/20260930-worktree-review/mysql-probes.cjs)、[mysql-probes.log](E:/code/st-cloud/.ai/docs/20260930-worktree-review/mysql-probes.log)。

### F3 [P1] 正常云端删除会让桌面增量页永久失败

位置：[sync-engine.ts](E:/code/st-cloud/st-desktop/src/sync-engine.ts:641)。要求：CR-04 R04-1、R04-5。

处理 DELETE 前总会读 /file/{id}，客户端只把 2001/2008 当作不可用节点。但是现有 getNodeDetail 调用 validateAccessible，回收态返回业务码 403。普通云端删除因此抛出“云端节点详情不可用”，无法进入本地保全和清理，后续事件也被同一页阻塞。需要能区分回收态与真实失权的状态契约。

生产 FileService/真实 H2 Mapper 最小启动验证：正常节点详情成功，改成回收态后返回 BusinessException 403，见 [delete-detail-probe.log](E:/code/st-cloud/.ai/docs/20260930-worktree-review/delete-detail-probe.log)。实际 TypeScript 引擎消费该响应两轮，cursor 始终为 1。既有桌面删除夹具返回的是 2001。

### F4 [P1] MOVE/RENAME 补下载仍校验历史内容

位置：[sync-engine.ts](E:/code/st-cloud/st-desktop/src/sync-engine.ts:451)。要求：CR-04 R04-5。

仅 CREATE/UPDATE 会用当前节点详情替换 size/md5。客户端离线期间先移动、再更新内容，且本地源缺失或被保全时，MOVE 补下载取得当前内容，却仍用历史日志的大小/哈希校验。相同事件重试永远不匹配，不能消费其后的 UPDATE。应在需要补下载的分支使用当前内容快照，并保留普通重命名时的本地旧基线语义。

实际引擎和下载代码、真实临时文件、受控流复现：历史长度 1、当前长度 2，连续两轮“下载长度不匹配”，cursor 保持 1，目标文件未落地。

### F5 [P1] 升级对账在加载排除规则前移动被排除文件

位置：[sync-reconcile.ts](E:/code/st-cloud/st-desktop/src/sync/sync-reconcile.ts:30)，关联 [sync-manager.ts](E:/code/st-cloud/st-desktop/src/sync-manager.ts:70)。要求：CR-04 R04-8、A04-4。

startSync 先调用 engine.start，再获取服务器 exclusions。新增升级全量清理因而以空排除列表执行。已有排除路径若云端已移出根，仍被按历史残留搬入恢复区并删除映射；其他排除目录也可能被下载/冲突处理。应在首次扫描和对账前取得并应用排除规则。

生产 manager/engine/reconcile/recovery 代码、真实临时文件复现：/excluded.txt 原件从同步根被移走；请求顺序为 file/list → 根详情 → 历史节点详情 → delta → exclusions。恢复副本保留原字节，但已违反排除路径不得处理的边界。

### F6 [P2] 文件移动重放提前退出，遗留旧节点映射

位置：[sync-engine.ts](E:/code/st-cloud/st-desktop/src/sync-engine.ts:553)。要求：CR-04 R04-3、R04-5。

目标下载及 upsert 已持久化、旧映射删除前退出进程后，重放发现源缺失、目标属于同节点，直接 break。旧映射永久保留而页面被确认。用户以后在旧路径创建文件时会误用旧 nodeId 更新已移动的云端节点。应在确认目标成功落地后完成同节点旧映射清理。

实际引擎从此持久化形态运行：cursor 推进到 2，但 /old.txt 和 /new.txt 两个映射同时留下。

F3/F4/F6 的客户端证据：[desktop-probes.cjs](E:/code/st-cloud/.ai/docs/20260930-worktree-review/desktop-probes.cjs)、[desktop-probes.log](E:/code/st-cloud/.ai/docs/20260930-worktree-review/desktop-probes.log)。网络为受控输入，未声称 Electron/HTTP 端到端。

### F7 [P2] 首次对账失败后，启动缓存阻止再次启动

位置：[sync-engine.ts](E:/code/st-cloud/st-desktop/src/sync-engine.ts:94)。要求：CR-04 R04-5。

start 在对账失败时正常返回，并把 running 设为 false；watcher 和定时器尚未启动。manager 将该实例保留在 engines，之后 startSync 的 has(rootId) 守卫直接返回。暂时断网恢复后既没有定时重试，再次启动也不会重新对账。应让失败启动触发既有失败清理，或提供真正的全量重试调度。

生产 manager/engine 复现：第一轮失败、恢复后再调 startSync，reconciliations 仍为 1，running=false、watcherStarts=0、timers=0。F5/F7 证据：[manager-probes.log](E:/code/st-cloud/.ai/docs/20260930-worktree-review/manager-probes.log)。

### F8 [P2] 分享胶卷加载失败后没有降级图标

位置：[PreviewModal.tsx](E:/code/st-cloud/st-web/src/components/preview/PreviewModal.tsx:540)。要求：[CR-07 R07-4](E:/code/st-cloud/.ai/docs/20260924-code-review-design/CR-07/requirement.md:18)。

受支持格式缩略图遇到超限/损坏/网络失败时，新 onError 仅隐藏 img。此分支没有 FileThumbnail 的底层图标，按钮成为空白；只有 WebP/SVG 分支渲染图标。应为失败节点记录降级状态并渲染同一通用图片图标，保留名称和点击能力。此项证据为 JSX 和错误处理代码检查，未重跑浏览器。

## 验证边界

- 本次新增验证集中于上述失败路径，没有重跑原 136 项全量验收。此前通过报告仍代表已执行场景，不能覆盖本次发现的协议/隔离级别差异。
- 诊断中也复现了桌面客户端未保存轮换 refresh token 的问题；HEAD 的后端已轮换 refresh token、客户端也已遗漏保存，因此按既有问题排除，不计入本次 findings。
- MySQL 使用本次独有、无网络、无宿主目录挂载的临时容器；已按完整 ID 和任务标签核对后停止并自动移除，见 [resource-cleanup.log](E:/code/st-cloud/.ai/docs/20260930-worktree-review/resource-cleanup.log)。未操作共享数据库。
- Java 诊断使用当前编译产物和真实测试 Mapper；Node 诊断转译当前 TypeScript 源码，使用真实临时文件和受控 API。诊断临时文件均已清理。
- 源码未变的证据：[source-verification.json](E:/code/st-cloud/.ai/docs/20260930-worktree-review/source-verification.json)。原实现类 Loop State 和验收记录未被本轮只读 Review 改写。
