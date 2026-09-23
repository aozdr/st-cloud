# 安全复核

- Task：`TASK-20260923-team-search-watch-security-review`
- Dispatch：`DISPATCH-20260923-TSW-SEC-A1`
- 复核修订：`tsw-code-r7`
- 执行者：`/root/tsw_security_review`
- 结论：**通过**；本次检查未发现未解决的高风险跨租户、当前 ACL、游标或通知失权泄漏路径。

## 范围与依据

只读检查可信身份与租户、团队搜索的即时权限及陈旧索引处理、游标签名、关注异步投递以及通知读取失权后的脱敏。未修改产品代码、State 或其他评审文档；未运行构建或测试。

## 检查结果

| 安全边界 | 结论与源文件依据 |
|---|---|
| 可信主体与租户 | `JwtAuthenticationFilter.java:49-87` 先验证并解析签名令牌，再从 claims 填充 `UserContext` 和 `TenantContext`。`SearchController.java:54-70` 的团队搜索只把上下文中的 tenant/user 传给服务；客户端只提供 space/folder 等搜索条件。`ReliableEventPublisher.java:92-128` 对索引/同步事件拒绝无可信租户快照，并在认证租户与节点租户冲突时拒绝。`FileWatchCaptureListener.java:48-66` 又按事件租户读取数据库节点并校验快照租户一致后才匹配订阅。 |
| 当前团队 ACL | `TeamSearchServiceImpl.java:107-117` 验证有效空间成员及搜索目录权限；`170-182` 对候选节点检查租户/空间、元数据、可见状态、目录范围和 `canView`；`205-253` 返回前再次确认成员与节点可见性。`TeamFileAccessPolicyImpl.java:51-110` 以显式 tenant/user/space 查询有效空间和未过期成员，验证节点到根的完整祖先链，并通过 `resolvePermissionsFreshForTenant` 从数据库重算权限，不依赖共享 ACL 缓存。 |
| 陈旧索引 | `TeamSearchServiceImpl.java:341-356` 只接受数据库中同 tenant/space 的节点；`359-380` 要求索引身份字段、节点类型、名称、路径及文件 MD5 与当前节点一致，缺少租户/空间/类型或 MD5 的旧文件文档会被排除；`230-255` 再以当前数据库节点组装名称、路径和结果，并重新核权。该路径把 ES 作为候选召回来源，不以索引字段代替最终授权。 |
| 游标 | `TeamSearchServiceImpl.java:555-610` 使用 HMAC-SHA256，`MessageDigest.isEqual` 比较签名，并把 tenant、user、space、folder、查询条件摘要、排序位置和到期时间绑定在令牌内；伪造、跨主体/条件复用及过期令牌均拒绝。`612-630` 未配置外部密钥时失败关闭，没有源码默认密钥。 |
| 关注投递 | `FileWatchDeliveryProcessor.java:58-103` 在短事务中锁定并校验投递，重查同租户有效用户和原 watch ID 代际；`103-146` 按当前节点和接收者权限核验，失权时抑制，数据库异常则向外抛出以进入重试。`FileWatchAccessService.java:51-80` 核验用户租户/状态，并对个人文件属主或团队 ACL、祖先链做当前数据库复核。 |
| 通知失权与目标 | `NotificationController.java:48-60, 91-94` 限制列表每页最多 100 条，并按当前 tenant/user 查询列表和目标。`FileWatchNotificationService.java:33-53, 62-82, 105-117` 对新关注通知每次读取及目标解析都重查节点和当前权限；不可见时清空内容及 ref/node/parent/space 目标字段，目标端点也不接受任意 URL。 |

## 现有验证证据

只读取了已有记录，未重跑：`.ai/docs/20260923-team-search-watch/testreport.md` 记录搜索 64 项通过（含本机临时 ES 集成测试 2 项）、关注/权限/投递/通知 40 项通过、核心 Outbox/schema 14 项通过。对应日志为 `search-tests-r2.log`、`watch-tests-r3.log`、`core-tests-r4.log`。

版本证据有一处限制：`testreport.md` 声明后端日志来自 r6，而 State/变更报告目标为 r7；虽然变更报告称租户补齐修正及 H2 回归已集成，现有报告不能独立确认这些后端日志均对应 r7。静态结论按当前工作树中 r7 实现给出，不把旧日志声称为 r7 复测结果。部署级 MySQL/ES/MQ/S3 联调不在本次证据中。

## 决策与风险

根据当前源代码，身份、租户、当前 ACL、索引复核、游标签名及通知失权处理均有 fail-closed 检查；未发现达到阻断级别的问题。剩余限制是 r7 后端测试版本对应关系和部署级联调证据，属于验证证据缺口，不是本次静态检查发现的授权绕过。

## State Delta 提案

建议主线程将 `SECURITY_REVIEW` 评估为 `pass`，证据为本文件及本 dispatch 结果；由主线程执行 State Evaluate。

## r8 主线程安全自检（2026-09-23）

用户要求当前模型自行 review。本节只检查 r7→r8 的权限缓存和游标变化，不能继承 r7 独立结论为 r8 的正式门禁。

请求内 `ReadContext` 只用于 ES 候选扫描，创建时按传入的 tenant/user/space 查询有效空间、成员和角色；节点与规则缓存只在该请求使用。最终返回前重新确认成员，批量读取当前节点，再对每条结果调用不读取共享缓存的 `canView`，可阻断扫描后撤权或节点变动。游标采用规范 Base64URL 编码要求及 HMAC 常量时间比较；篡改、主体/条件不匹配与过期均拒绝。前端对文件名和正文高亮经 `sanitizeHighlight`（DOMPurify 只允许 `em`、不允许属性）再写入 HTML。

本次静态自检未发现新增高/中等级越权或 XSS 问题。r8 的团队搜索定向 19 项、团队权限定向 13 项已通过；后端全量安全回归范围（排除会重建开发索引的 `ReindexIntegrationTest`）共 471 项、0 失败，MySQL schema 对比退出码 0。完整部署级联调仍未执行。用户授权当前模型完成剩余门禁，故本节可作为 r8 的单人安全自检证据，不能称为独立安全评审。
