# 测试报告：st-cloud 代码优化（TASK-001..006）

> 产出者：tester。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。运行环境：Windows / Java 17 / Maven / H2 内存库。

## 汇总
- 测试类：**10** 个（原 7 + TASK-006 新增 3）
- 用例数：**56** 个全绿（st-core 47 + st-team 9）
- 构建：全模块 `mvn compile` BUILD SUCCESS
- 静态校验：`.ai/scripts/verify-loop.ps1` PASS（FAIL=0）

## 用例清单

### st-core（47）
| 测试类 | 用例 | 覆盖 |
|---|---|---|
| JwtUtilsTest（st-common） | 5 | JWT 工具（回归） |
| TtlCacheTest | 4 | TTL 缓存读写/过期/前缀失效/清空（TASK-005） |
| AccessibleCacheTest | 4 | validateAccessible 缓存命中/失效/禁止路径（TASK-005） |
| ConcurrentUploadIntegrationTest | 2 | 多用户并发各自配额、同 md5 并发去重（TASK-006） |
| EventOutboxIntegrationTest | 6 | Outbox 回滚不产事件/重投/幂等/双通道（TASK-004） |
| FavoriteServiceIntegrationTest | 10 | 收藏功能（回归） |
| FileObjectIntegrationTest | 5 | 秒传去重/跨租户隔离/引用计数/物理删除（TASK-001） |
| FileServiceFlowIntegrationTest | 5 | 移动/移动拒绝/复制引用/回收恢复/子孙级联（TASK-006） |
| FileServicePermissionIntegrationTest | 3 | 用户权限/租户管理员/分享鉴权路径（TASK-006） |
| QuotaConcurrencyIntegrationTest | 1 | 10 线程容量竞争不超卖（TASK-003） |
| UploadStateMachineIntegrationTest | 7 | 分片上传/断点续传/合并幂等/失败恢复/abort（TASK-002+006） |

### st-team（9）
| 测试类 | 用例 | 覆盖 |
|---|---|---|
| FolderPermissionServiceTest | 4 | 权限缓存命中/失效/空间隔离/setPermissions 失效（TASK-005） |
| FolderPermissionServiceRuleTest | 5 | role/member/deny/祖先遍历/回退规则语义（TASK-006） |

## 关键路径覆盖（TASK-006 目标分类）
- **文件**：秒传去重、分片上传、断点续传、文件移动、复制引用、删除恢复 ✅
- **并发**：多用户同时上传、同文件同时上传、容量竞争 ✅
- **权限**：用户权限、团队权限、分享权限 ✅

## 结论
全部用例通过，TASK-001..005 回归无破坏，关键路径覆盖率明显提升；本报告随 changereport-t001..006 与 ADR-001..005 长期留存。
