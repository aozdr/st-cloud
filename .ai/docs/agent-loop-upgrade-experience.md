# 多 Agent 工作流升级为 Agent Loop——经验总结（面试用）

> 本文档复盘星云盘项目将"线性流水线式多 Agent 工作流"升级为"状态驱动的 Agent Loop"的完整经历，沉淀为面试可讲述的经验素材。
>
> 项目背景：星云盘是一个私有化部署的云盘系统（Java Spring Boot 后端 + React 前端），配套一套 AI 研发流程，由 15 个角色化 Agent（产品经理、架构师、前后端工程师、测试、Code Reviewer、安全审查等）协作完成需求交付。

---

## 一、升级前：线性流水线的痛点

### 原始架构

15 个 Agent 按固定阶段串联，Workflow Manager 在入口做一次任务分类（小/中/大），选定路径后退场：

```
请求 -> WM 分类 -> [需求 -> 设计 -> 编码 -> Review -> 安全 -> 测试 -> 验收 -> ...] -> 完成
```

文档在阶段间单向传递，走完阶段清单即结束。

### 核心痛点

| 痛点 | 表现 | 根因 |
|------|------|------|
| 编排器一次性退场 | WM 分类完就消失，后续全靠阶段间传递 | 无持续介入的编排中枢 |
| 回退只能"退一格" | Code Review 发现安全问题，但 Security Review 阶段已过，只能退回开发硬修 | 线性阶段模型，无重派能力 |
| 进度靠人记 | "现在到第几步了"全靠对话上下文 | 无显式共享状态 |
| 终止条件错误 | 走完阶段清单即结束，而非"目标达成" | 以位置驱动而非状态驱动 |
| 阶段间信息丢失 | 上一阶段的细节传到下一阶段时被压缩 | 单向传文档，无增量更新机制 |

**最典型的痛点场景**：Code Review 阶段发现一个权限漏洞，但 Security Review 阶段早已走过。按线性流程只能"退一格"回到开发，修完再往下走，无法干净地"重派 Security Reviewer 复检 -> 开发修复 -> 复检通过"。

---

## 二、目标架构：状态驱动的 Agent Loop

### 核心思想

从"位置驱动"（走到第 N 步）转为"状态驱动"（当前 State 推导下一步）：

```
[请求] -> 编排器解析为 Goal + 初始 State
           ↺ LOOP（每轮强制四段）
   1. Observe   读 State：已完成的产出物 / 阻塞 / 历史
   2. Plan      当前状态下一步最高价值的动作是？调哪个 Agent？
   3. Act       派发 Agent(带 State) -> Agent 返回 State Delta
   4. Evaluate  应用 Delta -> 检查门禁依赖 -> 死循环检测 -> 收敛判断
           ↹ 未达退出标准 -> 回到 1；全部满足 -> EXIT
```

### 与线性流水线的本质区别

| 维度 | 线性流水线 | Agent Loop |
|------|-----------|------------|
| 调度 | 入口分类一次，选固定路径后退场 | 每轮重新 Observe+Plan，持续介入到收敛 |
| 状态 | 阶段间单向传文档，进度靠人记 | 单一 Loop State，所有 Agent 读写增量 |
| 回退 | 退回上一阶段 | rework = 重新规划派发，不退格 |
| 收敛 | 走完阶段清单即结束 | 所有 exitCriteria 满足才结束 |
| Review 发现问题 | 退一格硬修 | 直接重派对应 Agent -> 复检 |

---

## 三、关键技术设计

### 1. Loop State——单一事实源

一份持续演进的 State 描述任务全量进度，所有 Agent 读它、只追加增量（Delta）：

```yaml
goal:        { objective, scope, completionCriteria }
scale:       large | medium | small
exitCriteria:        # 质量门禁清单，带依赖关系（DAG）
  - id, desc, status(pending|done), dependsOn[]
artifacts:           # 产出物，Agent 写入
  prd, uiSpec, design, testcases, code, review, security, testReport, knowledge
blockers:            # 活跃阻塞（含重试次数，用于死循环检测）
history:             # 滚动审计链（近 N 轮完整 + 更早摘要）
iteration, status
```

**设计要点**：State 是唯一进度来源，取代"当前在第几阶段"的记忆方式。Agent 间上下文通过 artifacts 的 ref 路径传递，而非重复传递全文。

### 2. 退出标准 + 依赖图（DAG）

按任务规模加载不同退出标准集：

- **大型（12 项）**：需求分析 -> 影响分析 -> 体验评审 -> 技术设计 -> 测试用例 -> 实现 -> Code Review -> Security Review -> 体验验收 -> 测试 -> Quality Gate -> 知识库
- **中型（5 + 1 条件项）**：安全审查为条件项，仅涉及权限/文件操作时启用
- **小型（3 项）**：实现 -> 验证 -> 知识库

每项标准带 `dependsOn`，形成有向无环图。**门禁不可降级**：

- TECH_DESIGN 依赖 IMPACT_ANALYSIS + EXP_DESIGN（体验评审必须先于技术设计）
- IMPLEMENTED 依赖 TECH_DESIGN + TESTCASES（大型任务未编写测试用例不得开发）
- TEST_PASS 依赖 CODE_REVIEW + SECURITY_REVIEW（安全审查须先于测试）

### 3. Rework Cascade（级联回退）——核心正确性保证

这是 Loop 相对线性流水线最关键的正确性机制：

> 当代码因 rework 发生变更（Review/测试/验收发现问题，导致工程师改代码），`IMPLEMENTED` 被重开，其**全部下游标准自动级联回退为 pending**。

```
IMPLEMENTED 重开
  -> CODE_REVIEW     -> pending  （旧 review 针对旧代码，失效）
  -> SECURITY_REVIEW -> pending  （旧安全审查针对旧代码，失效）
  -> EXP_ACCEPT      -> pending
     -> TEST_PASS    -> pending
        -> QUALITY_GATE -> pending
           -> KNOWLEDGE -> pending
```

**为什么必须级联**：rework 改了代码，旧的质量门结论（针对旧代码）就失效了。必须重走所有受影响的质量门，而非只复检发现问题的那一个。否则会出现"针对旧代码的 Review 通过了，但新代码没人查"的漏洞。

### 4. 死循环防护

- 同一 blocker 连续 3 轮未解除 -> 升级人工，暂停 Loop
- 超轮次上限（large=40 / medium=15 / small=5）-> 升级人工

### 5. History 滚动策略

- 保留最近 8 轮完整记录
- 更早轮次压缩为一行摘要（如 `iter 1-5: 需求->设计->测试用例->编码`）
- 避免上下文膨胀，同时保留审计可追溯性

### 6. 并行派发

无依赖的标准可并行派发 Agent。例如：
- 需求阶段：PM + UI Designer 并行协作产出 PRD + uiSpec
- IMPACT_ANALYSIS + EXP_DESIGN（均仅依赖 REQ_ANALYSIS）并行
- 编码阶段：前端 + 后端并行
- 审查阶段：Code Review + Security Review 并行

---

## 四、工程实践：三层验证

由于这是**配置驱动型**改造（改 AGENTS.md / .ai 指令文件，不是写运行时代码），"可用"= AI 扮演编排器时能正确遵循配置。因此建立了三层验证体系：

### 第一层：静态校验（脚本自动化）

- 依赖图无环（topo sort）+ 无孤立叶节点（应仅 KNOWLEDGE 为叶）
- 12 项 exitCriteria 每个有 Agent 归属
- 交叉引用路径存在（文件被引用但不存在 = 配置错误）
- 无残留旧版线性表述

### 第二层：白盒 Dry-Run（人工模拟）

用真实历史任务（收藏功能增强，有完整的需求/设计/测试文档）编排 11 轮 Loop 演练，逐轮模拟编排器决策链，**注入 rework 场景**验证级联回退：

- iter6 Security Review 发现 `toggleFavorite` 缺权限校验 -> 新增 blocker
- iter7 后端修复 -> 代码变更 -> IMPLEMENTED 重开 -> 6 项下游级联回退 pending
- iter8 并行复检 Code Review + Security Review -> 通过

### 第三层：真实任务实测 Checklist

C1-C24 共 24 项，覆盖：初始化、四段式、门禁依赖、并行派发、rework cascade、死循环防护、收敛退出、Agent 输出格式。

---

## 五、踩坑与复盘（面试加分项）

升级过程中发现并修复的 bug，体现了对一致性的严格把控：

### P0：体验评审变成孤立叶节点

**问题**：TECH_DESIGN 的 dependsOn 只写了 IMPACT_ANALYSIS，漏了 EXP_DESIGN。导致体验评审成了"没人依赖的孤岛"，可以被跳过。

**修复**：TECH_DESIGN dependsOn 改为 [IMPACT_ANALYSIS, EXP_DESIGN]，体验评审重新成为技术设计的前置门禁。联动更新 5 个文件的依赖表述。

**教训**：依赖图不能只看"有依赖"，要检查每个节点是否被下游引用（叶子节点审计）。

### P0：代码变更后下游未失效

**问题**：定义了 rework cascade 规则，但只在 state-model 里写了，没联动到各 Agent 文件。Agent 行为与规则脱节。

**修复**：cascade 规则联动到 8 个文件（AGENTS.md、workflow-manager、feature-development、5 个 Agent 的 rework 行为说明）。

**教训**：规则定义在单一文件，行为说明散落在多个文件，两者必须同步更新。

### P1：测试用例 dry-run 逻辑矛盾

**问题**：演练示例写"CODE_REVIEW done 后又发现问题"——但 done 了就不该再发现问题，自相矛盾。

**修复**：重写为"Review 与 Security 并行 -> 发现问题标 blocker -> 后端修复触发 cascade -> CODE_REVIEW 回退 pending -> 复检"。

**教训**：示例本身要经得起逻辑推敲，不能为了演示机制而制造矛盾。

### P1：TEST_PASS 不依赖 SECURITY_REVIEW

**问题**：TEST_PASS 只依赖 CODE_REVIEW，安全审查可以在测试后才做，但安全修复改代码后测试才有意义。

**修复**：TEST_PASS dependsOn 改为 [CODE_REVIEW, SECURITY_REVIEW]。

### P2：角色冗余与条件项缺失

- Project Manager 声称"管理开发流程状态"，与编排器职责重叠 -> 重新定位为跨任务协调
- 中型任务缺 SECURITY_REVIEW -> 加为条件项

---

## 六、后续演进：两个实战驱动的改进

### 改进一：UI 上游化协作（解决"前端乱写"）

**问题**：UI Reviewer 是纯下游评审者，等设计/开发完了才介入。PM 在需求阶段单独工作，前端拿到需求后自行发挥 UI 设计，导致视觉不一致。

**改造**：
- UI 从评审者升级为**需求阶段就与 PM 同步工作的设计师**
- PM 产出 PRD，UI 产出 uiSpec（UI/UX 设计文档），两份文档同步产出、互相校验
- 需求评审从三方（PM+dev+test）扩展为多方会议（PM+UI+前端+后端+测试）
- 定版后 uiSpec 成为前端实现的**唯一设计依据**，明确写入"不得自行发挥设计"
- Loop State 新增 `artifacts.uiSpec`，REQ_ANALYSIS 完成条件改为"prd 与 uiSpec 缺一不可"

**经验**：评审者要上游化。下游评审的问题是"发现晚了改不动"，上游协作的问题是"成本高但一次做对"。对于设计这类"一旦走偏影响大"的环节，上游协作 ROI 更高。

### 改进二：需求发现的实地调研（解决"我以为"）

**问题**：Requirement Discovery Agent 对竞品 UI/UX 的研究能力过低——只看官方文档就开始猜竞品长什么样，猜完就给需求。

**改造**：
- 核心原则改为"**实地观察，禁止臆测**"，明确禁止"我猜它应该是这样的"等表述
- 配置 `browser:control-in-app-browser` 技能，要求**实际访问竞品、亲自操作、截图记录**
- 案头调研只能回答"竞品有什么功能"，实地观察才能回答"竞品这个功能实际长什么样"
- 调研模板新增"竞品 UI/UX 实地观察记录"章节，要求附截图与操作步骤

**经验**：给 Agent 配能力比写约束更有效。光写"不要猜"没用，得给它配 browser skill 让它"能去看"。约束 + 能力 + 模板三管齐下。

---

## 七、核心权衡与反思

### 配置驱动 vs 运行时

这是本次最大的架构权衡：Loop 靠 AGENTS.md 文字约束实现，没有运行时强制层。

**优势**：零开发成本，改指令即生效，与 AI 原生协作模式契合。

**风险**：依赖 LLM 指令遵循度，长任务可能丢失早期 State，复杂任务可能走样。

**缓解措施**：
- 四段式写死模板（Observe/Plan/Act/Evaluate 每段强制输出），降低走样概率
- 依赖检查、cascade 规则显式写入，而非隐含
- history 滚动摘要控制上下文膨胀
- 超复杂任务建议拆子任务降低单 Loop 负担

### 状态膨胀 vs 可追溯

history 无限增长会吃上下文。采用"近 8 轮完整 + 更早摘要"的滚动策略，平衡可追溯性与上下文成本。

### 并行效率 vs 依赖正确性

并行派发提效，但必须严格检查 dependsOn。一旦依赖关系写错（如 P0 的 EXP_DESIGN 孤岛），并行反而加速出错。所以静态校验（topo sort + 叶节点审计）是必须的，不能靠人眼。

---

## 八、面试问答准备

### Q：为什么要从线性流水线升级到 Loop？

**A**：线性流水线的核心问题是"回退只能退一格"。比如 Code Review 发现安全问题，但 Security Review 阶段早过了，只能退回开发硬修，没法干净地重派 Security Reviewer 复检。根本原因是位置驱动——走到第 N 步决定下一步，而不是看当前状态决定下一步。Loop 把它改成状态驱动，每轮重新规划，rework 是重新派发而非退格。

### Q：Loop State 为什么要做成单一事实源？

**A**：线性模式下进度靠人记、文档阶段间单向传递，信息会丢失和压缩。单一 State 让所有 Agent 读写同一份进度，谁都能读全量、只追加增量。这解决了"上一阶段细节传到下一阶段时被压缩"的问题。State 的 artifacts 用 ref 指向文档路径，不重复存全文，控制体积。

### Q：rework cascade 为什么必须级联回退所有下游？

**A**：因为旧的质量门结论是针对旧代码的。代码改了，旧 Review 就失效了。如果只复检发现问题的那一个标准，会出现"针对旧代码的 Review 通过了，但新代码没人查"的漏洞。所以 IMPLEMENTED 重开时，CODE_REVIEW、SECURITY_REVIEW、EXP_ACCEPT、TEST_PASS 等全部下游必须回退 pending，重新验证。这是 Loop 的关键正确性保证。

### Q：怎么验证一个"没有运行时代码"的 Loop 是可用的？

**A**：三层验证。第一层静态校验——脚本检查依赖图无环（topo sort）、无孤立叶节点、交叉引用路径存在。第二层白盒 dry-run——用真实历史任务跑 11 轮模拟，注入 rework 场景验证 cascade。第三层真实任务实测——24 项 checklist 逐项打勾。因为配置驱动型没有运行时，只能靠"模拟编排器决策是否符合预期"来验证。

### Q：升级过程中踩过什么坑？

**A**：最严重的是依赖图写错——TECH_DESIGN 只依赖 IMPACT_ANALYSIS 漏了 EXP_DESIGN，导致体验评审成了可以被跳过的孤岛。发现方式是做叶子节点审计（只有 KNOWLEDGE 应该是叶子）。另一个是 cascade 规则只在 state-model 里定义了，没联动到各 Agent 文件，行为和规则脱节。教训是：规则定义和行为说明散落多文件时，必须同步更新，最好有脚本交叉校验。

### Q：UI 上游化协作解决了什么问题？

**A**：原来 UI 是纯下游评审者，等开发完了才看，前端自行发挥设计导致视觉不一致。改成需求阶段 UI 就和 PM 同步工作，PM 出 PRD、UI 出 uiSpec，经多方评审定版后 uiSpec 成为前端唯一设计依据。核心思路是：评审者上游化，把"发现晚了改不动"变成"一次做对"。对于设计这类走偏影响大的环节，上游协作 ROI 高于下游评审。

### Q：需求发现 Agent 为什么之前调研质量差？

**A**：因为它只做案头调研（看文档），然后靠"我以为"猜竞品长什么样。案头调研只能告诉你"竞品有什么功能"，但 UI/UX 研究需要知道"竞品这个功能实际长什么样、用起来什么体验"，这必须实地看。光写"不要猜"没用，得给它配 browser skill 让它真的能去访问竞品。约束 + 能力 + 模板三管齐下才有效。

### Q：这套 Loop 最大的局限性是什么？

**A**：配置驱动型，没有运行时强制层，依赖 LLM 指令遵循度。长任务可能丢失早期 State，超复杂任务可能走样。缓解措施是四段式写死模板、history 滚动摘要、依赖检查显式写入、超复杂任务拆子任务。本质上是用"足够明确的指令 + 验证机制"替代运行时强制，在 AI 原生协作场景下这是合理的权衡。

---

## 九、一句话总结

> 把多 Agent 协作从"线性流水线"升级为"状态驱动的 Agent Loop"，核心是：单一 Loop State 做事实源，编排器每轮 Observe-Plan-Act-Evaluate 重新规划，rework 触发级联回退而非退格，退出标准 DAG 驱动收敛。关键不是设计多巧妙，而是依赖关系、cascade 规则、验证机制的严格一致——配置驱动型系统，一致性就是正确性。