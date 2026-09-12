# TASK：Agent Loop V2 测试用例设计

## 元信息

- Task ID: `TASK-20260911-agent-loop-v2-testcases`
- 关联 State: `.ai/state/20260911-agent-loop-v2.yaml`
- 关联设计: `.ai/docs/20260911-agent-loop-v2/design.md`
- 归属 Agent: tester（taskType=testcases）
- 日期: 2026-09-11

## 目标

为 Agent Loop V2 的 State Schema、状态迁移、Direct Message Dispatch、Worktree 生命周期、协议迁移和最终验收设计可执行测试用例。

## 修改范围

- 只允许新增或修改 `.ai/docs/20260911-agent-loop-v2/testcases.md`

## 禁止修改范围

- 禁止修改其他 `.ai/**` 文件
- 禁止修改业务代码、数据库、Git 配置
- 禁止执行 git 写操作

## 验收标准

- [ ] 覆盖 design.md Phase 0～6
- [ ] 覆盖六类 False Green 反例
- [ ] 覆盖 revision cascade、ACK 异常、重复 attempt、scope 越界和孤儿 worktree
- [ ] 每个完成标准至少有一条用例
- [ ] 区分静态、单元、集成、迁移和人工验收

## 验证

- 对照 design.md 的目标、各 Phase 验收与完成标准逐项检查覆盖关系

## 输出

- `.ai/docs/20260911-agent-loop-v2/testcases.md`
