# Loop 操作手册

只在 medium/large 或用户明确要求审计时使用。small 默认直接完成；完整计划只用于复杂或高风险任务。

## 1. 建立任务一次

创建 TASK（include/exclude、验收、验证）及 design.md。design 包含目标、影响、方案、异常、风险和测试计划；large 先明确 requirement.md。只存在实质未决决策时设置 confirmationRequired=true 并请求用户裁决。不为无 UI 任务创建体验派发或 skip 文件。

使用 `.ai/scripts/loopctl.ps1` 初始化，默认新任务 definitionVersion=3：

```powershell
& ./.ai/scripts/loopctl.ps1 init .ai/state/<task-id>.yaml -TaskId <task-id> -Scale medium -Objective '目标' -CompletionCriteria @('可验证完成标准')
```

风险参数按实际设置：-HasUi、-SecuritySensitive、-DatabaseChange、-ApiContractChange、-IrreversibleChange。后四种为 true 时必须 large；核心文件写路径/复杂跨模块也采用 large。State 必须如实记录风险，不用 false 降级。

## 2. 更新修订和证据

用 stale -RevisionKind design/code -RevisionValue <revision> 设置或更新修订。revision 可以是本次变更集的稳定标识或摘要，不使用仍有未提交改动的 HEAD 代替当前内容。先登记 design.md 等真实 artifacts；产物元信息更新不等于门禁完成，只有主线程调用 Evaluate。

主线程结果用 evaluate-direct -ProposalPath <result.json> -Actor workflow-manager，不创建伪 Dispatch：

```json
{
  "taskId": "<task-id>",
  "criterionProposal": {
    "id": "VERIFIED", "outcome": "pass", "by": "workflow-manager",
    "evidenceRef": ".ai/docs/<task-id>/verification.md",
    "validatedRevision": "<code-revision>"
  },
  "verification": {
    "validatedRevision": "<code-revision>",
    "checks": [
      {"dimension":"tests","outcome":"pass","evidenceRef":".ai/docs/<task-id>/verification.md"},
      {"dimension":"selfReview","outcome":"pass","evidenceRef":".ai/docs/<task-id>/verification.md"},
      {"dimension":"knowledge","outcome":"pass","evidenceRef":".ai/docs/<task-id>/verification.md"}
    ]
  }
}
```

risk=true 的 ui/security/database/apiContract/irreversible 追加对应维度的通过证据；允许同一文件按章节存储多个维度，必须分别写明检查内容和结果。tests 可使用相称的静态验证；knowledge 无稳定知识变化时记录原因。数据库维度必须包含 AGENTS.md 规定的 H2 和 MySQL 两次对比。测试失败修复后更新修订，再验证。

large CODE_REVIEW 必须通过独立 reviewer 的真实 Dispatch 结果 Evaluate；独立 review 合并代码、安全及适用风险，不为每个检查维度再启动 Agent。委派机制见 `.ai/knowledge/agent-dispatch-protocol.md`。

## 3. 一次验证并收敛

verification.md 汇总修改、自检、命令/结果、UI/安全/DB 等适用检查、知识同步与剩余风险。测试完成无需等待 review；ACCEPT 等待 DAG 所有依赖。

ACCEPT proposal 的 acceptanceEvidence 对每条 Goal 填 criterion/evidenceRef/validatedRevision。最后调用 complete；缺证据、错修订、未确认事项、blocker 或未结束派发会失败。失败只修实际缺口，不创建形式性任务。

## 4. 检查范围

```powershell
# 当前规则与当前任务
& ./.ai/scripts/verify-loop.ps1 -StatePath .ai/state/<task-id>.yaml
# 修改 Loop 运行工具后运行新旧状态机测试
& ./.ai/tests/loop-v2/run-tests.ps1
# 全量历史审计（显式选择；不作为每轮开发前置）
& ./.ai/scripts/verify-loop.ps1 -AuditHistory
```

预提交/CI 的统一完整入口仍为 `.ai/scripts/run-loop-gate.ps1`。Worktree 生命周期未变，修改该工具才需要额外专项验证。

## 5. 接续

存在有效当前 State 时直接继续缺口；不重建已有完成项。V2 State 默认绑定 `.ai/loop/exit-criteria.v2.yaml`，V3 不静默迁移历史。V1 迁移工具仅供旧版本维护，新工作优先新建 V3 并核对必要证据。
