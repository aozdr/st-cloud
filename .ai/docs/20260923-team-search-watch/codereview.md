# 代码复核

- Task：`TASK-20260923-team-search-watch-code-review`
- Dispatch：`DISPATCH-20260923-TSW-CODE-A1`
- Revision：`tsw-code-r7`
- 执行者：`/root/tsw_code_review`

## 背景与输入

本次独立复核团队全文搜索、文件关注提醒与通知实现，按当前需求、设计、测试用例和变更报告核对权限、索引一致性、分页、事务、幂等及异常处理。只读当前代码和已有验证记录，没有运行构建或测试。State 中登记的代码修订为 `tsw-code-r7`；结论仅适用于该修订。

输入包括本 TASK、需求与设计、测试用例、变更报告、当前 State 的 revision 快照，以及搜索、关注、通知、Outbox 相关源码和已有测试记录。

## 结论

**CODE_REVIEW：fail（有一项未解决的中等级问题）。** 搜索与关注通知主路径能看到租户/空间约束、当前 ACL 复核、签名游标、队列事务幂等及历史通知脱敏实现；但团队搜索在候选扫描与最终复核时重复执行逐节点 ACL 数据库查询，缺少设计要求的请求级祖先和权限缓存。宽范围搜索可造成显著数据库查询放大，因此不建议当前修订通过。

## 发现

### CR-01 [P2 / 中] 团队搜索的逐候选 ACL 检查产生未缓存的数据库查询放大

- **代码证据：** `st-search/src/main/java/com/stcloud/search/service/impl/TeamSearchServiceImpl.java:165-184` 先按最多 100 个候选批量取节点，但随后在候选循环中逐条调用 `accessPolicy.canView(...)`；指定文件夹时还逐条运行 `isDescendantOrSelf(...)`。`recheckVisible` 又在 `TeamSearchServiceImpl.java:230-254` 对候选再次逐条做同样的范围与 ACL 复核；该复核既出现在分页 lookahead（第 139-143 行），也在响应构造前再次执行（第 204-209 行）。
- **查询放大依据：** `st-team/src/main/java/com/stcloud/team/service/TeamFileAccessPolicyImpl.java:62-111` 中，每次 `canView` 都重新检查空间和成员、读取节点及祖先链、再次读取成员角色，并调用 fresh 权限解析。其 `findNode` 查询位于第 146-159 行；最多遍历 20 层。该搜索允许扫描最多 2000 个 ES 候选（`TeamSearchServiceImpl.java:70,151-155`），因此低授权命中率时，单次请求可能对大量候选重复发起多条 SQL；候选恰好集中在首个批次时，lookahead 与最终复核还会重复核权。实现中没有请求级缓存。
- **设计不符：** `design.md:95` 明确要求批量读取节点并“维护本请求祖先与权限缓存”。当前实现只批量读取节点，没有维护该缓存。共享权限缓存不应被用于替代实时授权；请求级缓存仍可满足设计要求，并在最终返回复核时重新核权。
- **影响：** 搜索结果权限仍按当前数据复核，但一次搜索的数据库工作量随候选数和祖先深度相乘，宽范围或 ACL 过滤较多时会放大数据库负载、推高延迟，削弱候选预算对工作量的限制。现有测试记录没有该查询量/延迟上界的验证证据。
- **建议：** 增加有界请求级节点祖先、成员/角色与 ACL 计算复用，并保留响应前的即时授权复核；用 mapper 调用次数或受控数据集验证候选上限下的查询预算。

## 其余复核项

- 团队搜索接口从认证上下文获取 tenant/user；ES 查询限定 tenant/space，之后以数据库节点状态、索引名称/路径/MD5 和当前 ACL 复核。旧索引身份字段缺失时会被排除。对应代码位于 `SearchController.java:53-71`、`TeamSearchServiceImpl.java:107-127,176-219,230-255,359-395`。
- 搜索游标绑定调用者、租户、空间、目录及查询参数并使用 HMAC；分页不返回 ES total，候选扫描有预算，ES 异常不会转为空页。对应 `TeamSearchServiceImpl.java:260-322,538-640`。
- 关注捕获同步写入文件事务内；投递处理将权限核对、通知唯一键写入和 delivery 完成标记放在同一事务。取消/重订阅通过 watch ID 代际区分，失败由独立事务安排退避重试。对应 `ReliableEventPublisher.java:73-89`、`FileWatchCaptureListener.java:55-157`、`FileWatchDeliveryProcessor.java:58-184`、`FileWatchDeliveryRetryService.java:26-57`。
- 通知列表限制 `page>=1`、`1<=size<=100`；新关注通知的展示与目标跳转按 tenant/user 归属并实时核权，失权时清空内容和定位字段。对应 `NotificationController.java:43-61,90-95`、`FileWatchNotificationService.java:28-89,105-119`。
- 变更报告记载的索引租户补齐与通知分页修正有对应源码和既有测试记录；未在本次复核重跑测试。需求范围外的生产迁移与部署仍不在本结论内。

## 风险与下一步

风险限于宽范围团队搜索带来的数据库查询放大；未发现本次范围内其他足以单独阻止 CODE_REVIEW 的高/中等级问题。建议修复 CR-01 后由主线程更新代码修订，再按新 revision 复核。

## State Delta（proposal）

建议 `CODE_REVIEW=fail`，证据为本文件及本 Dispatch 结果；本 Agent 未写入 State。

## 变更影响

本次仅写入白名单中的独立评审文档与 Dispatch 结果；未修改产品代码、测试、State 或其他评审文档。

## r8 主线程复核（2026-09-23）

用户要求由当前主线程接手 review，`DISPATCH-20260923-TSW-CODE-A2` 已中断且无结果。本节是实现者自检，不冒充独立 CODE_REVIEW 门禁。

- CR-01：`TeamSearchServiceImpl` 的候选扫描改用单请求 `ReadContext`；`TeamFileAccessPolicyImpl` 只读取一次空间、成员及角色，并在请求内复用节点祖先；`FolderPermissionService.FreshPermissionContext` 同样复用祖先与规则。最终结果仍重新读库并用无共享缓存的 `canView` 核权。`TeamFileAccessPolicyTest`、`FolderPermissionFreshTest` 检查复用，`TeamSearchServiceTest` 检查扫描与最终复核分离。此前的无缓存扫描问题已修复。
- 游标：解码后要求 Base64URL 重新编码与原文本一致，再以 HMAC 常量时间比较；非规范末位不能复用相同签名。对应 `TeamSearchServiceTest` 的篡改用例已通过。
- 本轮未发现新增的高/中等级代码缺陷。最终逐条即时核权仍有与结果数成正比的数据库开销，这是返回前检查当前权限的代价；当前测试没有给出高并发压测结论。
- 证据：r8 定向 team 13 项、search 19 项通过；覆盖 2 项真实 Elasticsearch 临时索引测试。更大范围的 `mvn -pl st-api -am test -q` 涉及实际开发环境索引重建，观察到重建 805 个节点后主动停止，因此不能声明全量测试通过。

主线程技术判断：CR-01 已修复，代码自检无阻断项。用户随后明确授权当前模型执行剩余任务，授权原话与仅限本任务的范围见 [审阅资料索引](approval-evidence.md)。本结论是用户授权的实现者自检，不能称为独立评审；`CODE_REVIEW` 的正式登记仍需使用当前 r8 证据与 State Evaluate。
