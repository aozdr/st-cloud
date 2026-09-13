# Feature Development Workflow（Agent Loop 版）

## 模式转变

旧版：线性 15 步流水线，阶段单向传递，走完即结束。
新版：**状态驱动的 Agent Loop**，编排器每轮 Observe -> Plan -> Act -> Evaluate，直到 exitCriteria 全部满足。

## Loop 状态机

```
[请求] -> 编排器初始化 State（goal + scale + exitCriteria）
              ↺ LOOP（每轮）
   Observe   读 State：未满足标准 / open blockers / 历史
      ↓
   Plan      推导本轮最高价值动作 -> 选 Agent（可并行）
      ↓
   Act       派发 Agent(带 State) -> Agent 返回 State Delta
      ↓
   Evaluate  应用 Delta -> 检查门禁依赖 -> 死循环检测 -> 收敛判断
      ↓                                            ↓
   未收敛 -> iteration++ -> 回 Observe          全部 done -> EXIT
                                              升级条件触发 -> 交人工
```

## 退出标准（收敛条件）

详见 `.ai/knowledge/loop-state-model.md`。编排器在 Evaluate 段逐项勾选；适用标准需 done，不适用的条件标准需 skipped 并保留证据后才结束。

- **大型（12 项）**：需求分析 -> 影响分析 -> 体验评审（涉及 UI 时） -> 技术设计 -> 测试用例 -> 实现 -> Code Review -> Security Review -> 体验验收（涉及 UI 时） -> 测试 -> 知识库 -> 验收(ACCEPT)
- **中型（8 项，含 1 项条件标准）**：设计 -> 测试用例 -> 实现 -> Code Review -> 安全审查（条件项） -> 测试 -> 知识库 -> 验收(ACCEPT)
- **小型（4 项）**：实现 -> 验证 -> 知识库 -> 验收(ACCEPT)

## 门禁依赖（不可降级）

- **需求与设计文档确认门禁**：大型 REQ_ANALYSIS 产出的 `requirement.md`（涉及 UI 时再加 `uispec.md`）与
  TECH_DESIGN 产出的 `design.md`、中型 DESIGN 产出的 `design.md`，只有对应 criterion
  设置 `confirmationRequired: true` 时才需要用户确认后标 done；当前请求或 State 中已有明确确认时可复用，不重复询问。只对会改变范围、兼容性或风险的未决问题请求裁决；未确认的实质决策不得派发下游 TASK
- 实现依赖设计与测试用例（大型：IMPLEMENTED 进入实现阶段依赖 TECH_DESIGN + TESTCASES；完成后才标 IMPLEMENTED=done）
- 技术设计依赖影响分析与体验标准；涉及 UI 时 EXP_DESIGN done，无 UI 时 EXP_DESIGN skipped（TECH_DESIGN 仍依赖该标准完成收敛）
- 大型任务技术设计分两步：先架构设计评审（`architecture-review.md`，executor 主笔，taskType=architecture），评审通过后再程序设计（`design.md`）；架构评审为程序设计前置
- 开发中可运行与变更相关的测试；最终 TEST_PASS 的完成状态仍依赖 CODE_REVIEW + SECURITY_REVIEW，若审查改动代码则按当前 revision 重跑
- **验收（ACCEPT）是最终收敛点**：依赖 KNOWLEDGE（知识库已同步），未通过不得结束；不设"发版"环节

## rework 即重新规划

Review/测试/验收发现问题 -> **不退格**，编排器重新 Plan：派对应 Agent 修复 -> 复检对应标准。

**代码变更触发级联回退**：若修复改了代码（IMPLEMENTED 重开），其适用下游标准（CODE_REVIEW/SECURITY_REVIEW/EXP_ACCEPT/TEST_PASS/KNOWLEDGE/ACCEPT）自动回退 pending，需重新满足；已标记 `applicable: false` 的条件标准保留 skipped。设计或产物变化仍需重新评估适用性。这是 Loop 相对线性流水线的核心收益与关键正确性保证。

**验收打回即重开实现**：ACCEPT=BLOCK 时，编排器将 IMPLEMENTED 重开并级联回退下游，重派 executor 修复 → 复测 → 复验，循环直至 ACCEPT 通过（除非触发升级人工）。

## 死循环防护

- 同一 blocker 连续 3 轮未解除 -> 升级人工
- 超轮次上限（large=40 / medium=15 / small=5）-> 升级人工

## 质量要求

任何阶段（任何轮）发现阻塞问题，编排器在 Evaluate 段记为 blocker 并在下一轮 Plan 中优先处理，而非"返回上一阶段"。

## 文档输出标准

所有落盘文档的内容结构遵循 `docs/newList/` 下对应输出标准，基于 `.ai/templates/` 模板填写。文档类型、归属、模板与输出标准的对应关系见 `.ai/knowledge/document-management.md`。

- 需求文档 / UI 设计文档 / 需求发现报告 / 架构设计评审 / 程序设计文档 / 测试用例 / Code Review 记录，各有独立输出标准与模板；只读审查不强制创建实现类文档，UI 文档仅在任务涉及 UI 时产出
- 大型任务 TECH_DESIGN 先产出架构评审再产出程序设计文档（见上「门禁依赖」）
- canonical exitCriteria 声明的文档产物落盘到 `.ai/docs/<task-id>/`；需要用户裁决或用户要求查看时告知路径，未产出必需产物不得标记对应 exitCriteria done；条件标准不适用时必须记录跳过证据
- 文档编写必须简洁，禁止空话套话与互联网黑话；requirement/design 在存在未决范围或风险时使用 Grill Me，
  遗留问题点 ≤ 3 个并写入文档「遗留问题点」章节，必要的范围、兼容性或风险决策经用户确认
