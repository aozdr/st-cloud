# TASK：TASK-002 event_log 清理/归档

> 依据《code-and-security-review.md》遗留建议 ②。优先级 P2。

## 元信息
- Task ID: `TASK-002`
- 关联 State: `.ai/state/20260812-review-followups.yaml`
- 归属 Agent: backend-engineer

## 目标
`event_log` Outbox 行长期留存不清理（可审计但随量增长）。新增可配置保留期的清理/归档策略，仅清理已投递与重试耗尽的历史行，不干扰在途投递与重试。

## 修改范围
- `st-core/.../outbox/EventLogCleanupTask.java`（新增）：定时清理 `status=1`（已投递）超过保留期、以及 `status=2` 且 `retry_count>=MAX_RETRY`（重试耗尽）超过保留期的行；`status=0` 在途保留
- `EventLogMapper`：新增清理查询/删除方法
- 配置项：保留天数（默认 30），可开关；`EventRetryTask` 的 MAX_RETRY 常量复用

## 禁止修改范围
- 不改事件投递/重试主链路语义
- 不清理 `status=0`（在途）与 `status=2` 且 `retry_count<MAX_RETRY`（仍可重试）的行

## 验收标准
- 已投递且超保留期行被清理；在途与未耗尽重试行保留
- 清理任务幂等、可配置、默认不破坏现有重试

## 测试要求
- 单测/集成测试：清理边界（保留期内保留 / 超期清理 / 在途保留 / 重试中保留）

## 输出要求
- 完成后产出 `.ai/docs/20260812-review-followups/changereport-t002.md`；架构决策产出 ADR
