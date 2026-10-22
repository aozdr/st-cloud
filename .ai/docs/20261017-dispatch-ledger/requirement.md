# Dispatch 账本命令化：需求

## 目标

让主线程通过 `loopctl` 命令登记 Dispatch 生命周期，不再手工编辑 State 的 `dispatchLedger`：

- 从已校验的 Envelope 创建 `planned` attempt；
- 在 `spawned` 记录 childId；
- 在 `returned` 记录 `resultRef` 与 `scopeVerified`；
- 保持 `evaluate`、`complete`、`reconcile` 的现有校验不变。

## 用户场景

当前做法是主线程直接改 `.ai/state/<task-id>.yaml` 写入 planned/spawned 记录，再调用 `dispatch-transition`。这会产生三类问题：字段遗漏或拼写错误、重复 dispatchId、以及 returned 后缺少 `resultRef/scopeVerified` 导致 State 校验失败。命令化后，失败在写入前暴露，ledger 与 history 同步产生。

另需用一个只读 reviewer child 跑通真实链路，确认 DSH 原生 `subagent`（DeepSeek 模型）可以作为当前运行时的传输。

## 范围

- 修改 `.ai/scripts/loopctl.ps1`：新增 `dispatch-plan`，扩展 `dispatch-transition`。
- 修改 `.ai/tests/loop-v2`：正反用例。
- 同步 `.ai/knowledge/agent-dispatch-protocol.md` 与 `.ai/templates/dispatch-template.md` 的操作说明。
- 不改 Envelope schema、State schema、业务代码、数据库或产品 API。

## 验收

1. `dispatch-plan` 接受 `-DispatchPath`、`-CriterionId`、可选 `-ChildId`；校验 Envelope 与 State 后写入 planned attempt。
2. 拒绝重复 `dispatchId`、未知 criterion、role/taskType 与 catalog 不匹配、同 criterion 已有未结束 attempt、criterion 已 done。
3. `dispatch-transition` 在 `spawned` 更新并校验 childId；在 `returned` 强制 `-ResultRef` 与 `-ScopeVerified`，并验证结果文件存在。
4. 原有合法前驱链、旧 State 校验和 `evaluate` 行为不变。
5. 单元测试与 loop-v2 全量测试通过；真实 reviewer child 的 dispatch 由主线程 Evaluate。

## 非目标

- 不让 `loopctl` 自动 spawn child。
- 不新增传输通道，不改变 ACK 三元组规则。
- 不迁移或重写历史 State。
