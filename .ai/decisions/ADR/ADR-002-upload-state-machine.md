# ADR-002：上传流程状态机与幂等语义

> 架构决策记录。本决策定义 TASK-002 的上传状态机、幂等守卫与职责拆分。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
上传链路存在三类问题：无显式「合并中/已删除」状态（仅 0-3）；merge 无幂等守卫（重复请求会重复合并或报错，失败恢复能力弱）；confirm 不落库分片状态（DB 与 S3 不一致）。同时 `UploadServiceImpl` 职责过重（542 行门面），难以演进。

约束：
- 不改变前端上传接口契约（check/init/status/merge/abort/chunk-url/chunk-confirm 签名与响应不变）
- 不引入新表（状态机用现有 `upload_status` + `file_chunk` 表达）
- 兼容历史数据（0-待上传/1-上传中/2-已完成/3-失败 语义不变）

## 决策
- **状态机**：`INIT(0) -> UPLOADING(1) -> MERGING(4) -> COMPLETED(2=STORED)`，异常 `-> FAILED(3)`，中止 `-> DELETED(5)`（保留语义，当前新建上传 abort 仍物理删除节点）。`UploadStatus` 增 `isTerminal()` 标记终态。
- **合并幂等守卫**：`FileNodeMapper.claimMerging` 原子认领（`UPDATE ... SET upload_status=4 WHERE upload_status IN (1,3)`，影响行数=1 才取得合并权）；COMPLETED 早退直接返回已有节点；并发未认领到则重读判断（已完成返回 / 合并中抛错）。
- **失败可恢复**：新建上传 merge 失败保留节点与分片并标记 FAILED，S3 分片保留，客户端可重试 merge（从 FAILED 重新认领）；替换上传失败则 abort S3 残留并回滚上一版本（保证既有文件可用）。
- **分片状态落库**：confirm 将 `file_chunk.status` 0→1（幂等）；merge 成功置 2 并**保留记录**（支撑重复 merge 幂等，替代原「合并后删除」）。
- **职责拆分**：`UploadServiceImpl` 降为编排门面，委托 `UploadManager`（状态机+配额+回滚）、`UploadChunkManager`（分片记录）、`UploadStorageManager`（S3 生命周期+容量）、`UploadEventPublisher`（索引/同步事件）。

## 放弃的方案
1. **merge 后删除分片记录 + 通过 fileId 幂等**：删除后重复 merge 无法按 uploadId 定位节点，依赖客户端回传 fileId 不可靠，放弃。
2. **file_node 增加 upload_id 列支撑幂等**：需新增列与迁移，且合并后需置空/保留策略复杂；保留分片记录即可达同效果（无新表），放弃。
3. **分布式锁/Redis 锁做合并互斥**：引入外部依赖、增加复杂度；单库条件更新（claimMerging）已能满足并发认领，放弃。
4. **合并失败一律删除节点**（原行为）：破坏「失败可恢复」，新建上传应保留 FAILED 供重试，放弃。

## 理由
- **幂等正确性**：原子条件更新保证并发下仅一个 merge 执行 S3 complete；COMPLETED 早退保证重复请求返回同一节点、不产生重复节点（验收标准）
- **恢复能力**：保留分片与 FAILED 节点，配合 S3 `listUploadedParts` 断点续传，实现「失败可恢复」（验收标准）
- **一致性**：confirm 落库使 `file_chunk.status` 与 S3 上传进度可对照；分片唯一键 `(upload_id, chunk_index)` 防止乱序错数据
- **可维护性**：4 个单职责组件替代巨型门面，后续状态机/分片/存储各自演进
- **零契约破坏**：纯内部实现调整，前端与既有历史数据完全兼容

## 后续限制
- **S3 与 DB 非原子**：complete 后 DB 异常会留下「S3 已合并/DB 回滚」不一致，重试可能触发 S3 重复合并报错；后续应在存储层做同 key 幂等或引入合并完成标记
- **分片记录累积**：status=2 的已完成记录不删除以支撑幂等，需定时清理超期数据（建议 TASK-006 或独立维护任务补充）
- **替换上传合并失败**回滚上一版本依赖 file_version 快照，版本表缺失时回退 FAILED 语义
- DELETED(5) 当前为保留状态，未接软删除流程；未来如需审计/回收站语义再启用