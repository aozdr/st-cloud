# Change Report：TASK-002 event_log 清理/归档

> 关联 Task: .ai/tasks/TASK-002.md  归属: IMPLEMENTED

## 修改文件清单
| 文件 | 变更类型 | 说明 |
|------|----------|------|
| st-core/.../outbox/EventLogCleanupTask.java | 新增 | 定时清理任务，24h 周期，可配置保留期与开关 |
| st-core/.../mapper/EventLogMapper.java | 修改 | 新增 cleanupExpired(cutoff, maxRetry) 删除方法 |
| st-core/.../outbox/EventRelay.java | 修改 | 本地兜底路径标记 status=1（已投递），使清理可安全覆盖 |

## 与验收标准对照
- [x] 已投递且超保留期行被清理 — status=1 AND processed_at < cutoff
- [x] 在途与未耗尽重试行保留 — status=0 永不清理；status=2 AND retry_count < MAX_RETRY 不清理
- [x] 清理任务幂等、可配置、默认不破坏现有重试 — @ConditionalOnProperty(matchIfMissing=true)，保留期默认 30 天

## 测试结果
- EventOutboxIntegrationTest：6 用例全通过（含本地兜底 outbox 断言）
- 全量回归 124 用例 0 失败

## 风险
- 删除审计行不可恢复，默认保留 30 天可配置；如需归档可后续扩展为软删除/归档表
