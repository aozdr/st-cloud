# ADR-007：权限缓存 Redis 化抽象

> 架构决策记录。本决策扩展 ADR-005（进程内 TTL 缓存），新增 Redis 实现以消除多实例最终一致窗口。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
ADR-005 采用进程内 TtlCache 缓存权限/可访问性结果，多实例部署下跨实例权限变更依赖 TTL 兜底最终一致（权限 60s / 可访问性 30s 窗口）。多实例场景下此窗口可能导致短时间内权限不一致。需在不改缓存 key 语义、失效策略、TTL 语义与权限计算逻辑的前提下，提供 Redis 实现以消除跨实例窗口。

## 决策
- **抽象接口**：新增 com.stcloud.common.cache.Cache（get/put/removeByPrefix/clear/size），TtlCache implements Cache
- **Redis 实现**：RedisTtlCache implements Cache，基于 RedisTemplate，所有 key 加统一前缀 stcloud:cache:，前缀失效/清空用 SCAN（非 KEYS），TTL 用 set(key, value, ttl, TimeUnit)
- **工厂切换**：CacheFactory 按 stcloud.cache.redis.enabled（默认 false）返回内存/Redis 实现；无 RedisTemplate 时回退内存
- **调用方接入**：FolderPermissionService / FileServiceImpl 字段类型改 Cache，经 CacheFactory.create(ttl) 创建
- **默认零行为变化**：未启用 Redis 时行为与 ADR-005 完全一致

## 放弃的方案
1. **直接替换为 Redis 缓存（移除内存实现）**：破坏单机/无 Redis 部署场景，违反「默认行为不变」约束
2. **Spring Cache 抽象（@Cacheable）**：权限缓存 key 结构与前缀失效策略自定义度高，Spring Cache 抽象适配成本大于自建
3. **Redis Pub/Sub 主动失效通知**：复杂度高，SCAN+DELETE 前缀失效已满足语义一致性需求

## 理由
- **平滑可选**：默认内存缓存行为不变，启用 Redis 即获多实例一致性，无迁移风险
- **语义等价**：Redis 实现的 key 前缀/TTL/前缀失效/清空/计数语义与内存实现完全对照（双实现单测验证）
- **SCAN 安全**：前缀失效/清空用 SCAN 而非 KEYS，避免大 key 空间阻塞 Redis

## 后续限制
- 启用 Redis 需确保 RedisTemplate（JSON 序列化）可用；st-common 已含 spring-boot-starter-data-redis 依赖
- SCAN 性能依赖 key 数量；极端大量 key 场景需评估 SCAN count 参数（当前 100）
- CacheFactory 为 @Component，各 Service 需注入 CacheFactory 并调用 create(ttl) 而非直接 new

## 关联
- 前置决策: ADR-005（文件权限与可访问性结果缓存）
- 关联任务 State: .ai/state/20260812-review-followups.yaml
- 关联文档: .ai/docs/20260812-review-followups/design.md
- 关联 Task: .ai/tasks/TASK-003.md
