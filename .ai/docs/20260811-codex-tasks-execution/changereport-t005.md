# Change Report：TASK-005 权限性能优化（缓存）

> 编码完成后的变更汇总。归属 exitCriteria：IMPLEMENTED。产出者：backend-engineer。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。

## 背景
文件访问权限判定（`FolderPermissionService.resolvePermission`）每次沿 `parent_id` 向上遍历至空间根（每层 2 条 SQL），大目录深层级访问成本高；`FileServiceImpl.validateAccessible` 每次执行祖先链递归 SQL。TASK-005 引入**进程内 TTL 缓存**：未命中才计算并回填，权限/结构变更时显式失效，命中后大目录访问不再递归遍历。

## 修改文件清单
**新增**
- `st-common/.../cache/TtlCache.java` — 轻量线程安全 TTL 缓存（ConcurrentHashMap + 惰性过期），支持 `get/put/removeByPrefix/clear/size`
- `st-core/src/test/.../common/cache/TtlCacheTest.java` — 4 用例（读写/过期/前缀失效/清空）
- `st-core/src/test/.../service/impl/AccessibleCacheTest.java` — 4 用例（命中不查库/失效重算/禁止路径/空值）
- `st-team/src/test/.../service/FolderPermissionServiceTest.java` — 4 用例（命中不遍历/失效重算/空间隔离/setPermissions 失效）

**修改**
- `st-team/.../service/FolderPermissionService.java` — `resolvePermission` 加缓存（key=`spaceId:nodeId:userId:spaceRole`，TTL 60s）；新增 `invalidateSpace(spaceId)` 按前缀失效；`setPermissions` 末尾自失效
- `st-team/.../service/impl/TeamServiceImpl.java` — 9 处权限变更点接入 `invalidateSpace`（邀请/加入/改角色/移除成员/建删改角色/外部成员/删空间）
- `st-core/.../service/FileService.java` — 新增 `invalidateAccessible(Long nodeId)` 接口
- `st-core/.../service/impl/FileServiceImpl.java` — `validateAccessible` 改缓存判定（key=`acc:nodeId`，TTL 30s）；新增 `invalidateAccessible`；`move`/`moveTeamFiles`/`deleteToRecycleBin`/`deleteTeamFiles`/`cleanupDuplicates` 接入失效（回收时级联失效子孙，避免 TTL 窗口内泄漏）
- `st-core/.../service/impl/RecycleBinServiceImpl.java` — `restore` 恢复后失效、`permanentDeleteNodeAndChildren` 物理删除后失效
- `st-team/pom.xml` — 增加 `spring-boot-starter-test`（test scope，供缓存单测）

## 与 TASK 验收标准对照
| 验收标准 | 实现 | 状态 |
|---|---|---|
| 大目录权限判定命中缓存，无递归 SQL | `resolvePermission` 未命中才走 `computePermission` 向上遍历并回填；`cacheHitSkipsSecondTraversal` 断言二次访问不再查询规则/节点 | ✅ |
| 权限变更后缓存失效，重新计算正确 | `invalidateSpace` 按 `spaceId:` 前缀清除；`setPermissions` 与 TeamServiceImpl 9 处成员/角色变更点接入；`invalidateSpaceRecomputes` / `setPermissionsInvalidatesSpace` 验证重算 | ✅ |
| 可访问性判定不再高频递归 | `validateAccessible` 缓存 `acc:nodeId` 布尔；`cacheHitSkipsSecondSql` 断言二次访问不执行祖先链 SQL | ✅ |
| 分享路径复用缓存 | `ShareServiceImpl`（创建/访问/预览）调用 `fileService.validateAccessible`，自动命中缓存（契约未变） | ✅ |
| 权限语义与返回码不变 | 缓存仅存最终结果（-1/0/1/2），计算逻辑 `computePermission` 原样保留；未改权限表结构 | ✅ |

## 测试结果
- `mvn test -pl st-core -am`：退出码 0，**36 个测试全绿**（原 28 + TtlCacheTest 4 + AccessibleCacheTest 4，含 TASK-001..004 回归）
- `mvn test -pl st-team -am -Dtest=FolderPermissionServiceTest`：退出码 0，**4 个测试全绿**
- 全模块 `mvn compile`：BUILD SUCCESS
- `.ai/scripts/verify-loop.ps1`：PASS（FAIL=0，4 个 WARN 为旧版对比的合法表述）

## 明确未改动项（符合 TASK 禁止范围）
- 权限语义与返回码（-1/0/1/2）不变；`computePermission` 原遍历逻辑保留为缓存未命中时的计算路径
- 不改变权限表结构（`team_folder_permission` / `team_role` / 成员关系）；缓存为可重建派生数据，`invalidateSpace`/TTL 双保险
- 不触碰前端 st-web 接口契约
- `VersionServiceImpl` 无结构变更，无需失效

## 风险
- **进程内缓存**：多实例部署下各实例独立缓存，变更仅对本地实例即时失效，跨实例以 TTL 兜底最终一致（TTL 60s 权限 / 30s 可访问性）
- **遗漏失效**：缓存 TTL 作为兜底，即使个别变更点遗漏也会在 TTL 后自愈；已按「权限规则/成员/角色/结构变更」四类盘点失效点
- **内存占用**：键数随空间×节点×用户增长，受 TTL 60s 上限约束；单节点粒度可访问性缓存仅按 nodeId，量级可控
- **后续演进**：若需多实例强一致，可换 Redis（key 结构不变，仅替换 `TtlCache` 实现），属非本 TASK 范围
