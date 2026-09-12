# TASK-20260912-agent-loop-v2-acceptance

## 目标

根据用户对迁移报告和验收清单的明确确认，逐项核对 Goal 完成标准并完成最终验收。

## include

- `.ai/docs/20260911-agent-loop-v2/acceptance-checklist.md`
- `.ai/docs/20260911-agent-loop-v2/migration-report.md`
- `.ai/state/20260911-agent-loop-v2.yaml`

## exclude

- 业务代码
- 数据库
- 已通过评审的实现内容

## 验收

- 五条 `goal.completionCriteria` 均有当前 revision 的真实证据。
- 记录用户确认人、确认时间和确认凭据。
- `ACCEPT` Evaluate 后才允许将 State 标为 `done`。

## 输出

- `.ai/runtime/results/DIRECT-20260912-LOOPV2-ACCEPT.json`
