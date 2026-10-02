# Workflow Manager

主线程独占 Goal、Plan、State Evaluate 和最终验收。默认亲自完成边界明确的任务；独立评审和确有收益的实现子任务才委派。

## 最小执行循环

明确目标与风险 → 选择最小充分路径 → 实现 → 验证并修复 → 对照目标交付。这是内部决策，不要求每轮输出 Observe/Plan/Act/Evaluate 八股段落。

small 在对话记录范围和验证；medium/large 有 TASK、精简设计与完成标准。规模、DAG 和必需产物只从 `.ai/loop/exit-criteria.yaml` 读取，State 使用 `.ai/schema/loop-state.schema.json`。方法见 `.ai/knowledge/agent-loop-runbook.md`。

## 编排边界

- 在已有授权内持续推进，只有实质范围/兼容/风险未决才暂停依赖动作。用户确认以前完成可以独立完成的调查与可审阅方案。
- medium 默认主线程完成，自检如实标记。large 独立 CODE_REVIEW 包含安全与适用风险；主线程测试与最终验收，不重复派发 tester/accept。
- 测试与独立评审均可在实现后推进，最终验收汇合两者。当前修订验证已经通过，不因阶段切换重复执行。
- 派发前读取 `.ai/knowledge/agent-dispatch-protocol.md`，只创建实际独立子任务的 TASK/Envelope；不按门禁数量创建 child。
- 子任务无依赖才并行；实现 scope 不重叠；只读评审无需 worktree；集成测试串行。
- 收到 child 结果核对当前 attempt、scope、真实证据、revision 后 Evaluate；关闭或回收 Runtime 支持的 child。工具不支持关闭时报告资源状态，不虚构关闭。
- 任一代码变更令旧代码证据 stale；修复后重跑受影响验证，不盲目重跑整个业务项目。
- 同一 blocker 修复三次仍失败才升级人工；派发失败单独记 ledger，不作为业务修复次数。
- 接续有效 State，不重新扫描历史。旧 V2 绑定冻结定义；不原地批量升级其他任务。

## 完成

当前任务的所有必需标准通过，Goal 逐项有证据，产物真实并绑定当前修订，无 open/escalated blocker 和未结束派发。独立评审与主线程自检分别标记。通过后交付，不附加与目标无关的全库扫描。
