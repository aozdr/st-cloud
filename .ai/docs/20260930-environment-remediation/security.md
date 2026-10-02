# env-code-r2 独立安全审查

执行者：`/root/security_review`；派发：`DISPATCH-env-security-01`；任务：`TASK-20260930-environment-security`。本报告仅提出 SECURITY_REVIEW 建议，未写 State、未执行 Evaluate。

## 背景与输入

审查旧开发环境整改的安全影响：成对令牌轮换与 IPC、租户临时作用域和异步任务、团队撤权及回收锁序、独立上传候选与 GC、数据库重试和开发密钥配置。输入为当前 TASK、env-design-r1、changereport.md、当前源码、current-source-manifest.json 和本任务验证日志。历史文档不产生数据库删除、生产发布或凭据提取授权。

Envelope 必填字段、身份三元组、role=reviewer、forbidSpawn/forbidGitMvn 与输出白名单均与当前协议一致；skillRefs 为 `-`。只读最小 State 快照确认 code=env-code-r2、状态仍为 running。独立读取 manifest 并计算本地 SHA256：780 项全部匹配，缺失/变更为 0。

## 分析

| 边界 | 当前源码核查与证据 | 结论 |
| --- | --- | --- |
| 认证轮换、退出与换服 | st-desktop/src/api-client.ts 用 generation/base/sessionId 限定刷新结果，主进程与 IPC 共用 refreshFlight；旧请求重放前再次核对 generation，setBaseUrl 使旧会话失效。st-web/src/auth-session.ts 使用同一个持久 JSON 保存 pair，IPC 回传再核对会话、服务器与修订；旧成功/失败不能清除或覆盖新会话。后端 AuthService.refreshToken 先校验签名、refresh 用途和安全版本，再进入令牌租户；Redis Lua 对旧 refresh 做 CAS。live-auth-final.log/live-redis-final.log 为真实后端/Redis两轮并发、旧刷新拒绝及新进程恢复的脱敏证据。 | 未发现本次新增会话串用或旧 refresh 覆盖当前值的重大缺陷。持久存储持续失败直到退出仍可能要求重新登录，报告已明确。 |
| IPC 暴露范围 | preload.ts:266 的可信页面检查统一包裹新增 getAuth/refreshAuth/clearAuth 及旧 setAuth；ipc-handlers.ts:81 起的新认证处理器调用同一认证协调器，没有新增任意文件/命令接口。main.ts:248 的 auth:changed 向应用窗口传递 pair；不写令牌日志。 | 新接口沿用既有桥接边界；没有完成 Electron 原生 IPC 实机验证。既有可信页面策略和 webSecurity:false 的风险见下方，不能声称桌面沙箱已加固。 |
| 公开入口及数据库租户作用域 | JwtAuthenticationFilter 的默认租户只覆盖列出的公开路径且仅在未设置租户时建立，不覆盖有效 JWT 租户；请求最终清理三类上下文。AuthService/UserSecurityService 的临时作用域均 finally 恢复原始 ID。MyMetaObjectHandler 仅在缺少租户值时解析默认租户。后台 TenantTaskRunner 只扫描启用租户，逐租户隔离异常并恢复原始 ID/mode；PRIVATE 保持单次执行。 | 本次没有扩展匿名分享到非默认租户，也没有把受保护匿名端点默认认证。AuthTenantContext 4、JwtPublicTenantScope 9 在 backend-full-r2.log 中均通过。 |
| 异步及 MQ 租户隔离 | TeamActivityHelper 捕获调用线程 ID/mode，工作线程内设置后才查用户/写活动，并显式赋活动 tenantId、finally 恢复。AuditAspect 同样传递请求快照。SYNC_CHANGE MQ 去重查询与插入一起进入事件租户作用域；本地异步监听器设置事件租户并恢复。遗留缺租户消息继续保留诊断兜底。 | 未发现本次明确租户事件继续使用上一消息租户的路径。TeamActivityTenant 4 已通过；MQ 的新增三项测试需最终串行构建汇总，当前日志不能当作已执行证据。 |
| 团队撤权与回收写入 | TeamServiceImpl.removeMember/leaveSpace/setExternalMember/setExternalConfig 均先锁 team_space，再使用 READ_COMMITTED 读取权限/成员。RecycleBinServiceImpl:296 先锁空间再锁节点；:281 锁后核对当前主体租户/owner 或实时有效空间管理员关系。TeamStorageMapper:32 明确 tenant、空间状态、成员有效性及外部配置；没有用系统管理员或节点上传者替代成员关系。 | 撤权与恢复/删除竞争按同一空间锁串行，回收权限读取不依赖缓存。真实 MySQL TeamRole 33 和 TeamRecycleMysql 4 在当前完整回归日志中通过。批量跨空间仍可能数据库死锁并回滚，不构成绕权或部分提交。 |
| 后台过期写入 | ExternalMemberExpireTask 按租户执行，每成员 RC 短事务内先锁空间，再按当前 ID/spaceId/memberType/原 expireAt/当前到期条件删除。FileLockExpireTask 按 ID、lockedBy、lockedAt、原 lockExpireAt 和当前到期条件 CAS 清除。 | 延期、转内部或重新锁定不再被旧扫描快照无条件清理。TeamExpirationTaskIntegrationTest 4 在当前日志中通过。 |
| 上传候选、引用与物理删除 | ObjectUploadCandidateService:49 在 PUT/GC 前强制事务外；候选使用租户/MD5/UUID 独立路径，READY→ADOPTED 与 DELETING 竞争由数据库状态写互斥。GC:145 的 JDBC 引用查询均显式 tenant_id，包含回收站节点和历史版本；未知 PUT 保留墓碑。FileObjectServiceImpl.acquireByPath 只做落库/认领；回收永久删除发布提交后物理删除 Outbox，不在业务事务内调用 S3。 | 没有发现本次上传失败立即删除规范对象、历史未登记对象被候选 GC 扫描或跨租户引用判断的重大缺陷。ObjectUploadCandidate 13、CanonicalObjectCleanupRace 1 在当前日志中通过。 |
| 桌面路径与保全修复 | sync-reconcile.ts 严格接受安全非负整数页数；重复斜杠只在同节点且同绝对路径时退出旧映射；根映射计入当前扫描。保全 ID 由节点、路径、基线和游标 SHA256 生成，同轮重试稳定；升级前 pending 清单必须匹配原路径。sync-recovery.ts 保留已有越界、同步根重叠、符号链接和已存在目标拒绝，使用同盘 rename，不覆盖旧副本。 | 当前修复没有放宽本地写入/移除范围，真实重复链路保留原件和旧清单，见 live-report.md。未声称对抗本地恶意进程的实时符号链接置换已得到完全证明。 |
| 迁移与配置 | 42/47 只补缺失索引，46 只补缺失 security_version，现有值不重置；首行均 SET NAMES utf8mb4。schema-after.log、schema-46-repeat.log 记录 PASS；脚本只比较共享核心列集与迁移登记，不代表全库逐字段安全审计。开发 profile 接受环境变量覆盖；基础 JWT 空主密钥拒绝启动，搜索 cursor secret 未配置拒绝初始化。 | 未发现本次新增 destructive DML 或生产自动生成/随机更换主密钥。默认 dev profile 和开发凭据仅能作为本地开发基线，不能据此认定生产部署安全。 |

## 决策

在当前整改范围内未确认尚未解决的重大安全缺陷，建议 SECURITY_REVIEW=pass，validatedRevision=env-code-r2。该结论来自独立源码与证据核查，不表示完整测试、Electron 实机、生产安全或最终运行版本已通过。

完整后端 `backend-full-r2.log` 当前仍为 BUILD FAILURE：TeamCopyDatabaseIntegrationTest 的 14 项在 Spring 测试上下文缺少 ObjectUploadCandidateService 时出错；已向主线程回报。上述定向安全用例通过与整个构建失败同时成立，最终 TEST_PASS 必须在修复后重新汇总。8080 真实认证/同步报告使用本任务 r1 immutable JAR，最终 r2 运行证据仍须主线程补齐。

## State Delta（仅 proposal）

`SECURITY_REVIEW / pass / by=/root/security_review / dispatchId=DISPATCH-env-security-01 / evidenceRef=.ai/runtime/results/DISPATCH-env-security-01.json / validatedRevision=env-code-r2`。没有修改 State，没有宣布 Goal 或整体验收完成。

## 风险与边界

1. 已有桌面 `main.ts:63 webSecurity:false`、sandbox:false，以及 preload 的 app/file 页面信任规则仍是基线风险；主进程新旧 IPC 没有逐次验证 senderFrame，auth 广播也未逐窗口核对当前 URL。这些条件在可信本地页面假设下工作；Electron 原生实机与 CORS/主进程来源门禁应另行设计验证，不应被本次测试表述为已解决。
2. 基础 application.yml 默认启用 dev，开发对象存储/主密钥默认值没有生产级来源保证。必须用部署配置明确选择和注入生产环境，当前用户授权未涉及生产发布。
3. MyBatisPlusConfig 的全局 RBAC/system 表豁免及 TenantContext 缺失诊断后默认1沿用既有契约；本次只修已确认的入口与异步遗漏，并未完成全仓多租户模型重设计。
4. Docker 健康探针配置尚未应用到重建容器；Broker 容器层数据需先迁移保存。历史退出137/OOM根因没有新证据。S3 签名 HEAD、随机 PUT/GET 的 SHA256 在 s3-current-20261001.log 通过，但不能推导全部图片预览接口正常。

## 下一步与变更影响

主线程核对本结果身份、白名单和当前修订后 Evaluate；继续完成失败测试夹具修复、全量串行构建和 r2 运行证明。若本次审查的产品源码继续变化，重新核对哈希并按当前协议重审。此 child 只新增本报告及独立结果，没有运行 Git/Maven/安装/真实权限修改/凭据提取、没有创建 child 或写产品。
