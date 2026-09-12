# Agent Loop V2 优化设计

> Task ID：`20260911-agent-loop-v2`
> 日期：2026-09-11
> 状态：已确认（2026-09-11）
> 规模：中型（流程与工具链重构，不涉及业务代码、数据库或业务 API）

## 一、目标

将现有 Agent Loop 从“多份文档约束 + Agent 手工维护状态”收敛为“单一协议定义 + 工具执行状态迁移 + 可验证证据”。

完成后应满足：

1. 仅支持当前 OpenAI/Codex 子 Agent 运行时，Dispatch 只通过 `spawn_agent.message` 传递。
2. 删除 File Inbox、第三方模型兼容、双写和顺序认领逻辑，不保留运行时兼容开关。
3. State 不得缺少标准、使用错误依赖或在无验收证据时标记 `done`。
4. Review、Security、Test、Accept 的结论必须绑定当前代码或设计 revision。
5. Worktree 生命周期可重试、可对账，scope 越界和残留资源会让门禁失败。
6. `AGENTS.md` 只保留必须注入每个 Agent 的规则，协议正文只存在一份当前版本。

## 二、已确认决策

| 编号 | 决策 | 用户确认 |
|------|------|----------|
| D1 | 不再兼容第三方模型，File Inbox 运行时彻底移除 | 2026-09-11 已确认 |
| D2 | 一次性切换；完成态历史 State 不迁移，未完成任务迁移到新协议；不保留兼容开关 | 2026-09-11 已确认 |
| D3 | 覆盖全部高优先级问题，按阶段实施，不局限于 Dispatch | 2026-09-11 已确认 |
| D4 | 历史协议文档移动到 archive 留存，不直接删除审计资料 | 2026-09-11 已确认 |
| D5 | 用户确认按本设计进入分阶段实施 | 2026-09-11 已确认 |

## 三、范围

### 修改范围

- `AGENTS.md`
- `.ai/agents/workflow-manager.md`
- `.ai/knowledge/loop-state-model.md`
- `.ai/knowledge/agent-dispatch-protocol.md`
- `.ai/knowledge/loop-verification-checklist.md`
- `.ai/templates/dispatch-template.md`
- `.ai/loop/exit-criteria.yaml`
- `.ai/scripts/verify-loop.ps1`
- `.ai/scripts/worktree.ps1`
- 新增 State/Dispatch schema、状态迁移和调度辅助脚本
- 未完成的 `.ai/state/*.yaml`
- 历史运行时协议归档目录

### 禁止修改范围

- `st-*/**`、`st-web/**`、`st-desktop/**` 业务代码
- `docker/**` 与数据库 schema
- 已完成历史任务的业务产物与结论
- Git 历史提交
- 当前未提交改动中与本计划无关的内容

## 四、目标架构

```text
用户需求
   ↓
Workflow Manager
   ↓
loopctl init / plan
   ↓
TASK + 单一 Dispatch Envelope
   ↓
spawn_agent(message=<Envelope>)
   ↓
DISPATCH_ACK
   ↓
Agent result.json / criterionProposal
   ↓
loopctl evaluate
   ↓
State transition + evidence + DAG stale cascade
```

### 4.1 单一事实源

| 内容 | 唯一事实源 |
|------|------------|
| 规模及退出标准 | `.ai/loop/exit-criteria.yaml` |
| State 结构 | `.ai/schema/loop-state.schema.json` |
| Dispatch 结构 | `.ai/schema/dispatch.schema.json` |
| 当前运行协议 | `.ai/knowledge/agent-dispatch-protocol.md` |
| Agent 最小入口规则 | `AGENTS.md` |
| 状态迁移执行权 | `loopctl` |

其他文档只能引用，不再复制完整规则正文。

### 4.2 State 写入权

子 Agent 不直接写 `exitCriteria.status`，只返回建议：

```yaml
criterionProposal:
  id: CODE_REVIEW
  outcome: pass
  evidenceRef: .ai/runtime/results/<dispatchId>.json
  validatedRevision: <sha-or-digest>
```

只有 Workflow Manager 调用 `loopctl evaluate` 后才能改变 State。

### 4.3 状态与证据

State 增加：

```yaml
schemaVersion: 2
definitionVersion: 2
revision:
  design: <digest>
  code: <git-sha-or-worktree-commit>
acceptanceEvidence: []
dispatchLedger: []
scaleDecision:
  rulesMatched: []
  reason: "..."
```

每个已完成标准记录：

- `by`
- `dispatchId`
- `evidenceRef`
- `validatedRevision`
- `completedAt`

### 4.4 状态语义

- `pending`：从未完成。
- `in_progress`：正在执行。
- `done`：证据完整并由 Evaluate 通过。
- `stale`：曾完成，但输入 revision 已变化。
- `blocked`：存在可定位的阻塞。
- `skipped`：仅限定义允许的条件项，并有 `skipReason/approvedBy/evidenceRef`。

`incomplete` 的恢复路径固定为：

```text
incomplete → running → done
           └→ abandoned
```

artifact 变为 `missing` 时，其提供的标准及全部 DAG 后继自动转为 `stale`。

## 五、分阶段实施

### Phase 0：恢复当前记录真实性

目标：先建立可信基线，再开始重构。

实施：

1. 将 `20260910-ai-process-optimization` 保持为未完成，禁止继续标注“R1 已落地”。
2. 完成或明确取消其悬空 CODE_REVIEW TASK。
3. 修正当前 State 中错误的 `dependsOn`。
4. 让现有 `verify-loop.ps1` 恢复退出码 0。
5. 检查并明确记录 `core.hooksPath` 是否实际安装。

验收：

- `verify-loop.ps1` 为 0。
- 文档、State、Git 配置描述与真实状态一致。

### Phase 1：State Schema 与 False Green 门禁

目标：任何非法 State 都不能通过。

实施：

1. 新增正式 State/Dispatch schema，停止使用正则模拟 YAML 语义。
2. 新 State 强制 `schemaVersion`；历史完成态标 `legacy: true` 后只读。
3. 校验 State 的标准集合与 scale 定义精确一致。
4. 校验 ID 唯一、dependsOn 精确一致、done 节点依赖已满足。
5. 修复 SECURITY_REVIEW：large 必选，medium 才允许条件跳过。
6. 确认型标准缺 `userConfirmedAt`、确认人或 artifact 时直接失败。
7. 新 State 缺 `by`、证据或存在 open/escalated blocker 时不得 done。

验收反例：

- 缺 ACCEPT；
- 额外 QUALITY_GATE；
- 错误 dependsOn；
- 缺用户确认；
- 同一执行者实现和验收；
- open blocker 下标 done。

以上必须全部被拒绝。

### Phase 2：状态迁移工具化

目标：禁止 Agent 手写终态。

实施：

1. 新增 `loopctl`：`init`、`validate`、`propose`、`evaluate`、`stale`、`complete`、`reconcile`。
2. 每次状态改变写结构化 history event，不再依赖自由文本解析。
3. 引入 design/code revision 与 `validatedRevision`。
4. 从 canonical DAG 自动计算 stale cascade。
5. 增加逐项 `acceptanceEvidence`，ACCEPT 必须覆盖全部 completion criteria。
6. blocker 使用稳定 fingerprint，记录 `repairAttempts[]`。

验收：

- 代码 revision 改变后 Review/Security/Test/Accept 自动 stale。
- 旧 revision 证据不能复用。
- incomplete 可以恢复或废弃，不再成为死角。

### Phase 3：Direct Message Dispatch

目标：只保留当前可靠的 Codex 派发路径。

实施：

1. Dispatch Envelope 由 schema 生成和验证。
2. 唯一传输方式为 `spawn_agent(message=<完整 Envelope>)`。
3. 删除 `.ai/dispatch/` File Inbox 运行时、认领逻辑及双写规则。
4. 删除 `taskCode` 作为认领主键的语义；保留时仅作可读标签。
5. ACK 只验证 `dispatchId/taskId/role` 与主线程记录一致。
6. 每次派发写 `dispatchLedger`：planned、spawned、acked、running、returned、evaluated、failed。
7. 每个 attempt 使用新 dispatchId；同一 TASK 使用稳定 idempotencyKey。
8. 子 Agent 每个任务写独立结果文件，主线程串行合并 changereport，消除共享追加竞态。

验收：

- 模板无法生成缺字段 Envelope。
- ACK 错配、无 ACK、ACK_ONLY、重复 attempt 均有确定状态和恢复路径。
- 仓库中不存在活跃 File Inbox 分支。

### Phase 4：Worktree 生命周期事务化

目标：实现任务可以安全重试和恢复。

实施：

1. 拆分 `prepare → commit → merge → verify → cleanup`。
2. 记录 `baseSha/commitSha/targetBranch/worktreePath`。
3. merge 前检查主树干净、目标分支正确、HEAD 未漂移。
4. `git add -A` 前机械校验改动路径属于 scope.include 且不命中 exclude。
5. 修复剩余 worktree 统计；strict 模式发现残留返回非零。
6. 并行批次任一 worktree 创建失败则停止批次，不自动降级共享目录。
7. 启动与结束时 reconcile worktree、branch、dispatch ledger 和 Agent 状态。

验收：

- commit 成功但 merge 失败后可以继续重试。
- scope 越界无法提交。
- 孤儿 worktree/branch 会被发现且阻止收敛。

### Phase 5：协议与文档瘦身

目标：消除多版本规则互相覆盖。

实施：

1. `AGENTS.md` 只保留身份判断、主线程入口、安全边界、确认门禁、代码和数据库硬约束。
2. 合并 `workflow-manager.md` 中重复的 V14/V15 追加章节。
3. `parallel-dispatch-runtime-v7/v8.md`、`file-dispatch-runtime.md`、`task-isolation-migration.md` 移入 `.ai/archive/protocols/`。
4. 修复 ACK 顺序、`taskRef/taskRefs`、pending/stale、ACCEPT/KNOWLEDGE 等矛盾。
5. `EXP_DESIGN` 使用 `exp-review.md`，`EXP_ACCEPT` 使用独立 `exp-accept.md`。
6. 静态门禁增加“同一硬规则不得在两个权威文件重复定义”。

验收：

- 当前协议只存在一份。
- AGENTS 与 schema/工具没有重复定义或冲突。
- 历史协议可追溯但不会再被 Agent 当作当前指令。

### Phase 6：CI、迁移与最终验收

目标：完成一次性切换。

实施：

1. 未完成 State 迁移到 schemaVersion 2；完成态历史 State 保持原样并标 legacy。
2. 删除所有活跃 File Inbox 引用和脚本入口。
3. pre-commit 找不到 PowerShell或仓库根时改为失败，不再 fail-open。
4. 在 CI 中强制执行 schema、State、协议引用、worktree strict 校验。
5. 运行正向验证及完整反例集。

验收：

- 本地与 CI 门禁均为 0。
- 全仓搜索无活跃第三方模型/File Inbox 执行路径。
- 所有未完成 State 可被新 Planner 继续推进。
- 无 open blocker、孤儿 worktree 或未评估 dispatch。

## 六、任务拆分与顺序

| TASK | 内容 | 依赖 | 可并行 |
|------|------|------|--------|
| T01 | 恢复基线真实性 | 无 | 否 |
| T02 | Schema 与严格验证器 | T01 | 否 |
| T03 | State transition / evidence / cascade | T02 | 否 |
| T04 | Direct Message Dispatch | T02 | 可与 T03 后半段并行，但文件必须零重叠 |
| T05 | Worktree 事务化 | T02、T04 | 否 |
| T06 | 协议瘦身与历史归档 | T03、T04、T05 | 否 |
| T07 | State 迁移、CI、反例测试、验收 | T03～T06 | 否 |

在 IMPLEMENTED 阶段，每个 TASK 单独落盘；T03/T04 只有在修改文件完全不重叠时才允许并行。

## 七、风险与控制

| 风险 | 影响 | 控制 |
|------|------|------|
| 一次性切换造成活跃任务不可恢复 | 当前任务中断 | 切换前清点全部 running/incomplete State，生成迁移报告 |
| 新验证器过严，历史文件大量失败 | 无法提交 `.ai` 变更 | 完成态历史 State 只读并显式 legacy；新规则只约束 V2 State |
| 状态工具本身成为单点故障 | Loop 无法推进 | 每个子命令幂等、支持 dry-run、写前备份、失败不修改 State |
| Direct Message 偶发未 ACK | 派发失败 | 新 attempt + 新 dispatchId；先关闭并对账旧 child，不恢复 File Inbox |
| 文档归档后引用失效 | 历史不可追溯 | 归档前生成引用映射，静态校验检查全部新路径 |
| Worktree merge 中断 | 已提交但未合并 | ledger 保存 commitSha，merge 与 cleanup 可独立重试 |

## 八、验证方式

1. Schema 单元测试：合法/非法 State、Dispatch fixture。
2. 状态迁移测试：依赖、条件项、确认、角色分离、blocker、revision cascade。
3. Dispatch dry run：正常 ACK、错 ACK、ACK 超时、ACK_ONLY、重复 attempt。
4. Worktree 故障注入：越界修改、commit 后 merge 失败、残留 worktree。
5. 迁移演练：复制现有未完成 State 到临时目录后执行 dry-run。
6. 全仓静态检查：无活跃 File Inbox 和第三方 provider 分支。
7. CI 与 pre-commit 均执行相同的严格门禁。

## 九、完成标准

- 所有 Phase 验收项通过。
- `verify-loop` 与新 schema/state 测试退出码均为 0。
- 六类 False Green 反例全部失败。
- Review/Test/Accept 证据均绑定当前 revision。
- 未完成任务全部迁移；完成态历史记录未被篡改。
- File Inbox 与第三方模型兼容只存在于 archive 历史说明中。
- 用户对最终迁移报告和验收清单确认通过。

## 十、遗留问题点

无。关键边界已在 Grill Me 拷打中由用户确认：不兼容第三方模型、一次性切换、历史协议归档、覆盖全部高优先级问题并分阶段实施。
