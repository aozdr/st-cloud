# O02 路径核查与局部修复（env-code-r1）

## 背景与输入

本结果来自 TASK-20260930-environment-tenant / DISPATCH-env-tenant-01。冻结文档 O02 只作为问题线索；当前 design、requirement、testcases 与主线程明确的租户扫描决定作为本轮输入。子 Agent 未修改 State、数据库或已合并候选/编辑器/上传源码，未运行 Maven/Git，未触发真实清理任务。

## 分析：源码事实

| 入口/任务 | 修改前事实 | 本次处理 |
| --- | --- | --- |
| POST /api/auth/login | AuthService 首次 sys_user.selectOne 没有设置上下文，经租户拦截器告警并解析为 1；后续设置用户租户但直接服务调用不恢复。 | 无上下文时显式选择原有默认 1；保留已设置上下文的原租户选择；成功/失败恢复原始值。 |
| POST /api/auth/register | registerUser 首次用户名计数无上下文；找到默认租户后设置其 ID，服务退出未恢复。 | 同登录，显式默认入口并在 finally 恢复原始值。默认角色、租户状态、事务与响应规则不变。 |
| POST /api/auth/refresh | isCurrent 临时切换验证租户后恢复原上下文；后续 selectById 无上下文时又落入租户 1，非 1 租户可因此查不到用户。 | 验签、refresh 用途和安全版本都通过后，在已验证声明租户下完成后续查询；finally 恢复原值。Redis CAS 与二次安全检查不变。 |
| JWT 普通认证 | JwtAuthenticationFilter 最终清理 TenantContext、UserContext 和 Spring Security；UserSecurityService 快照采用原始 null。 | 保留现有修复，不降级真实缺上下文告警。 |
| 回收站自动清理 | findExpiredRecycleRoots 经租户拦截器；调度任务没有枚举租户，SAAS 无上下文时实际只扫描租户 1。 | TenantScanMapper 只读查询未删除且启用(status=1)的 sys_tenant；TenantTaskRunner 在 SAAS 逐租户显式设置，异常隔离，恢复原始 ID/模式。禁用租户保留数据。PRIVATE 按显式模式或配置默认模式只执行一次。 |
| Outbox 重试/清理 | event_log 明确位于 MyBatisPlusConfig IGNORE_TABLES 中。 | 无需把全局任务改为租户 1，保持系统表扫描。 |
| 新 object_upload_candidate GC | 直接 JDBC 扫描，引用保护读取都显式携带候选 tenant_id。 | 无 TenantContext 依赖；按 exclude 不修改。 |
| 旧 file_orphan_candidate GC | 旧 mapper selectDue 与随后引用查询仍走租户拦截器，调度入口无租户设置。 | 超出当前子任务修改白名单；已向主线程提供 TenantTaskRunner 接入接口，由主线程处理。 |
| 异步审计 | 原 AuditAspect 直接在线程池 insert；MyMetaObjectHandler 原实现无条件读取 TenantContext，即使实体有 tenantId 仍告警。 | 超出本子任务写入白名单，已提供源码事实；主线程单独处理。 |
| 匿名 /api/share/access/** | validateShareAccess 使用普通 FileShareMapper.selectOne(shareCode)，mapper 无租户豁免，file_share 不在 IGNORE_TABLES；随后访问 FileNode。 | 主线程确认这是当前单租户部署的默认 1 兼容路径，将在公开请求入口显式设 1；本 child 不修改 st-share 或 JwtAuthenticationFilter。非 1 租户匿名分享能力列为本次未扩展边界。 |

公开 ping、captcha 与文档/健康路由不因“公开”标签自动赋默认租户。OnlyOffice callback 和 WebSocket 各有独立的验签/会话入口；本子任务没有改动或声称完整验证这些排除范围。

以上是当前源码和查询路径事实。没有重启应用、采集当前运行请求日志或运行真实清理，所以“运行环境告警已经全部消失”不是本结果结论。

## 决策与变更影响

- 新辅助接口：`com.stcloud.core.task.TenantTaskRunner.runForEachTenant(String taskName, Runnable tenantTask)`，线程局部状态在每租户及整轮 finally 处理；读取配置 `stcloud.tenant.mode`，无原始模式时使用配置。
- 新 `TenantContext.getTenantModeOrNull()` 只用于保存原始快照；`setTenantMode(null)` 现在移除 ThreadLocal，避免留下空模式条目。`getTenantId()` 的真正遗漏告警保持原样。
- 未更改 HTTP API、角色权限、配额、状态机或删除规则；回收站 SAAS 扫描从隐式租户 1 扩为已启用租户。禁用/已删除租户不自动清理。

## 验证证据与下一步

已执行静态路径核查与限定变更文件冲突标记扫描：上述修改文件不存在 Git 冲突标记。未运行 Maven，测试只编写并交给主线程串行执行：

- `AuthTenantContextIntegrationTest`：真实 H2/签名验证默认登录成功与失败不告警、真实遗漏仍告警；注册成功/重复失败恢复 null；非默认租户 refresh 跨请求成功与失败恢复上下文；错误用途不切换。
- `TenantTaskRunnerTest`：原租户 null/99 的两租户异常隔离和下一轮线程复用；目录失败保持上下文；配置 PRIVATE 单次执行；PRIVATE 失败恢复状态。
- `TenantRecycleScanIntegrationTest`：真实目录 SQL 与租户拦截器覆盖两个启用租户，排除禁用/已删除租户，各自只看到自己的回收站根节点；只读扫描，四条数据均保留。
- `RecycleBinPurgeTenantTest`：调度入口在租户 1 扫描失败后继续租户 2，执行受控清理并释放 Redis 锁，无真实删除。

## State Delta（proposal）与风险

建议 IMPLEMENTED 在本子任务范围内通过，证据为本文件和独立结果；这不是 State 修改或 O02 总体验收。真实运行、Maven 测试、主线程审计/旧 GC 接入及公开请求入口仍需主线程整合与评估。

多租户清理扩大扫描覆盖面是主线程确认的行为变化；PRIVATE 配置覆盖有测试用例但尚未执行。匿名分享保留默认 1 契约，由主线程显式设置允许的默认上下文，真正未设置上下文的诊断保持。
