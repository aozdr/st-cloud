# TASK：Agent Loop V2 State Schema 与状态引擎

## 元信息

- Task ID: `TASK-20260911-agent-loop-v2-state-engine`
- State: `.ai/state/20260911-agent-loop-v2.yaml`
- Design: `.ai/docs/20260911-agent-loop-v2/design.md`
- Testcases: `.ai/docs/20260911-agent-loop-v2/testcases.md`
- Agent: executor（taskType=implement）

## 目标

实现正式 State/Dispatch schema、严格 State 校验、证据/revision/cascade 语义和状态迁移命令，堵住 False Green。

## 修改范围

- `.ai/schema/**`
- `.ai/scripts/loopctl.ps1`
- `.ai/scripts/verify-loop.ps1`
- `.ai/loop/exit-criteria.yaml`
- `.ai/tests/loop-v2/**`

## 禁止修改范围

- `AGENTS.md`
- `.ai/agents/**`、`.ai/knowledge/**`、`.ai/templates/**`
- `.ai/scripts/worktree.ps1`
- `.ai/state/**`
- 业务代码与数据库

## 验收标准

- 精确校验 scale 对应的 criterion ID 集、唯一性、dependsOn 和依赖状态
- large SECURITY_REVIEW 必选；medium 支持有证据的 skipped
- 确认、职责分离、open blocker、acceptanceEvidence、revision 证据可校验
- 提供幂等、支持 dry-run 的状态迁移入口及 DAG stale cascade
- 六类 False Green 反例全部失败
- 保留现有 verify-loop 的有效检查

## 验证

- 运行 `.ai/tests/loop-v2/` 下 State/Schema 测试
- 运行 `.ai/scripts/verify-loop.ps1`

## 输出

- 独立结果写入 `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-SE-01.md`
