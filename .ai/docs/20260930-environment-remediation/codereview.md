# 当前代码审查索引 env-code-r2

主线程索引：当前独立复审见 [codereview-r2.md](codereview-r2.md) 和 `.ai/runtime/results/DISPATCH-env-code-review-02.json`，CODE_REVIEW=pass 已由主线程 Evaluate。首轮 CR01–CR03 的团队锁/租户传播问题修复并独立复审通过。本索引不新增审查结论，最终测试和运行范围见 testreport.md、acceptance.md。

首轮失败原文另以字节相同副本保留在 [codereview-r1.md](codereview-r1.md)，下面也保留原文。历史 fail 仅对应 env-code-r1，不代表当前复审结论。

---

# 独立代码审查：env-code-r1

## 背景

本次按 DISPATCH-env-code-review-01 独立只读审查当前整改实现。结论：CODE_REVIEW 建议 fail，存在下列未关闭的租户传播和权限锁覆盖缺口。没有执行 Maven、Git 操作、数据库写入或产品修改；没有写 Loop State、判定整体 Goal 或 ACCEPT。

## 输入

TASK-20260930-environment-code-review、当前 Dispatch schema/protocol、design.md、changereport.md、testcases.md、requirement.md；最小 State 中 revision.code=env-code-r1、CODE_REVIEW=pending。skillRefs 为 `-`。遗留文档仅作事实线索。

读取了认证主进程/renderer/store/IPC、上传/文本/解压/编辑器提交边界、独立候选 GC/FileObject、回收服务/团队权限 mapper、TeamService 成员变更、租户 runner/定时任务/审计、47/42/46 迁移及配置。核对当前 backend-runtime-build-pass.log（81 项、零失败/错误/跳过、BUILD SUCCESS）、team-mysql-new-final.log（9 项、零失败/错误/跳过）、schema-after.log、mysql-46.log、桌面和 Web 专项报告与 web-final-results.json。大构建没有重复运行。

## 分析与需修复项

### CR01 [P1] 团队回收共享空间锁未覆盖其它使成员失效的写路径

源码事实：RecycleBinServiceImpl.java:296–304 取得空间→节点锁，:289 按当前成员、到期时间及外部开关决定管理员权限。TeamServiceImpl.java:308 removeMember、:457 leaveSpace、:1039 setExternalMember、:1061 setExternalConfig 均未调用 lockRoleWrites，也未使用 READ_COMMITTED；它们分别修改这次查询依赖的 deleted、expire_at/member_type、allow_external。TeamActivityHelper 的活动日志异步插入，相关表也没有空间外键，不能视为隐式取得并保持空间锁。updateMemberRole 的已有显式空间锁不能代表其它路径。

静态触发：回收操作持有空间锁并通过管理员查询后，在后续写入/配额/子树操作上等待；另一请求移除该管理员、设置其到期或关闭外部协作，可以绕开该空间锁并先提交，回收仍使用先前通过的授权继续写入。当前 4 项并发专项仅按 updateMemberRole 的锁序直接修改 role，未覆盖这些真实入口，无法证明完整的撤权串行契约。

最小修复：上述四个入口在任何权限/成员/配置读取前调用同一 lockRoleWrites，事务使用 RC；补真实成员移除/外部失效入口的交错证据。语义是由空间锁决定先后：回收先锁并决策则撤权等待；撤权先提交则回收锁后拒绝。不是要求撤销已经完成的操作。transferOwnership/deleteSpace 修改 team_space 本身会取得空间写锁，本项没有要求无关扩展。

本项为代码锁关系推导，child 没有声称现场复现。

### CR02 [P1] 团队活动日志仍在异步线程落到默认租户

源码事实：TeamActivityHelper.java:49 只捕获 userId；:50 executor.execute 后不设 TenantContext；:57 sysUserMapper.selectById 在 worker 默认租户 1 下执行；:68 activity 没有 tenantId，MyMetaObjectHandler 会用 TenantContext.getTenantId() 填 1。MyBatisPlusConfig 未将 sys_user/team_activity 排除租户隔离。

触发与后果：tenant=71 的正常团队操作调用 helper，异步任务会查询 tenant=1 用户并把 team_activity 写成 tenant=1，即使 spaceId 属于 71；71 的活动列表遗漏记录，1 的持久审计数据被混入。默认租户的正常操作也继续产生本轮 O02 要解决的兜底告警。这与 AuditAspect 已修复的同类传播缺口相同，不能把 async 线程没有上下文解释为允许默认租户。

最小修复：提交异步任务前捕获实际 tenantId 和 mode，给 activity 显式租户；worker 的用户查询和插入在对应作用域执行，finally 精确恢复原始 ID/mode。保留真实漏设调用的诊断，补两个非默认租户及复用线程/异常路径测试。

本项根据确定的执行分支与租户 SQL/填充机制判断，没有运行实际异步写库。

### CR03 [P2] 两个定时清理入口仍隐式只扫描租户 1，且旧快照可清掉新状态

源码事实：ExternalMemberExpireTask.java:25 和 FileLockExpireTask.java:26 直接在调度线程使用租户隔离 mapper，没有上下文/runner。它们分别在扫描后的循环中按 ID 无条件 delete、清空 lockedBy/lockedAt/lockExpireAt。

触发与后果：调度线程无上下文时只处理默认租户并产生 O02 告警，其它启用租户没有到期清理；扫描已过期成员后另一请求把到期时间延长，旧任务仍删除该成员；扫描旧过期文件锁后重新加锁，旧任务仍清掉新锁。后两种属于可以从分离的 SELECT 与无条件写入推导的状态竞态。

最小修复：复用当前 TenantTaskRunner 逐启用租户执行并精确清理。写入加当前到期条件（成员 member_type/expire_at；文件 lockedBy/lockExpireAt，必要时快照匹配），避免按旧扫描实体无条件写。成员到期清理若进入同一权限串行契约，应在事务中遵循空间→成员的锁序并锁后重判。补跨租户、延期/重新加锁及异常线程复用证据。

## 已核查的边界

- 主进程和浏览器刷新共用代次/singleflight；完整 token 对在同一次更新后通知/持久化；旧 refresh 成功/失败及迟到 401 都有原会话核对。临时网络/5xx 保留恢复凭据，明确业务认证拒绝清理。IPC 兼容旧 setAuth 两参；真实 Electron 运行/存储验收须由 live/体验证据单独证明，受控文件适配不能替代。
- 新上传物理路径独立 UUID；READY→ADOPTED 与 DELETING 的条件更新互斥，业务事务失败后的候选放弃是独立事务；去重败者延迟 GC。GC 检查对象、节点（包括回收态）、历史版本及活动会话引用，未知迟到 PUT 保留 DELETING 墓碑重试。抽查四条外部写入都在独立提交前，未在本次整合中发现直接失败删除已采用对象的路径。
- 42/47 索引 DDL 检查索引存在性，46 缺列分支与原值保留不执行重置；本轮 schema-after 和两个 46 对比记录为通过。compare-schema 的 PASS 仅证明所比较的表/列名及版本文件登记，不应扩大为全部字段类型/索引一致。实际 BIGINT/默认值验证有专项记录。
- 分享胶卷错误绑定 src 的请求身份，旧回调先比较当前身份再写状态；来源/updatedAt 更新后形成新身份。真实 Chrome 报告清楚说明受控旧 React handler 的边界，17 项与量化对比度证据没有冒充真实分享后端。
- Docker 使用实装 digest；旧 Broker 无持久卷及禁止直接重建的事实已写配置，当前容器没有因新增 healthcheck 被重建。新机器 ES 本地镜像需要先构建/核验，配置也有说明；历史 137/OOM 根因未知，不能宣称已定位。
- rg 对本轮相关产品源目录未找到遗留合并标记。本 child 未运行 Git，index 无 unmerged 的结论仍须主线程已有验证负责。

## 决策

CR01–CR03 应定向修复与验证后用新 Dispatch attempt 重新独立审查，当前不提出 pass。证据覆盖不足本身没有被当作已复现产品缺陷；上述问题均有具体当前源码触发。live 全链路/浏览器/Electron 尚在并行验证，未作为本 child 的完成事实。

## State Delta（仅 proposal）

CODE_REVIEW=fail，by=/root/code_review，dispatchId=DISPATCH-env-code-review-01，validatedRevision=env-code-r1；evidenceRef=.ai/runtime/results/DISPATCH-env-code-review-01.json。主线程负责 Evaluate，child 没有写 State。

## 风险

未做现场数据库交错复现；CR01 属于缺共享锁的静态风险，CR02/CR03 来自确定代码分支和 SQL 机制。这里只核查与需求相连的热点，不表示全仓所有既有边界都已安全审计。当前 readonly review 的结果不能覆盖后续产品修改。

## 下一步

主线程扩展必要精确 TASK scope，按最小修复串行补验证；将新实现 revision/证据传入新 attempt。无需用户裁决常规锁序和线程作用域细节。

## 变更影响

本 child 仅写 codereview.md 与自己的独立结果，不修改产品、配置、测试、历史证据或 State；没有执行子派发。
