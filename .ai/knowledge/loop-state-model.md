# Loop State 模型

结构唯一维护在 `.ai/schema/loop-state.schema.json`；DAG、归属和必需产物唯一维护在 `.ai/loop/exit-criteria.yaml`，不在文档复制节点表与数量。

## 版本与持久化

small 默认无 State。需要持久化的任务每任务一份 JSON-compatible YAML：`.ai/state/<task-id>.yaml`。主线程初始化一次，按发生的状态变化写入，不逐轮重建。

schemaVersion=2 表示数据结构；definitionVersion=3 表示精简流程。definitionVersion=2 的 State 由 `.ai/loop/exit-criteria.v2.yaml` 校验，历史文件和结论不被静默覆盖。显式错误定义仍拒绝，不自动修正。

goal 包含 objective、scope 和 completionCriteria。V3 risk 的 ui/security/database/apiContract/irreversible 必须是布尔值；敏感风险触发 large。实现、验证和验收绑定 code revision，需求/设计绑定 design revision。

## 证据与状态

- exitCriteria：只由主线程 Evaluate，要求真实执行者、证据、修订、DAG 前置和必要确认。
- artifacts：真实 ref；V3 完成单项即检查 catalog 必需产物。设计含测试计划，verification.md 合并变更/自检/测试/知识记录。
- verification：VERIFIED 的分维度 checks；tests/selfReview/knowledge 必需，风险标记触发其他维度。checks 通过证据绑定当前 code revision。
- acceptanceEvidence：每条 Goal 都有当前 code revision 的证据。
- dispatchLedger：仅记录实际派发；无派发时为空，不为直接执行伪造 child。
- blockers：稳定 fingerprint 和真实修复 attempts；外部阻塞如实记录。
- history：记录变更摘要，不能把包含 history 的 State 嵌入事件，否则产生递归膨胀。

code/design/artifact 变化用 loopctl stale 使旧证据失效；测试与 review 均从实现推进，最终 ACCEPT 汇合。具体操作见 `.ai/knowledge/agent-loop-runbook.md`。

V3 只有 CODE_REVIEW 要求实现者与审查者身份分离；不接受单人授权代替。主线程测试/验收是自检，不能声称独立。V2 原职责分离与单人授权行为保留。
