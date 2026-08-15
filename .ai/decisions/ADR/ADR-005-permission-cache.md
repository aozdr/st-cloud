# ADR-005：文件权限与可访问性结果缓存

> 架构决策记录。本决策定义 TASK-005 的权限性能优化方案。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
`FolderPermissionService.resolvePermission` 每次沿 `parent_id` 向上遍历至空间根（最多 20 层，每层 2 条 SQL：权限规则 + 节点），大目录/深层级访问成本高；`FileServiceImpl.validateAccessible` 每次执行 `WITH RECURSIVE` 祖先链 SQL（含节点自身）。两者均为纯函数式计算（结果只依赖当前数据库状态），适合结果缓存。需在不改权限语义、不改返回码（-1/0/1/2）、不改权限表结构的前提下显著降低访问成本，并在权限/结构变更后保证缓存正确刷新。

## 决策
- **进程内 TTL 缓存**：新增 `com.stcloud.common.cache.TtlCache`（ConcurrentHashMap + 惰性过期），key 前缀失效；权限缓存 TTL=60s，可访问性缓存 TTL=30s
- **权限结果缓存**：`resolvePermission` key=`spaceId:nodeId:userId:spaceRole`，命中直接返回；未命中走原 `computePermission` 向上遍历后回填
- **失效策略（权限）**：`invalidateSpace(spaceId)` 按 `spaceId:` 前缀清除整空间权限缓存。权限规则写入（`setPermissions` 自失效）与成员/角色变更（`TeamServiceImpl` 9 处：邀请/加入/改角色/移除成员/建删改角色/外部成员/删空间）均调用
- **可访问性缓存**：`validateAccessible` key=`acc:nodeId`（节点自身布尔，不区分用户），未命中执行 `countInaccessibleAncestors==0` 后回填；节点 `move`/`moveTeamFiles`/`deleteToRecycleBin`/`deleteTeamFiles`/`cleanupDuplicates`/`restore`/物理删除时 `invalidateAccessible`，回收时对子孙级联失效
- **分享路径复用**：`ShareServiceImpl` 创建/访问/预览复用 `fileService.validateAccessible`，自动命中缓存，接口契约不变
- **TTL 作为最终一致性兜底**：多实例 / 遗漏失效场景下，缓存过期后自动重算，保证不长期错误

## 放弃的方案
1. **Redis 分布式缓存**：需引入 Redis 依赖与序列化/反序列化，单机部署下收益不显著；key 结构与失效策略与内存版完全一致，后续可平滑替换
2. **权限快照落表（物化 path 权限链）**：需新增/变更表结构（违反 TASK 禁止范围），且同步时机与一致性成本高
3. **仅加大 TTL、不显式失效**：无法保证「权限变更后立即重算正确」的验收标准
4. **缓存计算出的遍历中间结果**：复杂度高，收益有限；直接缓存最终结果更简单且命中率更高

## 理由
- **命中率高**：权限/可访问性结果为读多写少的派生数据，TTL 窗口内重复访问显著命中
- **成本可量化**：大目录深层级访问由「每层 2 SQL」降为 O(1) 缓存命中；`validateAccessible` 由递归 SQL 降为命中返回
- **语义零变化**：只缓存最终结果，计算路径与返回码原样保留；缓存为可重建派生数据，不污染持久层
- **失效覆盖**：按「规则/成员/角色/结构变更」四类盘点全部写路径，配合 TTL 双保险
- **最小侵入**：改动集中在 2 个 Service + 1 个通用缓存类，不触碰接口契约与表结构

## 后续限制
- **多实例最终一致**：跨实例权限变更在 TTL 内不立即生效（权限 60s / 可访问性 30s）；如需强一致换 Redis（`TtlCache` 实现替换即可）
- **内存随访问量增长**：键数受 TTL 约束；极端高并发空间下可评估按空间 LRU 淘汰（非本 TASK 范围）
- **子孙可访问性缓存**：回收时已对子孙级联失效；其他结构变更（如移动文件夹）依赖 TTL 兜底，已在设计内
