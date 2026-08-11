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

详见 `.ai/knowledge/loop-state-model.md`。编排器在 Evaluate 段逐项勾选，全部 done 才结束。

- **大型（12 项）**：需求分析 -> 影响分析 -> 体验评审 -> 技术设计 -> 测试用例 -> 实现 -> Code Review -> Security Review -> 体验验收 -> 测试 -> Quality Gate -> 知识库
- **中型（6 项 + 1 条件项）**：设计 -> 验收用例 -> 实现 -> Code Review -> 安全审查（条件项） -> 测试 -> 知识库
- **小型（3 项）**：实现 -> 验证 -> 知识库

## 门禁依赖（不可降级）

- 实现依赖设计与测试用例（大型：IMPLEMENTED 依赖 TECH_DESIGN + TESTCASES）
- 技术设计依赖影响分析与体验评审（TECH_DESIGN 依赖 IMPACT_ANALYSIS + EXP_DESIGN）
- 大型任务技术设计分两步：先架构设计评审（`architecture-review.md`，Architect 主笔），评审通过后再程序设计（`design.md`）；架构评审为程序设计前置
- Review 与安全审查通过才能测试（TEST_PASS 依赖 CODE_REVIEW + SECURITY_REVIEW）
- Quality Gate 是最终收敛点，未通过不得结束

## rework 即重新规划

Review/测试/验收发现问题 -> **不退格**，编排器重新 Plan：派对应 Agent 修复 -> 复检对应标准。

**代码变更触发级联回退**：若修复改了代码（IMPLEMENTED 重开），其全部下游标准（CODE_REVIEW/SECURITY_REVIEW/EXP_ACCEPT/TEST_PASS/QUALITY_GATE/KNOWLEDGE）自动回退 pending，需重新满足。这是 Loop 相对线性流水线的核心收益与关键正确性保证。

## 死循环防护

- 同一 blocker 连续 3 轮未解除 -> 升级人工
- 超轮次上限（large=40 / medium=15 / small=5）-> 升级人工

## 质量要求

任何阶段（任何轮）发现阻塞问题，编排器在 Evaluate 段记为 blocker 并在下一轮 Plan 中优先处理，而非"返回上一阶段"。

## 文档输出标准

所有落盘文档的内容结构遵循 `docs/newList/` 下对应输出标准，基于 `.ai/templates/` 模板填写。文档类型、归属、模板与输出标准的对应关系见 `.ai/knowledge/document-management.md`。

- 需求文档 / UI 设计文档 / 需求发现报告 / 架构设计评审 / 程序设计文档 / 测试用例 / Code Review 记录，各有独立输出标准与模板
- 大型任务 TECH_DESIGN 先产出架构评审再产出程序设计文档（见上「门禁依赖」）
- 文档落盘到 `.ai/docs/<task-id>/`，产出后告知用户路径，未产出对应文档不得标记该阶段 exitCriteria done

