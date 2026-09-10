# 设计：审查遗留建议跟进（20260812）

> 产出者：architect + engineers。关联 State：`.ai/state/20260812-review-followups.yaml`。

## 总体原则
- 顺序执行 TASK-001..004，每项最小侵入、默认行为不变、可配置切换
- 所有对外行为（接口契约/事件语义/权限语义）保持不变

## TASK-001 设计（st-sync）
- `onMessage` 内：幂等预检（`alreadyProcessed`）保留；写入 catch 拆分：
  - `DuplicateKeyException`（org.springframework.dao）：幂等跳过（并发竞态兜底），仅 debug 日志
  - 其它异常：log.warn 后 `throw`，交由 RocketMQ at-least-once 重投；重投后 `eventLogId` 幂等兜底，不产生重复日志
- 风险：重投放大 → 受 eventLogId 幂等约束，重复投递最多触发一次幂等跳过

## TASK-002 设计（st-core）
- 新增 `EventLogCleanupTask`（`@Scheduled`，与 `EventRetryTask` 同模式；`@ConditionalOnProperty(name="app.event-log.cleanup-enabled", havingValue="true", matchIfMissing=true)`）
- 清理 SQL：`DELETE FROM event_log WHERE status=1 AND processed_at < now - 保留期 OR (status=2 AND retry_count>=5 AND updated_at < now - 保留期)`；`status=0` 永不清理
- 保留期 `app.event-log.retention-days`（默认 30）
- `EventLogMapper.cleanupExpired(retentionDate)` 实现；`MAX_RETRY` 从 `EventRetryTask` 常量抽取共享（或配置）

## TASK-003 设计（st-common/st-team/st-core）
- 抽象：将 `TtlCache` 保留为内存实现类名，新增接口 `com.stcloud.common.cache.Cache`（get/put/removeByPrefix/clear/size）；`TtlCache implements Cache`
- 新增 `RedisTtlCache implements Cache`：基于 `StringRedisTemplate`，key 加前缀 `stcloud:cache:`，value JSON 序列化，`removeByPrefix` 用 `scan`/`keys` 批量删除，TTL 用 `expire`
- 选择：`CacheConfig` 读 `stcloud.cache.redis.enabled`（默认 false）→ false 返回 `TtlCache` bean，true 返回 `RedisTtlCache` bean；`FolderPermissionService`/`FileServiceImpl` 字段类型改为 `Cache`（@Resource 按类型注入）
- 风险：Redis 序列化/scan 性能 → 默认关闭；启用时前缀批量失效与内存语义等价

## TASK-004 设计（st-search/st-sync 测试）
- 两模块 pom 补 `spring-boot-starter-test`（test scope）
- st-sync：`SyncChangeMessageConsumerTest` 用 Mockito mock `SyncChangeLogMapper`/`SyncPushService`，直接 new consumer 注入，验证三类路径
- st-search：`FileIndexMessageConsumerTest` mock `SearchService`（或 ES client 外层接口），验证 INDEX/DELETE/UPDATE_META 分发与幂等
