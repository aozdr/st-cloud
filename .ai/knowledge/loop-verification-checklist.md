# Loop 验证

只对当前改动和当前任务验证。默认静态检查不扫描历史任务；历史审计显式使用 -AuditHistory。

```powershell
& ./.ai/scripts/verify-loop.ps1 -StatePath .ai/state/<task-id>.yaml
& ./.ai/tests/loop-v2/run-tests.ps1
# 预提交/CI 完整入口（含 Worktree）
& ./.ai/scripts/run-loop-gate.ps1
```

## 必须成立

- 分级按实际风险；small 默认无流程产物，medium 单 Agent，large 独立 review。
- definition/schema/DAG 一致；V2 State 按绑定定义保留原规则。
- 测试可在实现后立即完成；最终 ACCEPT 仍汇合所有必要检查。
- 每个 done 项有真实证据和当前 revision，catalog 必需产物存在。
- risk 触发的验证维度都有通过证据；数据库保留 H2 与两次 MySQL 对比。
- 未决实质决策没有驱动依赖实现；已有授权不重复确认。
- scope 最小化，保留用户改动；子任务无越界，不互派、不写 State。
- 单人自检不伪装独立 review；V3 large 独立检查无法用单人授权绕过。
- 代码变化使旧代码证据 stale；open blocker、未完成派发或 Goal 缺项拒绝完成。
- history 为摘要，不递归嵌套 State；没有文件充数、伪派发或强制八段空表。

## 效率评估

比较代表性 small/medium/large 任务的必需产物、派发次数、工具调用、重复测试、用户等待、耗时/Token 和失败率。先用状态机正反例保证门禁，再观察真实任务；未采样的耗时/Token 不报告百分比提升。保留质量约束，若精简导致失败率上升再有针对性补充指令。

历史 dry-run 仅作案例，不作为当前任务步骤。
