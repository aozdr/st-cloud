# ADR-003：容量配额原子条件扣减

> 架构决策记录。本决策定义 TASK-003 的并发配额/容量一致性方案。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
原配额校验为「读 used -> 校验 quota -> 更新 used」三步（read-then-write），并发上传可在校验与更新之间互相插队，造成配额超卖；云盘总容量检查同样为读后判。需要在不改表结构、不改接口契约的前提下消除竞态，保证「并发超配额仅合法请求成功、used 不为负」。

## 决策
- **个人/团队配额原子化**：`UPDATE sys_user/team_space SET storage_used = storage_used + ? WHERE ... AND storage_used + ? >= 0 AND (storage_quota IS NULL OR storage_used + ? <= storage_quota)`，返回行数即判定结果
- **消费判定**：上传/复制/版本恢复等正向扣减路径调用 `UploadManager.consumeQuota`，返回 0 行抛 `STORAGE_QUOTA_EXCEEDED`（在 `@Transactional` 内回滚本次操作）；负向（释放）0 行静默忽略
- **云盘总容量行锁**：`CloudStorageServiceImpl.checkCapacity` 对已配置 `cloud_total_capacity` 的租户用 `SELECT ... FOR UPDATE` 锁 `sys_tenant` 行，使并发上传总容量校验串行化（未配置时零开销）
- **保留入口预检查**：上传入口保留读型快速失败（UX），但权威判定为原子扣减，不再依赖读后写结果

## 放弃的方案
1. **应用层分布式锁/Redis 锁**：引入外部依赖与锁生命周期管理，且锁粒度难定；数据库条件更新已能原子表达
2. **乐观锁版本列**：需改表结构（违反约束）且重试逻辑复杂
3. **云盘总容量预扣计数器**：需新增「已预扣/可用」列（违反不改表结构约束）
4. **仅靠入口读检查**：无法消除竞态窗口，直接放弃

## 理由
- **原子性**：单条条件 UPDATE 在数据库行锁层面串行化，判定与扣减一体，杜绝超卖
- **零结构变更**：复用现有 storage_used/storage_quota 列，仅改 SQL 语义
- **行为兼容**：quota 为 NULL（不限）时条件恒真，语义与旧逻辑一致；负值永远满足上限条件，释放不受影响
- **吞吐可控**：行锁仅作用于显式配置总容量的租户，常规租户无锁开销

## 后续限制
- 已配置云盘总容量的租户，上传/复制/版本恢复按租户串行化，高并发下有吞吐上限（可接受：显式管理上限场景）
- 入口预检查为读型快速失败，非权威；若未来移除预检查，仅 UX 变化，正确性不受影响
- 跨表一致性（sys_user + team_space 汇总的云盘总容量）依赖行锁保证，若未来引入独立计费/存储表需重新评估