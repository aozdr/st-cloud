# Code Review + Security Review：st-cloud 代码优化（TASK-001..006）

> 归属 exitCriteria：CODE_REVIEW / SECURITY_REVIEW。产出者：code-reviewer + security-reviewer（收尾门禁）。关联 State：`.ai/state/20260811-codex-tasks-execution.yaml`。

## 审查范围
6 项优化全部落库改动：文件对象模型、上传状态机、容量并发原子化、事件 Outbox + RocketMQ、权限性能缓存、测试体系补充。重点审查安全敏感路径：权限/分享访问控制、事件负载与消费幂等、配额并发、文件引用与物理删除。

## 结论
- **通过（无阻断问题）**：权限语义与返回码未变、缓存为可重建派生数据且写路径失效覆盖 + TTL 兜底；事件负载为字段白名单快照（无敏感泄露）；配额扣减为原子条件 UPDATE（不超卖不为负）；文件引用归零才物理删除。
- **门禁**：CODE_REVIEW ✅、SECURITY_REVIEW ✅。遗留建议见下（不阻断交付）。

## 逐项审查

### 1. 文件对象模型（TASK-001）
- `file_object` 表 `uk_tenant_md5` 唯一键 + `insertIgnore`：同租户秒传去重；跨租户各独立对象（租户隔离由拦截器保证）。
- 引用归零才 `deletePhysical`（`FileObjectServiceImpl.release` 返回剩余计数，<=0 才删除）；未归零不误删。
- 结论：无权限/一致性风险。✅

### 2. 上传状态机（TASK-002）
- `claimMerging` 原子认领合并（MERGING 守卫）、`confirm` 分片状态落库、`abort` 守卫（已完成不可 abort）、失败保留节点与分片供重试/断点续传。
- 幂等：重复 merge 不产生重复节点（测试断言 1 节点 + 1 次 S3 completeMultipartUpload）。
- 结论：无越权或重复扣费路径。✅

### 3. 容量并发原子化（TASK-003）
- 配额扣减合并为条件 UPDATE（`used + delta <= quota` 且非负双守卫），0 行即超限抛异常；并发 10 线程测试验证不超卖（used 精确=配额、5 成 5 拒）。
- 复制/版本恢复同步原子化；云盘总容量行锁。
- 结论：并发正确。✅

### 4. 事件 Outbox + RocketMQ（TASK-004）
- `EventMessage.FileNodeSnapshot` 为字段白名单（id/tenantId/parentId/nodeType/status/ownerId/path/md5/size 等消费所需），不含敏感扩展字段。✅
- 幂等：`event_log_id` 雪花 ID 直接入 payload；st-sync 以 `sync_change_log.uk_event_log_id` 唯一键（先查后插）；st-search 依赖 ES 覆盖写。✅
- 双通道：`rocketmq.name-server` 配置即走 Outbox+MQ，未配置本地 `ApplicationEvent` 兜底；本地监听器保留不重复消费（MQ 配置时）。✅
- **建议（P2 后续）**：`SyncChangeMessageConsumer.onMessage` 对非幂等冲突类异常仅 log 不重抛——若插入失败非唯一键冲突，消息会被 ACK 而丢失（MQ at-least-once 依赖抛异常重投）。建议区分唯一键冲突（幂等跳过）与其它异常（重抛触发 MQ 重投）。不影响本批交付，Outbox 侧重投与 eventLogId 幂等已覆盖主要故障。
- 另一建议（P2）：`event_log` 长期留存不清理（可审计），后续可加归档策略（已在 ADR-004 记录）。

### 5. 权限性能缓存（TASK-005）
- 语义零变化：`computePermission` 原向上遍历逻辑保留为缓存未命中时的计算路径；返回码 -1/0/1/2 不变；未改权限表结构。
- 缓存键：权限 `spaceId:nodeId:userId:spaceRole`（用户维度，正确）；可访问性 `acc:nodeId`（仅祖先状态布尔，与用户无关，正确，不造成跨用户越权）。
- 失效覆盖：规则写入 `setPermissions` 自失效 + `TeamServiceImpl` 9 处成员/角色变更点；结构变更 move/回收（子孙级联）/恢复（子孙级联）/物理删除/去重均失效；TTL（60s/30s）兜底最终一致。
- 分享路径复用 `validateAccessible`，回收即拒绝、恢复即放行（集成测试覆盖）。
- 多实例最终一致由 TTL 兜底，已在 ADR-005 记录为限制。✅

### 6. 测试体系补充（TASK-006）
- 纯测试补充 + 1 处 TASK-005 缺陷修复（`restore` 恢复时子孙级联失效缓存，演练发现并修复）。
- 56 用例全绿；无外部基础设施依赖（H2 + Mockito）。
- 并发用例断言不变式（单一对象 + refCount=线程数）与竞争时序无关。✅

## 遗留建议汇总（不阻断）
1. P2：st-sync 消费者非幂等异常重抛（MQ at-least-once 重投）— 见 TASK-004。
2. P2：event_log 归档/清理策略 — 见 ADR-004。
3. P3：多实例权限缓存强一致可换 Redis（`TtlCache` 实现替换）— 见 ADR-005。
4. P3：st-search/st-sync 无独立测试基建，消费链路由 st-core 集成测试间接覆盖；后续可补模块级测试。

## 门禁结论
- CODE_REVIEW：通过（结构/幂等/回归全绿）。
- SECURITY_REVIEW：通过（权限/事件/配额/引用路径无越权、无泄露、无并发破坏；遗留为 P2/P3 建议）。
