# ADR-006：event_log Outbox 清理/归档策略

> 架构决策记录。本决策定义 TASK-002 的 event_log 历史行清理方案。由 Knowledge Manager 复核一致性。

## 状态
Accepted

## 背景
event_log 作为事件 Outbox 表，承载已投递（status=1）、重试中（status=2）、在途（status=0）三类行。随系统运行，已投递与重试耗尽的行持续累积，表增长影响查询性能与存储成本。需在不干扰投递/重试主链路的前提下定期清理可安全删除的历史行。

## 决策
- **定时清理任务**：EventLogCleanupTask（@Scheduled 24h 周期，@ConditionalOnProperty(matchIfMissing=true)）
- **清理范围**（仅删除可安全清除的行）：
  - status=1（已投递，含本地兜底标记）：processed_at < cutoff -> 删除
  - status=2 AND retry_count >= MAX_RETRY（重试耗尽）：created_at < cutoff -> 删除
  - status=0（在途）与 status=2 AND retry_count < MAX_RETRY（仍可重试）：永不清理
- **保留期可配置**：pp.event-log.retention-days（默认 30），pp.event-log.cleanup-enabled（默认 true）
- **本地兜底路径补标记**：EventRelay 本地兜底投递后标记 status=1，使清理可安全覆盖兜底场景

## 放弃的方案
1. **软删除/归档表**：增加表结构与迁移成本，当前阶段物理删除即可；后续如需审计追溯可扩展归档表
2. **全量保留不清理**：表无限增长，长期影响性能
3. **清理 status=0 在途行**：会导致未投递事件丢失，破坏 Outbox 可靠性
4. **按时间一刀切清理所有 status**：会清掉仍可重试的失败事件，破坏重试语义

## 理由
- **安全边界清晰**：仅清理「投递已完成」与「重试已耗尽」两类行，不触碰在途与可重试行
- **可配置**：保留期与开关均可配置，默认 30 天平衡审计需求与存储成本
- **最小侵入**：新增独立清理任务，不改投递/重试主链路语义

## 后续限制
- 物理删除不可恢复；如需审计追溯需扩展为归档表（非本 TASK 范围）
- 清理周期 24h，极端高吞吐场景下单次删除量可能较大，可后续评估分批删除

## 关联
- 关联任务 State: .ai/state/20260812-review-followups.yaml
- 关联文档: .ai/docs/20260812-review-followups/design.md
- 关联 Task: .ai/tasks/TASK-002.md
