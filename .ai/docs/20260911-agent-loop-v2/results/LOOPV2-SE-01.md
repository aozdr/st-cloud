# LOOPV2-SE-01 实现结果

## 背景

Agent Loop V2 需要用机器校验与受控状态迁移阻止标准缺失、依赖错误、无证据完成和旧 revision 复用。

## 输入

- TASK：`.ai/tasks/TASK-20260911-agent-loop-v2-state-engine.md`
- Design：`.ai/docs/20260911-agent-loop-v2/design.md`
- Testcases：`.ai/docs/20260911-agent-loop-v2/testcases.md`
- Canonical definition：`.ai/loop/exit-criteria.yaml`
- State：只读；本任务未修改 `.ai/state/**`

## 分析

现有 `verify-loop.ps1` 已覆盖拓扑、文档引用、历史 State 和工程配置检查，但仍允许标准集合不精确、条件项语义跨规模泄漏、done 缺证据及 revision 失配。V2 通过正式 Schema 与 `loopctl` 的语义校验补足这些门禁；State 使用 JSON-compatible YAML，使 PowerShell 5.1 环境不依赖外部 YAML 模块。

## 决策

1. 新增 `loop-state.schema.json` 与 `dispatch.schema.json`，固定 V2 必填字段和禁止额外字段。
2. `exit-criteria.yaml` 升级到 definition version 2：large 的 SECURITY_REVIEW 必选，medium 仅可凭 `skipReason/approvedBy/evidenceRef` 跳过。
3. `loopctl validate` 严格校验 criterion 集合、唯一性、canonical dependsOn、done 依赖、确认、职责分离、blocker、逐项 acceptanceEvidence 和 revision。
4. `loopctl stale` 根据 canonical DAG 对 code/design/artifact 变化执行 stale cascade；相同 revision 幂等返回 `UNCHANGED`，写操作支持 `-DryRun` 与同目录临时文件替换。
5. `verify-loop.ps1` 保留原有检查，并加入 SECURITY_REVIEW 规模语义和 V2 State 严格校验入口。

## 验证结果

### Loop V2 单元测试

命令：`.ai/tests/loop-v2/run-tests.ps1`

结果：退出码 `0`，10 项通过：

- TC-FG-01～06 六类 False Green 全部按稳定错误码拒绝；
- 合法 medium State 通过；
- medium SECURITY_REVIEW 有证据 skipped 通过；
- code revision 改变后 CODE_REVIEW、SECURITY_REVIEW、TEST_PASS、KNOWLEDGE、ACCEPT stale，顶层状态恢复 running；
- 旧 revision proposal 被 `REVISION_MISMATCH` 拒绝。

### 仓库总门禁

命令：`.ai/scripts/verify-loop.ps1`

结果：退出码 `1`，`FAIL=5`、`WARN=18`。本任务新增的退出标准结构、依赖图和安全审查规模语义均通过。失败均为既有/并行任务产物尚未落盘的悬空引用：

- `.ai/docs/20260910-ai-process-optimization/codereview.md`（2 次引用）；
- `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-DP-01.md`；
- `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-SE-01.md`（本报告落盘后已解除）；
- `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-WT-01.md`。

因此本报告落盘后的预期剩余为 4 个悬空引用，需对应并行任务/基线任务产出处理；本任务没有越界修改它们。

## State Delta

- artifacts 新增：
  - `.ai/schema/loop-state.schema.json`
  - `.ai/schema/dispatch.schema.json`
  - `.ai/scripts/loopctl.ps1`
  - `.ai/tests/loop-v2/run-tests.ps1`
  - `.ai/tests/loop-v2/unit/state-engine.tests.ps1`
  - `.ai/docs/20260911-agent-loop-v2/results/LOOPV2-SE-01.md`
- artifacts 更新：
  - `.ai/loop/exit-criteria.yaml`
  - `.ai/scripts/verify-loop.ps1`
- blockers 新增：仓库总门禁仍受 4 个白名单外/并行任务悬空产物阻塞。
- exitCriteria 建议：`IMPLEMENTED` 可标 done；`TEST_PASS` 不应在总门禁归零前标 done。

## 风险

- 无 `ConvertFrom-Yaml` 的环境只接受 JSON-compatible YAML；V2 初始化和工具写回均采用该格式，旧 YAML 继续由原 verify-loop 历史路径读取。
- 本任务未负责 Dispatch 生命周期和 Worktree 对账，它们由相邻 TASK 实现。

## 下一步

1. 相邻 DP/WT TASK 落盘各自结果报告。
2. 处置 20260910 基线缺失的 codereview.md。
3. 串行重跑 `verify-loop.ps1`，再进入统一 Review/Test。

## 变更影响

新 V2 State 在进入后续环节前会受到精确标准、证据和 revision 门禁；旧 State 未被迁移或修改。Dispatch/Worktree Agent 可复用两个 Schema 和 `loopctl validate`，无需复制状态规则。
