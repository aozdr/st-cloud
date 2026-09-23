# 影响分析

- 版本：tsw-design-r3；作者：GPT-6；2026-09-23 集成复核版
- 前置：已定版 requirement.md / uispec.md。

| 范围 | 现状证据 | 必要变化 |
|---|---|---|
| st-search | SearchController / SearchServiceImpl 仅 ownerId 范围；SearchIndexInitializer 定义索引字段 | 新团队搜索接口、授权过滤分页、索引身份字段及版本标识；个人接口兼容 |
| st-common | UserContext / TenantContext 与模块公共层 | 新增不依赖业务实体的显式主体团队查看权限接口，避免 st-core/st-search 反向依赖 st-team |
| st-team | TeamServiceImpl、FolderPermissionService、TeamMember | 实现显式 tenant/user/node 授权解析；成员过期即时判断；新链路使用新鲜权限数据 |
| st-core | ReliableEventPublisher.publishSyncChange 为主要文件变更汇合点 | 增加同步的订阅事件捕获钩子（仅数据库）；移动携带旧父 ID；补齐内容更新未发事件的入口 |
| st-team 通知 | NotificationController 直接映射存储字段；NotificationHelper 吞异常 | 新关注服务、可靠投递队列、读取时核权和安全目标解析；旧通知不改语义 |
| 数据库 | 最新增量 42_file_orphan_candidate.sql；notification 无事件幂等键 | 新增 43_file_watch.sql、两张表及 notification 可空字段/唯一索引；同步 H2 |
| st-web | SearchPage、TeamSpacePage、FileDetailPanel、NotificationBell | 团队搜索范围与加载更多、关注入口与列表、团队文件定位、安全通知跳转 |
| 测试 | 各模块现有测试及 st-core schema | 权限负例、重试并发、回滚、事件覆盖、前端契约与构建；DB 双对比 |
| 部署 | ES、MySQL、可选 RocketMQ | 先 DB 增量，再应用，再重建索引；新提醒不依赖 MQ 是否启用 |
| 文档 | business-domain / api-reference / data-model / architecture | 实现验证后同步，不提前声称新功能已存在 |

本轮集成修正：`ReliableEventPublisher.publishFileIndex` 必须在写入索引 Outbox 前补齐可信 tenant 快照并拒绝跨租户冲突；通知列表限制 size≤100，以约束按提醒重新核权的逐行读取成本。

权限影响：新增接口不赋予额外访问权；无租户/用户上下文拒绝；异步任务显式建立和释放租户上下文。新数据表显式 tenant_id 条件，即使 PRIVATE 模式关闭自动注入也不跨租户。

兼容影响：原个人搜索接口、通知已读接口和同步事件结构保留；索引新字段缺失时新团队检索不回退到无范围查询。旧客户端忽略通知新增字段。

主要风险与措施：候选过滤扫描设上限；跨目录事件保留旧父链；文件夹根事件匹配后代关注；投递事务不进行外部网络；通知读取禁止直接返回失权内容快照。生产迁移需另行授权，本轮仅可使用明确的本地开发/测试数据库。





