# Change Report：TASK-003 权限缓存 Redis 化

> 关联 Task: .ai/tasks/TASK-003.md  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| st-common/.../cache/Cache.java | 新增 | 缓存抽象接口（get/put/removeByPrefix/clear/size） |
| st-common/.../cache/TtlCache.java | 修改 | implements Cache，5 方法加 @Override |
| st-common/.../cache/RedisTtlCache.java | 新增 | Redis 实现，SCAN+DELETE 前缀失效，统一 stcloud:cache: 前缀 |
| st-common/.../cache/CacheFactory.java | 新增 | 按 stcloud.cache.redis.enabled 切换实现，默认内存 |
| st-team/.../FolderPermissionService.java | 修改 | 字段类型改 Cache，经 CacheFactory 创建 |
| st-core/.../FileServiceImpl.java | 修改 | 可访问性缓存字段类型改 Cache |
| st-common/src/test/.../cache/TtlCacheTest.java | 新增 | 内存实现 5 用例 |
| st-common/src/test/.../cache/RedisTtlCacheTest.java | 新增 | Redis mock 5 用例 |
| st-common/src/test/.../cache/CacheFactoryTest.java | 新增 | 工厂选择 4 用例 |

## 与验收标准对照
- [x] 默认内存缓存：现有测试全绿（行为不变）— AccessibleCacheTest 4 + FolderPermissionService*Test 9 全通过
- [x] 启用 Redis（mock）：get/put/removeByPrefix 语义一致 — RedisTtlCacheTest 5 用例验证命名空间前缀/TTL/SCAN 失效/清空/计数

## 测试结果
- 缓存测试 14 用例全通过（TtlCache 5 + RedisTtlCache 5 + CacheFactory 4）
- 全量回归 124 用例 0 失败

## 风险
- 默认不启用 Redis（stcloud.cache.redis.enabled=false），零运行时影响
- 启用 Redis 时 SCAN 性能依赖 key 数量；极端大量 key 场景需评估 SCAN count 参数
