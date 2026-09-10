# Change Report：TASK-006 测试体系补充

> 编码完成后的变更汇总。归属 exitCriteria：IMPLEMENTED / TESTCASES / TEST_PASS。产出者：tester（测试补充）+ backend-engineer（缺陷修复）。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。

## 背景
项目原仅 7 个测试类（多聚焦 TASK-001..004 与收藏），文件移动/删除恢复、多用户与同文件并发、用户/团队/分享权限等关键路径缺少直接用例。TASK-006 在不改业务代码的前提下补齐三类测试：文件、并发、权限，覆盖关键路径。

## 修改文件清单
**新增（st-core 测试）**
- `FileServiceFlowIntegrationTest.java` — 5 用例：文件移动（父节点/路径落库）、移动拒绝（自身/移入子孙成环）、复制引用复用对象与引用计数、文件回收/恢复、目录回收子孙级联不可访问/恢复
- `FileServicePermissionIntegrationTest.java` — 3 用例：所有者访问/非所有者拒绝、租户管理员跨用户访问、分享鉴权路径（validateAccessible 回收拒绝/恢复放行）
- `ConcurrentUploadIntegrationTest.java` — 2 用例：多用户同时上传各自配额内互不影响、同文件（同 md5）同时上传去重为单一对象且引用计数正确
- `UploadStateMachineIntegrationTest.java` 追加 1 用例：断点续传（部分分片已上传 → 状态落库 → 续传剩余 → 合并完成）

**新增（st-team 测试）**
- `FolderPermissionServiceRuleTest.java` — 5 用例：role 规则生效、member 覆盖 role、deny(-1) 生效、祖先规则向上遍历、无规则回退空间角色

**缺陷修复（TASK-005 范围，演练发现）**
- `st-core/.../service/impl/RecycleBinServiceImpl.java` — `restore` 恢复时对子孙级联失效可访问性缓存（原仅失效节点自身；回收期间被访问并缓存为「不可访问」的子孙，恢复后仍被误判）

## 与 TASK 验收标准对照
| 验收标准 | 实现 | 状态 |
|---|---|---|
| 文件测试：秒传/分片/断点续传/移动/删除恢复 | 秒传去重（FileObjectIntegrationTest 5 用例）；分片 + 断点续传（UploadStateMachineIntegrationTest 7 用例）；移动 + 复制引用 + 删除恢复（FileServiceFlowIntegrationTest 5 用例） | ✅ |
| 并发测试：多用户/同文件/容量竞争 | 容量竞争（QuotaConcurrencyIntegrationTest）；多用户并发 + 同文件去重（ConcurrentUploadIntegrationTest 2 用例） | ✅ |
| 权限测试：用户/团队/分享 | 用户权限 + 租户管理员 + 分享鉴权路径（FileServicePermissionIntegrationTest 3 用例）；团队规则语义（FolderPermissionServiceRuleTest 5 + FolderPermissionServiceTest 4 用例） | ✅ |
| 现有测试全绿 | st-core 47 用例 + st-team 9 用例全部通过，TASK-001..005 回归无破坏 | ✅ |
| 关键路径覆盖率明显提升 | 测试类 7 → 10；用例数显著增长；新增文件移动/删除恢复/多用户并发/同文件去重/用户与团队与分享权限等此前缺失的关键路径 | ✅ |

## 测试结果
- `mvn test -pl st-core -am`：退出码 0，**47 个测试全绿**（TASK-006 新增 11 用例：Flow 5 + Permission 3 + Concurrent 2 + resume 1）
- `mvn test -pl st-team -am`：退出码 0，**9 个测试全绿**（TASK-006 新增 FolderPermissionServiceRuleTest 5 用例）
- 全模块 `mvn compile`：BUILD SUCCESS
- `.ai/scripts/verify-loop.ps1`：PASS（FAIL=0，4 个 WARN 为旧版对比的合法表述）
- 测试覆盖明细见 `.ai/docs/20260811-codex-tasks-execution/test-report.md`

## 明确未改动项（符合 TASK 禁止范围）
- 未改业务代码（除上述 TASK-005 范围缺陷修复，属「发现缺陷时按对应 TASK 修复」）
- 未引入外部基础设施依赖（全部复用 H2 + 现有 AbstractIntegrationTest / CoreTestApplication / Mockito）
- 未触碰前端 st-web 接口契约
- st-search / st-sync 无既有测试基建，其消费幂等/事件链路已在 st-core `EventOutboxIntegrationTest`（sync_change_log 幂等）与 `FileObjectIntegrationTest` 覆盖

## 风险
- **并发测试确定性**：同文件去重并发用例依赖数据库唯一键与原子增量，断言不变式（单一对象 + refCount=线程数）在任何竞争时序下均成立
- **缓存级联失效**：恢复场景级联失效后，TTL 不再承担该路径的一致性兜底；其余未显式失效的结构变更仍由 TTL（30s/60s）兜底，已在 ADR-005 记录
