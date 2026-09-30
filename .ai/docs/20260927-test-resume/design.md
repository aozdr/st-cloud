# 续接设计（定版）
沿用用户批准的 ../20260925-test-remaining/resolution-plan.md 与 execution-plan.md，不重新设计业务。当前使用者和场景仍为鉴权、团队、同步、分享、搜索及预览验收。
现状核对：当前逐项状态与交接一致，保留所有未提交改动。旧记录仅作审计，不直接沿用其 State 完成标记。
首批范围：扩展现有实际 SyncEngine、SQLite 与独立进程夹具，验证 reconcileRequired 下根改名/移动后目录恢复，详情/列举失败保留 cursor，重启后完成；根节点标识不变，根 B 快照不变。受控 API 不声称真实 HTTP/租户/监听链通过。
异常规则：网络或元数据故障必须留下 error，不能伪造删除或成功页。新旧内容按已有原子保全设计处理，不修改已确认策略。
数据/API：本批仅测试，无 schema 或 API 变化。发现核心缺陷先留复现，再补具体最小修复方案。
风险：历史通过证据不是当前全量执行；仅对新增完整证据提升状态。跨服务、浏览器和 watcher 仍按原要求执行。
验证：定向 Node 测试，随后标准桌面 npm test；结果和日志落在本目录。没有未决范围、兼容性或风险裁决。

冲突任务链补充：加载真实 upload-manager、task-scheduler、transfer-tasks 与同步上传/冲突模块，使用SQLite生产任务DDL，API仅控制初始化、分片、合并及流。失败/超时分别在初始化/合并阶段注入；仅上传等待模块采用受控时钟，避免实际等待10分钟。验证keep_both、local_wins/latest_wins、全量对账及重复页的基线/任务/内容/cursor。真实watcher后续另验，不将API替身冒充服务器。

实际任务链复现后的最小修复决策：keep_both成功时提交冲突开始时的localMtime（不是异步结束时的mtime），避免正常扫描把已保留本地版反向覆盖云端，同时后续真实编辑仍可被检测；失败不提交任何原文件基线。全量清理跳过status=conflict的保留副本，它们不是云端路径的历史镜像，不能按nodeId复用的恢复操作清理。复现：conflict-task-repro.log、conflict-replay-repro.json。无API/schema变化。

管理写路径夹具：沿用auth的既有H2 schema（复制为admin独立测试资源，不修改业务schema）。加载真实管理服务、角色服务、AuthService/JwtUtils、MyBatis租户链和事务；Redis仅为有状态替身，CloudStorage仅隔离配额外部依赖。用锁前屏障复现读旧实体后并发改密/禁用；在安全版本UPDATE成功后抛异常验证整事务回滚。此批不冒充MySQL/Redis多实例证据。

TC04-28副本计数复现：同一内容冲突连续三轮部分失败，已成功的本地云端版或云端本地版各新增3份，不能满足重放幂等。最小修复复用既有sync_state中同根、同文件名且哈希相同的已完成副本：本地副本重新校验实际字节，云端副本读取当前详情核对状态/哈希；无有效证据不跳过。成功原件基线仍仅在两边保全后提交，不增加schema、API或清理框架。云端副本下载复用已有原子下载校验路径，避免不完整流被当作已完成副本。

真实Redis阶段：已创建仅127.0.0.1:60327可访问、无宿主挂载的临时Redis7容器（redis-resource.json）。沿用真实H2管理/认证链，RedisTemplate使用真实Lettuce连接，测试在CAS执行和两次版本查询返回处加线程屏障；验证同Refresh并发仅一成功、首检后撤权拒绝、末检后撤权只签发旧版本或CAS拒绝。Redis仅属于本TASK，结束后按名称与标签核对清理。

TC01-11真实Redis复现：同秒Refresh签发无唯一标识，生成值与旧值相同，CAS两次均成功。最小修复在Refresh JWT加入随机jti（既有UUID依赖），不改变claims校验或旧令牌接收规则；后续刷新轮换到唯一新值。证据auth-real-redis.log及24项报告，另两处登录交错失败属于Mapper spy抽象方法调用错误，单独修正夹具。

WS验收使用st-sync独立H2夹具、随机本地HTTP端口、实际Tomcat握手/Java WebSocket客户端帧及生产Handler；仅新增测试作用域H2依赖。使用短期真实签名令牌跨过到期时间，分别触发接收/sendToUser/sendToTenantUser，核对关闭码、无业务消息与注册表计数。

TC02-18浏览器复现：成员角色修改被403拒绝后，下拉框立即恢复已保存值，丢失待重试选择（browser-team-actions-4.log；selection.json已被修复后重跑更新）。最小修复仅TeamSpacePage：每成员独立保存待提交role/pending/error，失败保留选择并显示重试；已保存角色徽标只在成功后更新，提交中禁用控件避免并发覆盖。沿用现有样式，不重设计；无API变更。UI验收范围扩展为此错误恢复状态与浏览器角色/ACL验证。

TC02-15实际复现：关闭外部协作后既有memberType=1成员仍通过团队查看/上传与搜索显式主体策略。证据team-access-revocation-repro.log（5组仅external-off失败，5个授权断言失败）。最小修复在TeamServiceImpl.requireActiveMembership与TeamFileAccessPolicyImpl.findActiveMember中读取当前tenant/space的team_external_config，仅allowExternal=1放行外部成员；缺省关闭与getExternalConfig一致。内部成员不受影响，不变更schema/API。搜索使用真实服务+真实H2授权，ES仅受控候选。

复制验收补强：TeamCopyDatabaseIntegrationTest 使用真实 Controller、TeamService/FileService、FileObjectService 与 ReliableEventPublisher，测试专用 H2 增补逐字复用 core 的 file_object DDL。物理存储为替身，拒绝时比较节点/对象引用/空间用量/Outbox/活动调用快照；成功时逐节点比较索引与同步事件快照，不声称 MQ/ES 消费已执行。13 项通过 team-copy-db-complete.log。

搜索进程验收：SearchInstanceWorker 在独立 JVM 中通过 Spring 注入真实环境变量配置，使用生产游标生成/验证/分页代码；固定六条 ES/元数据候选用于可重复的三页交替访问。子进程 stdin/stdout 为测试协议，不宣称 HTTP 服务端端到端。

浏览器预览验收对照CR-07已批准uispec：胶卷图标按钮无title/aria-label，降级后无法获知对应文件；主图失败态缺少规格中的一次显式重试。最小修复PreviewModal添加文件名title/aria-label，并按fileId+versionId记录一次手动重试，重新走既有授权API，不自动改用原图、不改变布局或权限。浏览器验证失败重试最多一次、切换与导航及SVG仅img展示。

## MOVE/RENAME 源缺失后的状态清理
TC04-14 真实进程测试复现：源文件不存在但旧状态仍在，目标下载成功后未删除旧映射。仅在成功下载后删除同节点旧路径状态；下载失败保留映射及游标，允许重放。无 API/数据库契约变化。新增 MOVE/RENAME 成功与失败恢复两组断言。未知身份旧文件原样保留且不绑定 N，沿用已确认保护规则。
