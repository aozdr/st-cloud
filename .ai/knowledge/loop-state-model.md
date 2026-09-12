# Agent Loop 状态模型（Loop State Model）

> 本文件定义星云盘 Agent Loop 的核心数据结构：**Loop State**。编排器（Workflow Manager）与所有 Agent 围绕同一份 State 协作，取代旧的"阶段间单向传递文档"模式。本文件是 Loop 架构的事实源，AGENTS.md、workflow-manager.md、feature-development.md 均引用此处定义。

## 设计原则

- **单一事实源**：一份 State 描述任务的全量进度，任何 Agent 都读它、只追加增量（Delta）
- **状态驱动而非位置驱动**：下一步动作由当前 State 推导，不由"流水线第 N 步"决定
- **退出标准驱动收敛**：循环持续到所有 exitCriteria 满足，而非走完阶段清单
- **历史可追溯**：每轮记录 agent/action/delta，形成审计链
- **持久化可恢复**：State 落盘为文件，跨轮次/跨会话可重读，崩溃或换会话后能从断点继续

## Loop State 结构

```yaml
goal:
  objective: "客观目标"
  scope: "影响范围"
  completionCriteria: ["完成标准1", "完成标准2"]

scale: large | medium | small   # 任务规模，决定 exitCriteria 集

exitCriteria:                    # 质量门禁清单，编排器在 Evaluate 段勾选
  - id: REQ_ANALYSIS
    desc: "需求已分析 + UI/UX 设计文档已产出（executor 的 requirement+ui-design 协作），经 Grill Me 拷打收敛（遗留问题点 ≤3 写入文档）并经用户确认"
    status: pending | in_progress | done | blocked | stale
    dependsOn: []                # 依赖哪些其他标准先满足
  - id: IMPACT_ANALYSIS
    desc: "影响范围已分析"
    status: pending | in_progress | done | blocked | stale
    dependsOn: [REQ_ANALYSIS]

artifacts:                       # 产出物，Agent 写入
  discovery:  { status: pending, ref: ".ai/docs/<task-id>/discovery.md", owner: "executor" }  # 可选上游需求发现报告（taskType=discovery）
  prd:        { status: pending|in_progress|done, ref: ".ai/docs/<task-id>/requirement.md", owner: "executor" }  # ref 必须为已落盘真实路径（taskType=requirement）
  uiSpec:     { status: pending, ref: ".ai/docs/<task-id>/uispec.md", owner: "executor" }  # UI/UX 设计文档，REQ_ANALYSIS 阶段与 requirement 协作产出（taskType=ui-design）
  design:     { status: pending, ref: ".ai/docs/<task-id>/design.md", owner: "executor" }  # 前后端合用同一份，分章节（taskType=design）
  archReview: { status: pending, ref: ".ai/docs/<task-id>/architecture-review.md", owner: "executor" }  # 架构设计评审，大型任务 TECH_DESIGN 前置（先于 design，taskType=architecture）
  testcases:  { status: pending, ref: ".ai/docs/<task-id>/testcases.md", owner: "tester" }
  code:       { status: pending, ref: "", owner: "executor" }
  review:     { status: pending, ref: ".ai/docs/<task-id>/codereview.md", owner: "reviewer" }
  security:   { status: pending, ref: ".ai/docs/<task-id>/security.md", owner: "reviewer" }
  testReport: { status: pending, ref: ".ai/docs/<task-id>/testreport.md", owner: "tester" }
  knowledge:  { status: pending, ref: "", owner: "" }
  task:       { status: pending, ref: ".ai/tasks/TASK-xxx.md", owner: "workflow-manager" }  # 开发前置产物：中型以上 IMPLEMENTED 前必须落盘（xxx 为任务内序号）
  changereport: { status: pending, ref: ".ai/docs/<task-id>/changereport.md", owner: "executor" }  # 编码完成后的变更汇总（taskType=implement）

blockers:                        # 活跃阻塞
  - id: B1
    desc: "鉴权缺失"
    raisedBy: "reviewer"
    raisedAt: 6                  # 第几轮触发
    status: open | resolved | escalated
    attempts: 1                  # 修复重试次数：创建时为 0，每次派发修复后仍 open 则 +1，>=3 升级（示例 1=已失败一次）

history:                         # 滚动审计链（保留近 N 轮 + 更早摘要）
  - iter: 6
    phase: "review"
    agent: "reviewer"
    action: "审查后端鉴权"
    delta: "新增 blocker B1"
    result: "发现 /share 接口缺权限校验"

iteration: 6                     # 当前轮次
status: running | blocked_escalation | done | incomplete | abandoned   # 取值定义见 .ai/loop/exit-criteria.yaml
```

### 状态取值与回填记录

State 顶层 `status` 取值（与 `.ai/loop/exit-criteria.yaml` 的 `stateStatus` 一致）：

| 取值 | 含义 |
|------|------|
| `running` | 循环进行中 |
| `blocked_escalation` | 触发升级条件，暂停待人工裁决 |
| `done` | 全部 exitCriteria done **且** 所有标记 done 的产物 ref 均指向真实文件 |
| `incomplete` | 曾标 done 但产物缺失/门禁不实，回填后保留 |
| `abandoned` | 中途放弃或长期停滞（默认 14 天）未收敛 |

产物状态取值：`pending` / `in_progress` / `done` / `stale` / `missing`。`missing` 表示**曾声称完成但文件不存在**，与从未开始的 `pending` 区分。

回填记录固定格式（置于顶层 `status` 之后）：

```yaml
backfill:
  date: "YYYY-MM-DD"
  from: "done"
  reason: "产物未落盘，done 前置不成立，回填为 incomplete"
  missing:
    - ".ai/docs/<task-id>/testreport.md"
```

### 用户确认状态（需求/设计门禁）

`requirement.md` 与 `design.md` 属确认型 artifact，State 中记录确认状态：

```yaml
artifacts:
  prd:     { status: pending, ref: ".../requirement.md", owner: "executor",
             grillPoints: ["P1:...", "P2:..."], userConfirmedAt: null }
  design:  { status: pending, ref: ".../design.md", owner: "executor",
             grillPoints: ["P1:..."], userConfirmedAt: null }
```

- `grillPoints`：Grill Me 拷打后遗留问题点（≤3 个），与文档「遗留问题点」章节一致
- `userConfirmedAt`：用户确认时间；为 null 时对应 exitCriteria 不得标 done

## State 持久化与加载

> 配置驱动型 Loop 无运行时代码，AI 即运行时。State 必须落盘为文件，保证每轮 Observe 能重读全量进度，跨会话/换任务后可从断点恢复，避免上下文丢失导致状态漂移。

### 存储位置

- 每个任务一份 State 文件：`.ai/state/<task-id>.yaml`
- `task-id` 由编排器初始化时生成（建议 `YYYYMMDD-<slug>`，如 `20260809-share-permission`）
- 同一任务全程读写同一文件，不另建

### 读写时机

- **初始化（iter=0）**：编排器创建 State 后立即写入 `.ai/state/<task-id>.yaml`
- **每轮 Observe**：编排器第一步读取该文件作为本轮 State 事实源（禁止凭记忆推导 State）
- **每轮 Evaluate**：应用 Delta 后立即覆写该文件（落盘优先于进入下一轮）
- **升级暂停**：`status=blocked_escalation` 时文件保留，人工裁决后编排器读文件恢复

### 文件结构

State 文件即上文「Loop State 结构」定义的 YAML，顶部增加 `taskId` 字段：

```yaml
taskId: "20260809-share-permission"
goal: { ... }
scale: large
exitCriteria: [ ... ]
artifacts: { ... }
blockers: [ ... }
history: [ ... ]
iteration: 0
status: running
```

### 并发 Delta 合并

并行派发的 Agent 各自返回 Delta。编排器按**串行落盘**合并：

- exitCriteria：任一 Agent 标 done 即写 done；若两个 Agent 对同一项结论冲突（一 pass 一 fail），以 fail 为准并记 blocker
- artifacts：后到者追加/更新 ref，不覆盖已 done 的关键结论，仅更新 status/ref
- blockers：合并去重（同 desc 视为同一 blocker，attempts 不重置）

### 任务生命周期

- 任务收敛 `status=done` 后，State 文件保留作为审计记录，不移除
- 中途放弃/废弃的任务，编排器将 `status` 改为 `abandoned`（新增状态值）后保留文件
- `.ai/state/` 仅存活跃与归档 State 文件，不放其他内容

## 退出标准集（按规模）

> **项数唯一权威**：`.ai/loop/exit-criteria.yaml`。本文件与 `workflows/feature-development.md`、`knowledge/loop-verification-checklist.md` 的规模项数表述由 `.ai/scripts/verify-loop.ps1` 强制一致，改一处必须三处同步。

### 大型任务（12 项）

具体 ID、依赖和是否可跳过只从 `.ai/loop/exit-criteria.yaml` 读取。本节不复制 DAG，避免文档与机器定义分叉。

> `status: done` 仅当该规模下**所有 exitCriteria 均 done**（ACCEPT 为最后一项，是最终收敛点）。

### 中型任务（8 项，含 1 项条件标准）

具体 ID 与依赖只引用 canonical 定义。`SECURITY_REVIEW` 是唯一条件项；只有 `.ai/loop/exit-criteria.yaml` 声明可跳过且具备 skip 证据时，Evaluate 才可接受。

> **SECURITY_REVIEW 为条件项**：仅当本次变更涉及权限校验、分享访问控制、文件操作、配额计算等云盘安全敏感逻辑时启用。编排器在初始化 State 时根据变更范围判断是否激活；不涉及安全敏感逻辑的纯展示/排序类中型任务可跳过此标准（在 State 中标注 `skipReason`）。

### 小型任务（4 项）

具体 ID 与依赖只引用 canonical 定义；ACCEPT 仍是唯一终点。

## 门禁依赖规则（不可降级）

Evaluate 段必须强制检查 dependsOn：

- 依赖未满足的标准不得标记 done（例：CODE_REVIEW 依赖 IMPLEMENTED，代码没实现完不能算 Review 通过）
- REQ_ANALYSIS 要求 PRD 与 UI/UX 设计文档（uiSpec）**均产出且落盘到 `.ai/docs/<task-id>/`**（ref 指向真实文件）、
  经 Grill Me 拷打收敛（遗留问题点 ≤3 写入文档）并**经用户确认**，缺一不可（executor 的 requirement+ui-design 协作，实现以此为唯一依据）
- **需求/设计确认门禁（20260815 起）**：`requirement.md`（大型 REQ_ANALYSIS）与 `design.md`
  （大型 TECH_DESIGN / 中型 DESIGN）必须经用户确认才能标 done；未确认不得推进任何下游标准，
  编排器在 State 中记录 `userConfirmedAt`
- TECH_DESIGN 依赖 IMPACT_ANALYSIS 与 EXP_DESIGN（体验评审必须先于技术设计，V3 原顺序保留）
- 大型任务 TECH_DESIGN 分两步：先产出架构设计评审（`architecture-review.md`，executor 主笔，taskType=architecture，输出标准 `docs/newList/ai-architecture-review-standard.md`），评审通过后再产出程序设计文档（`design.md`）；架构评审为程序设计前置条件
- IMPLEMENTED 依赖 TECH_DESIGN 与 TESTCASES（大型任务未编写测试用例不得进入开发）
- 中型以上任务 IMPLEMENTED 前必须存在对应 Task 文件（`artifacts.task.ref` 指向 `.ai/tasks/` 真实路径），未落盘不得标 IMPLEMENTED done（小型直接执行除外）
- TEST_PASS 依赖 CODE_REVIEW 与 SECURITY_REVIEW（安全审查须先于测试，安全修复改代码后测试才有意义）
- ACCEPT 是最终收敛点，未通过不得 `status: done`

这些规则取代旧版"未完成 X 进入 Y"的线性禁止项，但约束力等价。

## 代码变更失效规则（rework cascade）

**核心规则**：当代码因 rework 发生变更（如 Code Review / Security Review / 体验验收 / 测试发现问题，导致工程师修改代码），`IMPLEMENTED` 被重开，其全部下游标准自动级联回退为 `pending`。

级联范围由 `.ai/loop/exit-criteria.yaml` 的反向依赖图计算，不在文档或脚本中维护第二份列表。以大型任务为例，IMPLEMENTED 变化会先使 CODE_REVIEW、SECURITY_REVIEW、EXP_ACCEPT 失效，再沿 DAG 传递到 TEST_PASS、KNOWLEDGE，最终到 ACCEPT。

编排器在 Evaluate 段执行级联回退后，下一轮 Plan 重新派发对应 Agent 复检所有回退标准。这确保 rework 后所有质量门重新验证，而非沿用针对旧代码的 stale 结论。

> 这是 Loop 的关键正确性保证：rework 改了代码，就必须重走所有受影响的质量门，而非只复检发现问题的那一个。

## 规模升降级路径

任务进行中，用户或编排器发现实际规模与初始判定不符时，可中途升降档。切换规则：

### 升档（small->medium / medium->large / small->large）

1. 加载目标规模的完整 exitCriteria 集，替换当前集
2. **同 id 标准**：新旧集都有的（如 IMPLEMENTED/KNOWLEDGE），若已 done 且语义一致，保留 done
3. **新增标准**：目标集独有的一律 pending
4. **依赖倒挂处理**：若新增的前置标准（如 TESTCASES）本应在已 done 的 IMPLEMENTED 之前，IMPLEMENTED 不回退，但该前置标准标 `lateAdded: true`，须补做完成后才允许推进其下游（CODE_REVIEW 等）；在 State 中记录倒挂原因
5. 升档不重置 iteration 与 history

### 降档（large->medium 等）

1. 原则上不自动降档，避免借降档偷工
2. 用户显式要求时，超出目标集的标准标 `skipReason: "降档跳过"` 保留记录，不删除
3. 已 done 的同 id 标准保留

> 升降档后编排器在 Evaluate 段重新检查全量 dependsOn，必要时触发 cascade。

## 死循环与升级

### attempts 自增语义

- blocker 创建时 `attempts = 0`
- 编排器每轮为某 open blocker 派发 Agent 修复后，在 Evaluate 段检查该 blocker：
  - 仍 open（修复无效或未完全解决）-> `attempts++`
  - 已 resolved -> 不自增，blocker 关闭
- 仅"派发修复后仍未解决"才计数；非修复轮（派发其他无关 Agent）不触发自增

### 升级条件

- 同一 blocker `attempts >= 3` 且仍 open -> 该 blocker `status = escalated`，编排器暂停 Loop，交人工处理
- `iteration` 超过规模上限（large=40, medium=15, small=5）-> 暂停升级人工
- 升级后 `State.status = blocked_escalation`，待人工裁决后恢复 `running`

## history 滚动策略

- 保留最近 8 轮完整记录
- 更早的轮次压缩为一行摘要（如 `iter 1-5: 需求->设计->测试用例->编码`）
- 避免上下文膨胀，同时保留审计可追溯性


## Dispatch Ledger 与 Evaluate 权限

当前 Dispatch 协议只定义于 `.ai/knowledge/agent-dispatch-protocol.md`，结构只定义于 `.ai/schema/dispatch.schema.json`。State 记录每次 attempt 的 ledger：

```yaml
dispatchLedger:
  - dispatchId: "DISPATCH-20260911-001"  # 每次 attempt 唯一
    taskId: "TASK-001"
    idempotencyKey: "TASK-001"          # 同一 TASK 跨 attempt 稳定
    role: executor
    childId: "agent-runtime-id"
    status: planned | spawned | acked | running | returned | evaluated | failed
    resultRef: ".ai/runtime/results/DISPATCH-20260911-001.json"
```

ACK 只核对 `dispatchId/taskId/role`。重派保持 `taskId/idempotencyKey` 不变，并生成新的 `dispatchId` 和 child。旧 attempt 的迟到结果保留审计，但不得覆盖当前 attempt。

子 Agent 不写 State，只返回独立结果和：

```yaml
criterionProposal:
  id: IMPLEMENTED
  outcome: pass | fail | blocked
  evidenceRef: ".ai/runtime/results/DISPATCH-20260911-001.json"
  validatedRevision: "<design-or-code-revision>"
```

只有 Workflow Manager 可以执行 Evaluate：校验 attempt、scope、产物、证据、revision、DAG 和职责分离后，再改变 exitCriteria、artifact、blocker、history 或顶层 status。

### Dispatch failure 不属于业务 blocker

`DISPATCH_INVALID`、无 ACK、ACK 错配、ACK_ONLY 和结果缺失是调度失败：

- 只更新 dispatchLedger；
- 不增加业务 blocker attempts；
- 不污染业务 exitCriteria；
- 新 attempt 使用新 dispatchId 重派，不启用备用投递路径。

### Stale 与 Pending 的区别

- `pending`：从未完成；
- `stale`：曾经完成，但输入代码/设计发生变化，旧结论失效；
- `blocked`：当前因 blocker 无法继续。

代码变更后优先使用 `stale`，保留 `staleReason` 与 history。


## Task Isolation State

State 不应被整份复制给子 Agent。Workflow Manager 应在 Dispatch 时生成 `stateSnapshot`，仅包含当前 TASK 所需的：

- iteration
- relevant exitCriterion
- relevant dependencies
- relevant blockers

完整 State 仍只由 Workflow Manager 负责读取、Evaluate 和持久化。子 Agent 的 proposal 不是已应用的 State Delta。


## 实现阶段与并行派发

`IMPLEMENTED` 必须区分 `in_progress` 与 `done`：

- `in_progress`：已满足 TECH_DESIGN/TESTCASES，已进入编码阶段，TASK 已落盘并正在执行。
- `done`：所有实现 TASK 均已完成，且 Workflow Manager Evaluate 通过。

前后端并行时，State 中必须能对应到独立的 TASK / dispatch：

```yaml
implementationBatch:
  batchId: BATCH-xxx
  status: running
  tasks:
    - taskId: TASK-FE-001
      dispatchId: DISPATCH-FE-001
      owner: executor
      status: running
    - taskId: TASK-BE-001
      dispatchId: DISPATCH-BE-001
      owner: executor
      status: running
```

Workflow Manager 只能在两个 TASK 都 Evaluate 通过后将 `IMPLEMENTED` 标记为 `done`。
