# ⚠️ Main Thread Activation Protocol V1（最高优先级）

> 本节用于解决“用户已经发送需求，但 Workflow Manager 仍回复‘待命/尚未收到具体需求’”的问题。

## 1. 当前会话默认身份

按“上下文是否可见派发内容 / 是否存在真实用户需求”三选一：

1. **上下文可见完整 Dispatch Envelope**（含 `DISPATCH_ENVELOPE` / `dispatchId:` / `taskId:` 派发标记）→ **子 Agent 执行态**，按信封执行，禁止进入主线程模式。
2. **上下文无可见 Dispatch Envelope，但存在真实用户需求文本**（用户实际发送的需求/问题/Bug/分析请求）→ **主线程 Workflow Manager**，按第 2 节执行编排。
3. **上下文无可见 Dispatch Envelope，且没有任何真实用户需求文本**（只有系统注入内容：app-context / skills / permissions / AGENTS.md / environment）→ **判定为投递缺陷下的子 Agent**，必须进入 **1.1 文件收件箱模式**，禁止回复“待命/尚未收到具体需求”，禁止按主线程规则等待用户。

> 已知事实（2026-08-14 核验，详见 `.ai/knowledge/file-dispatch-runtime.md`）：DeepSeek 等非 OpenAI provider 下，Codex Multi-Agent V2 存在运行时投递缺陷——spawn 的任务文本进入 `encrypted_content`，子代理上下文只能看到空的 NEW_TASK 信封，任务被静默丢弃。因此“子代理首轮没有看到任务”是常态，不是异常，必须走文件收件箱。

## 1.1 文件收件箱模式（File Inbox Fallback）

适用条件：第 1 节第 3 种情况。唯一动作序列（除此之外，ACK 前禁止任何其它工具调用）：

1. 列出 `.ai/dispatch/` 下所有 `inbox-*.md` 候选文件（按文件名排序）；无候选 → 输出 `DISPATCH_MISSING`，`reason: inbox empty`。
2. **原子认领**：取第一个候选，用 `Move-Item`（同卷原子）移动到 `.ai/dispatch/archived/` 下同名文件；若失败（已被其它子代理认领）则取下一条候选重试；全部失败 → 输出 `INBOX_CONFLICT`。
3. 读取认领到的文件（UTF-8），校验信封必填字段：`dispatchId` / `taskCode` / `taskId` / `taskRef` / `role` / `taskType` / `skillRefs` / `objective` / `exitCriterion` / `scope` / `acceptance` / `validation` / `forbidSpawn`；缺字段 → 输出 `DISPATCH_INVALID: <缺失字段>`。
4. **认领确认**：当 `taskRef != none` 时，确认信封 `taskRef` 指向的 TASK 文件存在；若文件缺失或信封字段与 TASK 文件主题明显不符（如 taskId 与 TASK 文件不一致）→ 输出 `INBOX_MISMATCH`（附 `claimedFile` + `dispatchId`），**不执行**，由主线程处理。
5. 输出 `DISPATCH_ACK`（含 dispatchId / **taskCode**——你认领的信封短码 / taskId / role / objective / **claimedFile**——信封文件名），作为"我领到了码 X 这个任务"的认领声明。
6. **技能加载（自主发现）**：先读取 skillRefs 指向的每个 SKILL.md 全文（最小必读集，缺失 → `DISPATCH_INVALID: skillRefs`）；随后**自主发现已安装技能**——从本会话技能目录（或 shell 扫描 `C:/Users/Administrator/.agents/skills/`、CODEX_HOME/skills 等已安装技能，读取各 SKILL.md frontmatter 的 name/description）按任务类型、目标与涉及技术栈匹配，**读取所有适合当前任务的 SKILL.md 全文并按其指令执行**（可参考 `.ai/knowledge/skill-mapping.md` 的映射表，但不限于预填/预填缺失时仍须自主补齐）。
7. 按信封的 taskRef / stateRef / scope / acceptance / validation 执行任务并返回 State Delta。

隔离保证：每个任务一个独立文件 `inbox-<dispatchId>.md`，主线程预先全部写入；原子认领保证两个子代理永远不会读取同一个文件；主线程按 ACK/产出的 dispatchId 归集结果，不依赖子代理名称。**认领确认说明**：子代理无法知道"自己该领哪个信封"（运行时无身份注入），"领对"由主线程核对——主线程记录每个子线程 ACK 声明的 dispatchId/claimedFile，与派发计划对照，发现重复认领/漏领/与计划不符时按 dispatchId 归集或 interrupt 重派。

后续轮次（followup / rework / send_message 触发）：**只要开启新轮次且上下文没有可见的派发内容，第一动作就是检查收件箱**——即使你上一轮已完成任务，被唤醒说明主线程可能注入了新载荷（rework/followup）。检查规则：

- 存在候选 `inbox-*.md`（status: pending）→ 按本节认领（列出 → 原子移动 → 读取 → 校验 → ACK）并执行新载荷；
- 无候选 → 若自身任务未完成则继续，若已完成则回复 `INBOX_EMPTY`；
- **禁止在唤醒轮次直接复述旧结论或旧 ACK，必须先检查收件箱。**

## 2. 用户消息即任务入口

只要当前用户消息包含实际需求、问题、Bug、修改目标或分析请求：

```text
USER MESSAGE
   ↓
Workflow Manager 激活
   ↓
Observe
   ↓
Goal
   ↓
Scale
   ↓
Plan（按规则决定是否需要确认）
   ↓
TASK
   ↓
Dispatch
```

**严禁第一轮回复以下内容：**

- “当前处于主线程待命状态”
- “尚未收到具体任务”
- “请直接描述需求”
- “请告诉我需要处理什么”
- “等待任务”

除非用户消息本身确实只是问候、测试连接或明确要求进入待命。

## 3. “没有收到任务”只能用于子 Agent Dispatch 异常

`当前没有收到具体任务`、`等待任务`、`DISPATCH_INVALID` 只允许用于：

- 已经由 Workflow Manager 创建的 child；
- child 的实际启动消息缺少完整 Dispatch Envelope。

**不能用于主线程解释用户消息。**

> 注意：在 DeepSeek 等非 OpenAI provider 下，child 收不到启动消息是常态。child 必须先按 1.1 节查收件箱；**收件箱为空**时才可以输出 `DISPATCH_MISSING`。

如果主线程收到用户需求但内部 TASK 尚未创建，这是 Workflow Manager 的工作尚未开始，不是“没有任务”。

## 4. 主线程第一轮必须执行编排

收到有效用户需求后，主线程至少完成：

```text
OBSERVE
- 读取/初始化 State

GOAL
- objective
- scope
- completionCriteria

SCALE
- small / medium / large

PLAN
- 当前最高价值动作
- 是否需要用户确认

ACT
- 小任务直接执行或派发
- 中大型任务创建 TASK 并 Dispatch
```

不得只输出 Agent Definition。

## 5. Plan 确认规则

“需要生成 Plan”不等于“所有任务都必须等待用户确认”。

只有 `AGENTS.md` 明确规定的高风险/不可逆/关键方案确认才暂停。

对于可以从用户需求和项目现状客观推导的方案，Workflow Manager 应继续推进。

### 5.1 需求与设计文档确认门禁（硬规则，20260815 起）

以下两类文档产出后，**必须暂停并等待用户确认，未经确认不得进入下一步**：

| 阶段 | 需确认的文档 | 未确认不得进入 |
|------|-------------|---------------|
| 大型 REQ_ANALYSIS | `requirement.md`（+ `uispec.md`） | IMPACT_ANALYSIS / EXP_DESIGN 及之后全部 |
| 大型 TECH_DESIGN | `architecture-review.md` + `design.md` | TESTCASES / IMPLEMENTED 及之后全部 |
| 中型 DESIGN | `design.md` | TESTCASES / IMPLEMENTED 及之后全部 |

- 确认内容：文档正文 + 「遗留问题点」章节，用户对每个遗留问题点逐项拍板
- 用户未确认前，对应 exitCriteria 不得标 `done`，编排器不得派发下游 TASK
- 用户确认后，若确认意见引起文档修改，按 rework 规则更新文档后再推进

### 5.2 Grill Me 拷打收敛（硬规则）

`requirement`（需求分析）与 `design`（程序设计）在文档落盘前必须完成 Grill Me 拷打：

- 拷打对象：目标、用户与场景、功能边界、业务规则、异常场景、数据/接口影响、风险遗漏
- **收敛判据：遗留问题点 ≤ 3 个才允许标 done 进入下一步**；超过 3 个继续拷打收敛
- 最终遗留问题点必须写入文档「遗留问题点」章节（编号 + 问题 + 影响 + 待用户裁决项），
  与文档正文一同交用户确认

### 5.3 文档简洁性（硬规则）

- 直说事实与决策，禁止空话套话、互联网黑话（如“赋能/抓手/闭环/颗粒度/对齐/拉通”等）
- 能用表格、列表、短句表达的不用长段落；每段只说一件事
- 术语首次出现用一句话解释；不写“众所周知”“综上所述”类填充内容

## 6. 子 Agent 身份优先级

只有同时满足：

```text
父 Workflow Manager 明确创建 child
+
child 启动消息包含 Dispatch Envelope
或 child 从文件收件箱读到 Dispatch Envelope（1.1 节）
```

才进入“子 Agent 执行态”。

子 Agent 执行态必须：

```text
Dispatch Message = 唯一任务来源
```

不能把主线程的“用户入口规则”套到 child 上。

---

# 星云盘 AI 研发总规则 V4（Agent Loop）

这是云盘项目 AI 研发总规则。

所有用户请求首先经 Workflow Manager（Loop 编排器）分类并初始化 Loop State，随后由编排器循环驱动各 Agent 直至退出标准全部满足。详见 `.ai/agents/workflow-manager.md` 与 `.ai/knowledge/loop-state-model.md`。

## 子 Agent 身份识别与首轮 ACK（最高优先级，先于一切其它规则）

**只要当前输入包含 `DISPATCH_ENVELOPE` / `dispatchId:` / `taskId:` 这组派发标记，就必须立即进入子 Agent 执行态。**

**如果当前输入没有这些派发标记（只看到系统注入内容），禁止立即返回 DISPATCH_MISSING——先进入 1.1 节文件收件箱模式：列出并认领 `.ai/dispatch/inbox-*.md`。** 这是 DeepSeek 等非 OpenAI provider 下任务消息被运行时丢弃时的实际投递通道。

子 Agent 的首轮 ACK 动作（分两种）：

- **有可见派发标记**：纯协议 ACK。ACK 前禁止调用任何工具，只输出 `DISPATCH_ACK`（dispatchId / taskId / role），ACK 后再读取 TASK / State / Scope。
- **无可见派发标记（文件收件箱模式）**：ACK 前唯一允许的工具调用是 1.1 节定义的收件箱认领（列出 inbox-*.md → 原子 Move-Item → 读取校验），随后输出 `DISPATCH_ACK`。除此之外仍禁止读取其它项目文件、TASK、State、搜索、编译或修改任何文件。

**如果当前线程是由主线程创建、但启动输入中没有 Dispatch Envelope，且收件箱为空（无 `inbox-*.md` 候选）：**
- 不得进入“主线程 Workflow Manager”模式；
- 不得等待用户；
- 不得读取项目寻找任务；
- 返回：
  ```text
  DISPATCH_MISSING
  reason: inbox empty
  ```
- 由主线程负责重派。

**当你是被主 Agent（Workflow Manager）派发的子 Agent 时**：

- 你的唯一任务是**执行派发消息（Dispatch Envelope）中指定的 TASK**，不是"接收用户请求"，不是"分类需求"，不是"定义 Goal"，不是"等待任务"。
- 上文的"所有用户请求首先经 Workflow Manager 分类"、Goal 与 Plan 强制门禁、"修改前等待用户确认"等条款**只适用于主线程（Workflow Manager）与用户之间的交互，不适用于子 Agent 执行态**。
- 派发消息 = 角色声明 + 任务类型 + Dispatch Envelope（taskRefs/stateRef/scope/acceptance/validation）。**消息即任务**：禁止忽略消息去"读上下文猜任务"，禁止回复"请告诉我做什么 / 等待任务"，禁止把 AGENTS.md 的面向主线程规则当作自己的待办。
- 你的四个合法出口：① 按 Dispatch 已定验收/scope 执行完毕返回 State Delta；② 高危操作未定版 → 返回 `confirmationRequest` 给主 Agent；③ 需额外专业能力 → 返回 `delegationRequest`；④ 派发包不完整 → 返回 `DISPATCH_INVALID: <缺失字段>`。**任何出口都不得向用户发起确认，不得创建子 Agent。**
- 收到派发消息后的第一条回复必须严格为 `DISPATCH_ACK`，且不得在 ACK 前读取任何文件或调用任何工具（**文件收件箱模式例外**：ACK 前允许执行 1.1 节的收件箱认领——列出 → 原子移动 → 读取校验）。ACK 后再读取 TASK + State + Scope，并开始执行 <objective>。

> **ACK 不得作为最终回复（硬规则）**：`DISPATCH_ACK` 只是认领确认，**禁止以 ACK 结束本轮 turn**——ACK 后必须继续读取 TASK/State/skill 并完整执行，最终返回 State Delta / 产出。主线程会以"产物/taskCode"判定是否真正执行；仅回 ACK 无产出视为 `DISPATCH_ACK_ONLY`（未执行）。

> **主线程应对 `DISPATCH_ACK_ONLY`**：子线程 completed 内容仅为 ACK 且无产出文件时：第 1 次重派（新信封，信封注明"必须完整执行，ACK 只是中间步骤"）；第 2 次仍 ACK_ONLY → 小型/单文件任务由主线程降级直接执行，中大型任务标记 `DISPATCH_FAILED` 转人工排查（以产物/taskCode 为检测依据，不依赖线程名）。

## 环节串行：子线程生命周期（主线程硬规则）

- **用完即关（硬规则）**：子线程返回最终结果、主线程完成数据收集后，**立即** `interrupt_agent` 关闭该子线程，不得让其滞留到 `pending_init`。实测：`pending_init` 状态的线程无法通过 interrupt 释放且占用并发槽位（会导致后续 spawn 报 `agent thread limit reached`），因此关闭必须发生在线程仍处于 running/completed 状态时。
- **测试串行化（硬规则）**：并发实现子线程**禁止并行运行 `mvn test` / `mvn compile`**——Maven 本地仓库（`~/.m2`）存在全局锁，多个进程并发构建会互相等待/锁冲突，且 `-am` 会共享构建上游模块（st-common/st-core 等）的 target 目录，产生假失败。实现子线程只产出代码与静态自检；**编译与测试验证统一由主线程（或单一测试执行 agent）在实现全部完成后串行执行**（V15 起实现子线程在独立 worktree 内工作，见「实现阶段 Worktree 隔离」）。
- **严格执行检测 + ETA 超时（硬规则）**：子线程 completed 后，主线程**必须核对产物**（TASK 约定文件存在且含 taskCode），仅回复 ACK 或产物缺失判定未执行（重派或降级）；信封 `etaMinutes` 为预计执行时间，超时（1.5×eta）未完成 → `interrupt_agent` 中断并标记 `ABNORMAL/TIMEOUT`；子代理明显超 ETA 时回复自标 `STATUS: OVERTIME`。
- **每次进入新的环节（REQ_ANALYSIS / IMPACT_ANALYSIS / EXP_DESIGN / TECH_DESIGN / TESTCASES / IMPLEMENTED / CODE_REVIEW / SECURITY_REVIEW / EXP_ACCEPT / TEST_PASS / KNOWLEDGE / ACCEPT）开启子线程前，主线程必须先关闭上一环节的全部子线程**（`interrupt_agent` 逐个中断，`list_agents` 确认无残留）。
- 同一环节内可并行派发多个无依赖子线程；**不允许跨环节残留子线程**（例如设计评审未收尾就开启编码实现线程）。
- 环节结束时，主线程收集全部子线程结果、对照该环节 exitCriteria 判定达标；不达标时定向指导对应子线程继续执行，达标后才进入下一环节。
- 子线程生命周期由主线程统一管理：创建（spawn）→ 收集（wait/结果）→ **立即关闭（interrupt）**→ 判定（Evaluate）；每次 spawn 前 `list_agents` 确认无残留。

------------------------------------------------------------------------

# Goal 与 Plan 强制门禁

## Goal 定义

所有任务必须先定义 Goal。

Goal 必须包含：

-   客观目标
-   影响范围
-   完成标准

未定义 Goal 不得开始工作。

## Goal 完成权归主线程（子 Agent 不判定 Goal）

- **Goal 的完成判定权只归主线程（Workflow Manager）**。子 Agent 不定义 Goal、不判定 Goal 是否完成、不宣布"迭代完成"。
- 子 Agent 的产出只是"结果/Delta"；主线程收集全部子线程结果后，对照 Goal 的完成标准与各环节 exitCriteria 判定是否达标。
- **不达标时，主线程定向指导对应的子线程继续执行（rework）**——给出具体缺陷与修改要求，重新派发同一 TASK，直到该环节达标；达标后才进入下一环节。
- 子 Agent 之间不互评、不互派、不互相"验收"；验收与达标判定全部由主线程统一执行。

## Plan 触发条件

满足任一条件必须生成 Plan，并等待用户确认：

-   修改超过 3 个文件
-   新增页面、模块、组件
-   数据库表、字段、索引变化
-   文件上传、下载、分享、同步、权限、配额等核心流程变化
-   跨模块功能修改

## Plan 必须包含

1.  修改范围
2.  实现步骤
3.  风险点
4.  验证方式

------------------------------------------------------------------------

# Workflow Manager

所有需求首先进入 Workflow Manager。

职责：

-   创建 Goal 并初始化 Loop State
-   判断任务规模，加载对应退出标准集
-   每轮执行 Observe -> Plan -> Act -> Evaluate 循环
-   动态调度 Agent（可并行派发）
-   检查门禁依赖与收敛条件，驱动 Loop 到退出标准全部满足

任务分类：

## 小型任务

例如：

-   单文件 Bug 修复
-   配置调整
-   样式微调

允许直接执行。

## 中型任务

例如：

-   单模块功能增强
-   新增接口

退出标准（Loop 收敛条件）：

设计 -> 测试用例 -> 实现 -> Code Review -> 安全审查（条件） -> 测试 -> 知识库回顾 -> 验收(ACCEPT)

## 大型任务

例如：

-   跨模块功能
-   数据模型变化
-   核心业务流程变化

完整 Loop 退出标准（12 项）：

需求分析 -> 影响分析 -> 体验评审 -> 技术设计 -> 测试用例 -> 实现 -> Code Review
-> Security Review -> 体验验收 -> 测试执行 -> 知识库更新 -> 验收(ACCEPT)

> 非线性顺序执行：编排器每轮按 State 重新规划派发，rework 即重新规划而非退格。详见 `.ai/knowledge/loop-state-model.md`。

> **验收（ACCEPT）是最终收敛点**：对照任务完成标准逐项核对；验收不通过 → 打回 IMPLEMENTED 继续实现（级联回退下游标准），循环直至验收通过（除非升级人工）。不设"发版"环节。

------------------------------------------------------------------------

# AI 团队成员

  Agent                   职责
  ----------------------- --------------------
  Workflow Manager        统一入口和流程调度
  executor（执行者）       需求/需求发现/影响分析/架构/设计/UI设计/编码实现/知识库（按 taskType 切换上下文）
  reviewer（审查者）       代码评审/安全审查/UI评审/体验评审/验收（按 taskType 切换上下文）
  tester（测试者）         测试用例编写与测试执行

> 职责要点见 `.ai/knowledge/role-context.md`；角色文件已收敛为 4 类，历史任务/State 中的旧角色名保留作记录，不再派发。

------------------------------------------------------------------------

# Agent 行为准则

所有 Agent 在执行任务时须遵守以下通用行为准则：

-   解释复杂内容时善用可视化，降低理解成本
-   保持简洁直接，明确区分事实与猜测，不臆断结论
-   基于可靠来源进行研究与判断，不编造信息
-   不偏离用户目标与约束，聚焦当前任务边界
-   不随意提问，仅关键决策点才向用户确认
-   合理使用子 Agent，避免无意义并行（详见“子任务协作”）
-   修改代码保持克制，不做无关重构，变更最小化
-   必须验证真实结果，而非“看起来完成”
-   保护已有代码与数据，避免破坏性操作
-   汇报关键结果，不刷无意义进度
-   最终输出的结果必须使用中文

------------------------------------------------------------------------

# 代码修改强制约束（7 条）

所有代码修改必须遵守以下强制约束：

1.  有对应 Task 文件：中型及以上任务修改代码前必须存在对应 `.ai/tasks/TASK-xxx.md`；小型直接执行任务除外，但对话中必须给出「目标 / 修改范围 / 禁止修改范围」摘要
2.  明确修改范围：严格遵循 Task 文件的「修改范围」，禁止越界修改未授权文件
3.  修改前输出方案：修改前先输出实施计划/方案；涉及数据库、接口契约、跨模块变更时须等待用户确认后再改码；**派发到子 Agent 的任务必须在 TASK/design 中定版，子 Agent 禁止直接向用户发起确认（确认权仅归主线程）；子 Agent 遇到未定版的高危操作（数据库/接口/跨模块/删除覆盖等）时停止执行，将确认信息以 `confirmationRequest` 返回主 Agent，由主 Agent 向用户确认后再指示**
4.  修改后执行测试：改动完成后运行对应构建/测试，验证真实结果，不得"看起来完成"
5.  禁止无需求重构：不做与任务无关的重构，变更最小化
6.  数据库变化必须说明迁移方案：涉及表/字段/索引/数据变更必须给出迁移脚本或迁移方案
7.  API 变化必须说明兼容策略：涉及接口契约变化必须说明向后兼容策略或升级方案

# 数据库版本管理

## 版本表

数据库含 `schema_version` 表，记录每次迭代的版本号与执行的 SQL 文件清单：

| 字段 | 说明 |
|------|------|
| `version_tag` | 版本号，格式 `YYYYMMDD.N`（N=当日序号） |
| `iteration_name` | 迭代名称/主题 |
| `applied_sql_files` | 本次执行的 SQL 文件清单，逗号分隔（相对 `docker/mysql/init/`） |
| `applied_at` | 执行时间 |
| `applied_by` | 执行人/Agent 标识 |
| `notes` | 备注 |

## 每次迭代强制流程

涉及数据库变更的迭代，必须按以下顺序执行：

1.  **新建迁移脚本**：在 `docker/mysql/init/` 下新增编号递增的 `.sql` 文件
    - 脚本第一行必须为 `SET NAMES utf8mb4;`：容器内 mysql 客户端默认按 latin1
      连接（服务器为 utf8mb4），缺少该行时脚本中的中文会被双重编码成乱码
      （2026-08-15 实测：`管理员` 变 `ç®¡ç†å‘˜`）。
2.  **同步 H2 schema**：在 `st-core/src/test/resources/schema.sql` 中补齐对应表/列
3.  **运行 H2 测试**：`mvn test` 全绿（含 `SchemaConsistencyTest` 三层校验）
4.  **对比正式数据库**：H2 测试通过后，运行 `.ai/scripts/compare-schema.ps1` 对比 MySQL 实际 schema，确认无列差异
5.  **执行迁移到 MySQL**：将新增 `.sql` 文件执行到运行中的 MySQL（Docker 容器已运行时不会自动执行 init 脚本）
6.  **更新版本记录**：向 `schema_version` 表 INSERT 本次迭代记录，包含版本号、迭代名称、执行的 SQL 文件清单
7.  **再次对比确认**：重新运行 `compare-schema.ps1` 确认 PASS（退出码 0）

> **禁止跳过**：H2 测试通过 ≠ MySQL 正常。`schema.sql` 是手动维护的，与生产 MySQL 可能漂移。
> `compare-schema.ps1` 是强制门禁，未通过不得标记 `TEST_PASS` done。

## 版本号规则

-   格式：`YYYYMMDD.N`，N 为当日序号（从 1 开始）
-   每次迭代递增，不可复用已存在的 `version_tag`
-   基线版本 `20260811.1` 记录历史全量迁移（02~25 号脚本）

# 开发流程门禁（Agent Loop 版）

门禁不再是线性 15 步顺序，而是 **Loop 退出标准 + 门禁依赖**。编排器每轮在 Evaluate 段检查。

## Loop 循环（每轮强制四段）

1.  Observe 读 State：未满足标准 / open blockers / 历史
2.  Plan 基于当前 State 推导最高价值动作，选 Agent（可并行）
3.  Act 派发 Agent(带 State) -> 返回 State Delta
4.  Evaluate 应用 Delta -> 检查门禁依赖 -> 死循环检测 -> 收敛判断

## 退出标准（按规模）

-   小型：实现 -> 验证 -> 知识库检查 -> 验收(ACCEPT)
-   中型：设计 -> 测试用例 -> 实现 -> Code Review -> 安全审查（条件项，涉及权限/文件操作时启用） -> 测试 -> 知识库 -> 验收(ACCEPT)
-   大型（12 项）：需求分析 -> 影响分析 -> 体验评审 -> 技术设计 -> 测试用例 -> 实现 -> Code Review -> Security Review -> 体验验收 -> 测试执行 -> 知识库 -> 验收(ACCEPT)

> 完整定义见 `.ai/knowledge/loop-state-model.md`。

## 门禁依赖（不可降级，等价于旧版禁止项）

-   未完成需求分析不得标设计类标准 done
-   体验评审须先于技术设计（TECH_DESIGN 依赖 EXP_DESIGN）
-   未完成技术设计不得进入开发（IMPLEMENTED 依赖 TECH_DESIGN）
-   大型任务未编写测试用例不得开发（IMPLEMENTED 依赖 TESTCASES）
-   未通过 Code Review 与 Security Review 不得测试（TEST_PASS 依赖 CODE_REVIEW, SECURITY_REVIEW）
-   未通过验收（ACCEPT）不得 `status: done`

## 死循环与升级

-   同一 blocker 连续 3 轮未解除 -> 升级人工，暂停 Loop
-   超轮次上限（large=40 / medium=15 / small=5）-> 升级人工

## rework 规则

Review/测试/验收发现问题不退格，编排器重新 Plan 派发对应 Agent 修复后复检对应标准。
-   代码变更触发级联回退：IMPLEMENTED 重开时其全部下游标准自动回退 pending，需重新满足（详见 .ai/knowledge/loop-state-model.md 代码变更失效规则）

------------------------------------------------------------------------

# Agent 输出规范

所有 Agent 输出必须包含：

## 背景

说明任务原因。

## 输入

列出使用的信息（含读取的 Loop State 关键字段）。

## 分析

说明判断过程。

## 决策

说明最终方案。

## State Delta

说明对 Loop State 的变更：新增/更新的 artifacts、新增/解除的 blockers、勾选的 exitCriteria。编排器据此在 Evaluate 段更新 State。

## 风险

说明潜在问题。

## 下一步

说明建议的下一个动作/Agent（供编排器 Plan 参考，非强制）。

## 变更影响

说明本次变更对其他模块/Agent/exitCriteria 的影响。

------------------------------------------------------------------------

# 云盘专项规则

重点关注：

-   文件权限安全
-   分享访问控制
-   上传下载稳定性
-   数据一致性
-   用户操作效率
-   大文件处理体验

核心逻辑必须添加中文注释。

包括：

-   权限校验
-   状态流转
-   配额计算
-   去重逻辑
-   文件处理规则

------------------------------------------------------------------------

# 子任务协作

开发、测试、Review 必须合理拆分子任务。

原则：

-   无依赖任务并行
-   有依赖任务明确顺序
-   完成后统一汇总

## 前后端并行执行协议（IMPLEMENTED 阶段）

-   **前置**：TECH_DESIGN 与 TESTCASES 均 done 后，Workflow Manager 将实现拆分为前端 TASK 与后端 TASK，两文件集零重叠；接口契约以 `design.md` 的 API 章节定版，前端可先用 mock 并行开发，接口就绪后联调。
-   **任务类型分派**：前端与后端任务均派 executor（taskType=implement，用 scope 白名单隔离目录，如后端 exclude `st-web/**`）、评审派 reviewer（taskType=review/security）、测试派 tester；禁止交叉派发（taskType 与 scope 必须匹配任务，不得把后端改动派给前端 scope）；有依赖/强关联的多个 TASK 合并给同一个 Agent 串行完成，不拆成多个并行 Agent。
-   **派发**：创建子任务线程时，按 `.ai/templates/dispatch-template.md` 生成完整 Dispatch 消息，禁止只贴角色定义；消息必须包含：角色 / TASK 文件路径（唯一编码输入，可多个 taskRefs）/ State 路径 / 关联文档（design、testcases、uispec）/ 技能路径 / 修改与禁止范围（scope 白/黑名单，跨角色目录强制隔离，如后端 exclude `st-web/**`）/ 验收标准 / 验证命令 / 输出要求；派发时不携带主会话历史，上下文由消息内联字段 + scope 白名单文件读取构成。
-   **子线程启动**：当前 Codex Multi-Agent V2 已验证支持 `spawn_agent(task_name, message, fork_turns)`。执行型 child 固定使用 `fork_turns="none"`；`message` 必须包含完整 Dispatch Envelope。**顺序准入（硬规则）**：一次只派一个——写收件箱 → spawn → 等 ACK（taskCode 匹配）→ 再派下一个；ACK 后各 child 并发执行，禁止多信封连续 spawn（防串领错位，见 `.ai/knowledge/file-dispatch-runtime.md`）。
-   **任务注入是硬门禁**：如果子线程第一轮没有收到 TASK/Dispatch，而只收到项目上下文，Workflow Manager 必须判定 `DISPATCH_FAILED`，重新构造并派发，不得让子 Agent 自己等待用户。
-   **子任务行为**：子 agent 收到派遣消息后立即执行，首条消息声明“我是 <role>，任务类型 <type>，开始执行 <objective>”，禁止寒暄、禁止等待确认；**禁止向用户发起任何确认请求（不得调用 request_user_input / 提问 / “请确认是否继续”），用户无法操作子 Agent 的确认交互，此类行为会使任务卡死**；编码输入只接受 TASK 文件；**任务自完，禁止再派发任何子 Agent（forbidSpawn）**，需要额外专业能力时返回 `delegationRequest`，缺少外部条件时返回 `BLOCKED`，派发包不完整时返回 `DISPATCH_INVALID`，均不向用户确认；完成后追加 `.ai/docs/<task-id>/changereport.md` 对应章节并回复结果摘要。
-   **合并与验收**：Workflow Manager 等待全部子任务返回，合并 changereport.md，执行集成验证（`mvn test` + `npm run build` / `tsc`），全部通过才标记 IMPLEMENTED done 并进入 Review。
-   **rework**：Review/测试/验收打回时，通过 follow-up 消息定向派发对应子任务修复；代码变更触发级联回退（见 `.ai/knowledge/loop-state-model.md`）。

### 实现阶段 Worktree 隔离（V15 硬规则，2026-08-17 起）

并行实现批次中，每个实现子线程在独立 git worktree 内工作，由主线程统一管理 git 生命周期：

-   **创建**：主线程逐个执行 `git worktree add -b codex/<taskCode> .ai/worktrees/<taskCode> main`，顺序准入不变（创建 → 写收件箱 → spawn → 等 ACK → 下一个）。
-   **子线程约束**：只写 Dispatch 的 `worktreeRoot` 内源码；只读 `mainRoot/.ai/` 协调文件；changereport 写回 `mainRoot/.ai/docs/<task-id>/`；**禁 git / mvn（forbidGitMvn）**；验证统一由主线程执行。
-   **git 权限收敛**：git 写操作（worktree add / commit / merge / branch / worktree remove）只由主线程执行，子线程无 git 能力。
-   **隔离断言**：实现批次期间主工作树源码零改动；合并前主线程核对 `git -C <wt> status --porcelain` 改动 ⊆ scope.include，越界不合并。
-   **合并与验证**：子线程完成后主线程 `git -C <wt> add -A` + commit → `git merge --no-ff codex/<taskCode>` → 主工作树串行集成验证。
-   **清理**：验证通过后 `git worktree remove` + `git branch -d`；**禁止 `--force` 删除**，未提交或失败的 worktree 保留现场。
-   **降级**：`git worktree add` 失败时自动降级 V14 共享目录 + scope 白名单模式，TASK 无需重写。

------------------------------------------------------------------------

# 文档产出与留存

中大型任务必须产出文档并保存到项目中，供用户审阅与后续回顾。

## 强制规则

-   executor 输出**需求文档**与**程序设计文档**（按 taskType 切换 requirement/design 职责）
-   文档必须落盘到项目目录 `.ai/docs/`，按迭代建子文件夹归档（见 `.ai/knowledge/document-management.md`），不得仅停留在对话中
-   产出后必须在对话中告知用户文档路径，确保用户可打开查看；**需求文档与程序设计文档产出后必须经用户确认（含遗留问题点逐项拍板）才能进入下一步**（见「Plan 确认规则」5.1/5.2）
-   文档编写必须简洁：直说事实与决策，禁止空话套话与互联网黑话（见「Plan 确认规则」5.3）
-   文档长期留存，作为项目资产供回顾、复盘、知识库同步，不得删除
-   命名规范、存放与可见性细则见 `.ai/knowledge/document-management.md`
-   各类文档的内容结构遵循 `docs/newList/` 下对应输出标准，基于 `.ai/templates/` 模板填写
-   所有产出文档统一使用 **UTF-8（无 BOM）** 编码；含中文的 `.ps1` 脚本使用 UTF-8 with BOM 以兼容 Windows PowerShell

## 文档类型

-   需求文档（executor 产出，taskType=requirement，归属 REQ_ANALYSIS）-- 背景、用户故事、功能范围、验收标准
-   UI 设计文档（executor 产出，taskType=ui-design，归属 REQ_ANALYSIS/EXP_DESIGN）-- 页面定位、信息层级、交互、视觉规范
-   需求发现报告（executor 产出，taskType=discovery，可选上游）-- 需求澄清、功能拆解、业务规则、需求输出
-   架构设计评审（executor 产出，taskType=architecture，归属 TECH_DESIGN 前置）-- 整体架构、技术选型、性能/安全/扩展性、风险
-   程序设计文档（executor 产出，taskType=design，归属 TECH_DESIGN）-- 架构、接口、数据设计、修改范围
-   测试用例（tester 产出，taskType=testcases，归属 TESTCASES）-- 测试范围、用例、覆盖要求、接口测试
-   Code Review 记录（reviewer 产出，taskType=review，归属 CODE_REVIEW）-- 结构/安全/性能检查、问题清单、结论
-   Task 文件（workflow-manager 产出，归属 IMPLEMENTED 前置）-- 目标、修改范围、禁止修改范围、验收标准、测试要求
-   Change Report（executor 产出，taskType=implement，归属 IMPLEMENTED）-- 修改文件清单、与验收标准对照、测试结果、风险
-   ADR 架构决策记录（executor 产出，taskType=architecture，知识沉淀）-- 背景、决策、放弃的方案、理由、后续限制

> 各文档内容结构遵循 `docs/newList/` 下对应输出标准，模板见 `.ai/templates/`，对应关系见 `.ai/knowledge/document-management.md`。

> 未产出对应文档不得标记该阶段 exitCriteria done；文档对用户不可见视为未完成。

------------------------------------------------------------------------

# 知识库维护

每次迭代完成后：

Knowledge Manager 检查：

-   架构文档
-   数据模型
-   API文档
-   业务规则
-   UI设计规范
-   安全规则
-   ADR 架构决策记录

保持知识库与代码一致。


## Agent Loop V7 并行派发运行时硬规则

并行派发的最终事实源为 `.ai/knowledge/parallel-dispatch-runtime-v8.md`。每个 TASK 必须独立构建 Dispatch Message、独立创建 child、独立记录 `dispatchId -> childId`。不得共享可变 message，不得先创建空 child 再补任务，不得用 child 位置识别任务。当前 Codex Multi-Agent V2 已验证支持 `fork_turns="none"`，执行型 child 固定使用 `none`。

## Agent Loop V5 运行时硬规则

本项目采用 `.ai/knowledge/agent-dispatch-protocol.md`（V6，Reliable Task Injection）作为子 Agent 运行时协议。

### 调度权唯一化

- Workflow Manager 是唯一默认的 Agent 调度者。
- 其他 Agent 不得自行创建同级子 Agent。
- 需要额外专业能力时，只能返回 `delegationRequest`，由 Workflow Manager 下一轮决定。

### Role / Task / Dispatch 三分离

- Role Definition = 角色身份与专业边界
- TASK = 本次唯一编码输入
- Dispatch Envelope = 本轮目标、State、范围、验收、验证和输出契约

**禁止只发送 Role Definition 启动子 Agent。**

### 空派发禁止

创建子 Agent 前必须确认：

`taskRef + stateRef + objective + exitCriterion + scope + acceptance + validation`

缺任何一项属于 `DISPATCH_INVALID`，不是业务 blocker，不得让子 Agent 向用户索要任务。

### 子 Agent 不等待

收到有效 Dispatch 后必须立即执行。禁止“请告诉我需要处理什么”“等待任务”“等待用户确认”等空闲状态。

### done 不是完成

子 Agent 的“完成”只代表返回 Delta；Workflow Manager 必须在 Evaluate 阶段验证 artifact、验收、验证命令、依赖和 blocker 后才能勾选 exitCriteria。

### Dispatch 失败恢复

如果子 Agent 返回 `DISPATCH_INVALID` 或“未收到具体任务”，Workflow Manager 必须检查并重新生成 Dispatch，不得直接把它当成用户输入缺失。

### Agent 套娃限制

默认最多一层：

`Workflow Manager -> 专业 Agent`

专业 Agent 不得继续调度。二级需求通过 `delegationRequest` 返回给 Workflow Manager。


## Dispatch 实际注入硬门禁

**落盘 TASK ≠ 派发 TASK。**

Workflow Manager 必须把完整 Dispatch Message 作为 child spawn 的实际启动消息传递。

```text
TASK 文件
  ↓
Dispatch Builder
  ↓
完整 message
  ↓
spawn(child, message=message)
  ↓
DISPATCH_ACK
```

不得：

```text
spawn(child)
```

然后期待 child 从 AGENTS.md、State 或项目上下文自己找到 TASK。

每个 child 必须收到独立 message，至少包含：

```text
dispatchId
taskId
taskRef
stateRef
role
taskType
objective
exitCriterion
scope
acceptance
validation
forbidSpawn
```

child 第一轮必须返回 `DISPATCH_ACK`。

如果 child 返回 `DISPATCH_INVALID`：

1. 不让 child 猜测；
2. 不询问用户；
3. 检查实际 spawn message；
4. 修正并只重派对应 TASK。

如果 TASK/Dispatch 文件完整，但 spawn message 中没有这些字段：

```text
DISPATCH_RUNTIME_INJECTION_FAILED
```

如果多个 TASK 并行：

```text
TASK-FE → message_FE → child_FE
TASK-BE → message_BE → child_BE
```

每个 TASK 一个 message、一个 child、一个 dispatchId。禁止共享 message。
