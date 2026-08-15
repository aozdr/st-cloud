# TASK：TASK-003 权限缓存 Redis 化

> 依据《code-and-security-review.md》遗留建议 ③。优先级 P3。

## 元信息
- Task ID: `TASK-003`
- 关联 State: `.ai/state/20260812-review-followups.yaml`
- 归属 Agent: backend-engineer + architect

## 目标
进程内 `TtlCache` 多实例下跨实例权限/可访问性变更依赖 TTL 兜底最终一致。将缓存实现抽象为可切换：新增 Redis 实现并以 `@ConditionalOnProperty` 启用，默认仍走内存缓存（无 Redis 环境行为零变化）。

## 修改范围
- `st-common/.../cache/TtlCache.java`：抽为接口 `Cache`（get/put/removeByPrefix/clear/size）+ 现有内存实现 `MemoryTtlCache`；或保留 `TtlCache` 类名不动、新增 `RedisTtlCache` 实现同一抽象
- 缓存选择：`CacheConfig` 按 `stcloud.cache.redis.enabled`（默认 false）返回内存/Redis 实现
- `st-team/.../FolderPermissionService.java`、`st-core/.../FileServiceImpl.java` 改用抽象类型注入（字段类型改接口，构造/注入兼容）

## 禁止修改范围
- 不改缓存 key 语义、失效策略、TTL 语义与权限计算逻辑
- 默认（未启用 Redis）行为与现有一致，不引入运行时 Redis 依赖

## 验收标准
- 默认内存缓存：现有测试全绿（行为不变）
- 启用 Redis（mock/测试）：get/put/removeByPrefix 语义一致，失效策略等价

## 测试要求
- 单元测试：抽象实现语义一致（内存 + Redis mock 双实现对照）；现有 TtlCache/AccessibleCache/FolderPermission 测试保持全绿

## 输出要求
- 完成后产出 `.ai/docs/20260812-review-followups/changereport-t003.md`；架构决策产出 ADR
