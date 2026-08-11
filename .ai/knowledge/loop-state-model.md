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
    desc: "需求已分析 + UI/UX 设计文档已产出（PM + UI 协作，经多方评审定版）"
    status: pending | done
    dependsOn: []                # 依赖哪些其他标准先满足
  - id: IMPACT_ANALYSIS
    desc: "影响范围已分析"
    status: pending | done
    dependsOn: [REQ_ANALYSIS]

artifacts:                       # 产出物，Agent 写入
  discovery:  { status: pending, ref: ".ai/docs/<task-id>/discovery.md", owner: "requirement-discovery" }  # 可选上游需求发现报告
  prd:        { status: pending|in_progress|done, ref: ".ai/docs/<task-id>/requirement.md", owner: "product-manager" }  # ref 必须为已落盘真实路径
  uiSpec:     { status: pending, ref: ".ai/docs/<task-id>/uispec.md", owner: "ui-designer" }  # UI/UX 设计文档，REQ_ANALYSIS 阶段与 PM 协作产出
  design:     { status: pending, ref: ".ai/docs/<task-id>/design.md", owner: "frontend-engineer + backend-engineer" }  # 前后端合用同一份，分章节
  archReview: { status: pending, ref: ".ai/docs/<task-id>/architecture-review.md", owner: "architect" }  # 架构设计评审，大型任务 TECH_DESIGN 前置（先于 design）
  testcases:  { status: pending, ref: ".ai/docs/<task-id>/testcases.md", owner: "tester" }
  code:       { status: pending, ref: "", owner: "frontend-engineer + backend-engineer" }
  review:     { status: pending, ref: ".ai/docs/<task-id>/codereview.md", owner: "reviewer" }
  security:   { status: pending, ref: ".ai/docs/<task-id>/security.md", owner: "security-reviewer" }
  testReport: { status: pending, ref: ".ai/docs/<task-id>/testreport.md", owner: "tester" }
  knowledge:  { status: pending, ref: "", owner: "" }

blockers:                        # 活跃阻塞
  - id: B1
    desc: "鉴权缺失"
    raisedBy: "security-reviewer"
    raisedAt: 6                  # 第几轮触发
    status: open | resolved | escalated
    attempts: 1                  # 修复重试次数：创建时为 0，每次派发修复后仍 open 则 +1，>=3 升级（示例 1=已失败一次）

history:                         # 滚动审计链（保留近 N 轮 + 更早摘要）
  - iter: 6
    phase: "review"
    agent: "security-reviewer"
    action: "审查后端鉴权"
    delta: "新增 blocker B1"
    result: "发现 /share 接口缺权限校验"

iteration: 6                     # 当前轮次
status: running | blocked_escalation | done
```

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

### 大型任务（完整门禁，12 项）

| id | 标准 | 依赖 dependsOn |
|----|------|----------------|
| REQ_ANALYSIS | 需求已分析 + UI/UX 设计文档已产出（PM + UI 协作，多方评审定版） | - |
| IMPACT_ANALYSIS | 影响范围已分析 | REQ_ANALYSIS |
| EXP_DESIGN | 体验评审已通过 | REQ_ANALYSIS |
| TECH_DESIGN | 技术设计已评审 | IMPACT_ANALYSIS, EXP_DESIGN |
| TESTCASES | 测试用例已编写 | TECH_DESIGN |
| IMPLEMENTED | 代码已实现 | TECH_DESIGN, TESTCASES |
| CODE_REVIEW | Code Review 通过 | IMPLEMENTED |
| SECURITY_REVIEW | Security Review 通过 | IMPLEMENTED |
| EXP_ACCEPT | 体验验收通过 | IMPLEMENTED |
| TEST_PASS | 测试执行通过 | CODE_REVIEW, SECURITY_REVIEW |
| QUALITY_GATE | Quality Gate 通过 | TEST_PASS, SECURITY_REVIEW, EXP_ACCEPT |
| KNOWLEDGE | 知识库已更新 | QUALITY_GATE |

> `status: done` 仅当该规模下**所有 exitCriteria 均 done**。

### 中型任务（精简门禁，6 项 + 1 条件项）

| id | 标准 | 依赖 dependsOn |
|----|------|----------------|
| DESIGN | 设计已定 | - |
| TESTCASES | 验收用例已编写（轻量：不要求完整测试设计文档，至少列出验收点） | DESIGN |
| IMPLEMENTED | 代码已实现 | DESIGN, TESTCASES |
| CODE_REVIEW | Code Review 通过 | IMPLEMENTED |
| SECURITY_REVIEW | 安全审查通过（条件项） | IMPLEMENTED |
| TEST_PASS | 测试通过 | CODE_REVIEW, SECURITY_REVIEW |
| KNOWLEDGE | 知识库已回顾 | TEST_PASS |

> **SECURITY_REVIEW 为条件项**：仅当本次变更涉及权限校验、分享访问控制、文件操作、配额计算等云盘安全敏感逻辑时启用。编排器在初始化 State 时根据变更范围判断是否激活；不涉及安全敏感逻辑的纯展示/排序类中型任务可跳过此标准（在 State 中标注 `skipReason`）。

### 小型任务（直接执行，3 项）

| id | 标准 | 依赖 dependsOn |
|----|------|----------------|
| IMPLEMENTED | 改动已实现 | - |
| VERIFIED | 编译/测试通过 | IMPLEMENTED |
| KNOWLEDGE | 轻量知识库检查 | VERIFIED |

## 门禁依赖规则（不可降级）

Evaluate 段必须强制检查 dependsOn：

- 依赖未满足的标准不得标记 done（例：CODE_REVIEW 依赖 IMPLEMENTED，代码没实现完不能算 Review 通过）
- REQ_ANALYSIS 要求 PRD 与 UI/UX 设计文档（uiSpec）**均产出且落盘到 `.ai/docs/<task-id>/`**（ref 指向真实文件）并经多方评审定版，缺一不可（PM 与 UI 协作，前端实现以此为唯一依据）
- TECH_DESIGN 依赖 IMPACT_ANALYSIS 与 EXP_DESIGN（体验评审必须先于技术设计，V3 原顺序保留）
- 大型任务 TECH_DESIGN 分两步：先产出架构设计评审（`architecture-review.md`，Architect 主笔，输出标准 `docs/newList/ai-architecture-review-standard.md`），评审通过后再产出程序设计文档（`design.md`）；架构评审为程序设计前置条件
- IMPLEMENTED 依赖 TECH_DESIGN 与 TESTCASES（大型任务未编写测试用例不得进入开发）
- TEST_PASS 依赖 CODE_REVIEW 与 SECURITY_REVIEW（安全审查须先于测试，安全修复改代码后测试才有意义）
- QUALITY_GATE 是最终收敛点，未通过不得 `status: done`

这些规则取代旧版"未完成 X 进入 Y"的线性禁止项，但约束力等价。

## 代码变更失效规则（rework cascade）

**核心规则**：当代码因 rework 发生变更（如 Code Review / Security Review / 体验验收 / 测试发现问题，导致工程师修改代码），`IMPLEMENTED` 被重开，其全部下游标准自动级联回退为 `pending`。

级联回退范围（传递依赖）：

```
IMPLEMENTED 重开
  -> CODE_REVIEW     -> pending  （旧 review 针对旧代码，失效）
  -> SECURITY_REVIEW -> pending  （旧安全审查针对旧代码，失效）
  -> EXP_ACCEPT      -> pending  （旧验收针对旧页面，失效）
     -> TEST_PASS    -> pending  （旧测试针对旧代码，失效）
        -> QUALITY_GATE -> pending
           -> KNOWLEDGE -> pending
```

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
