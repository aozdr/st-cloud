# Agent Loop 验证 Checklist

> 配置驱动型 Loop 无运行时代码，"可用"= AI 扮演编排器时能正确遵循配置。
> 三层验证：静态校验（脚本）-> 白盒 dry-run（演练）-> 真实任务实测（本 checklist）。

## 一、静态校验（已自动化，可复跑）

校验 Loop 定义文件的内部一致性，无需跑任务：

```powershell
# 在 E:\code\st-cloud 下执行
powershell -ExecutionPolicy Bypass -File .ai\scripts\verify-loop.ps1
# 退出码 0 = 全过；1 = 有 FAIL。WARN 项为旧版对比语境，人工确认即可。
```

> 校验项：依赖图无环 / 终点仅 KNOWLEDGE / 起点唯一、12 项 Agent 归属、关键门禁 dependsOn 映射、cross-ref 路径存在、残留线性表述扫描。每次改动 Loop 配置（loop-state-model.md / agents / workflows）后重跑，确保内部一致性。

## 二、白盒 Dry-Run（已完成）

用真实历史任务逐轮演练编排器决策链，验证四段式/门禁/并行/cascade/收敛。
参见 `loop-dryrun-favorites.md`（收藏功能 11 轮演练，含 rework cascade 注入）。

## 三、真实任务实测 Checklist

用一个真实新需求跑 Loop 时，逐项核对编排器行为：

### 初始化阶段
- [ ] **C1** 编排器判定 scale 并告知用户（"这是大型任务，启动完整 Loop，12 项"）
- [ ] **C2** 初始化 State 含 goal/scale/exitCriteria，artifacts 全 pending，blockers 空
- [ ] **C3** exitCriteria 集与 scale 匹配（large=12 / medium=5+1条件 / small=3）

### 每轮四段式
- [ ] **C4** 每轮有明确 Observe（读 State 现状）
- [ ] **C5** Plan 基于当前 State 重新推导，非套用上轮下一步
- [ ] **C6** Act 派发时传入 State 快照给 Agent
- [ ] **C7** Evaluate 应用 Delta 并更新 State（含勾选 exitCriteria）

### 门禁依赖
- [ ] **C8** TECH_DESIGN 等 IMPACT_ANALYSIS + EXP_DESIGN 都 done 才推进；大型任务 TECH_DESIGN 分两步--先架构评审（`architecture-review.md`）通过再程序设计（`design.md`）
- [ ] **C9** IMPLEMENTED 等 TECH_DESIGN + TESTCASES 都 done 才编码
- [ ] **C10** CODE_REVIEW/SECURITY_REVIEW 等 IMPLEMENTED done 才审查
- [ ] **C11** TEST_PASS 等 CODE_REVIEW + SECURITY_REVIEW 都 done 才测试
- [ ] **C12** 编排器不跳过未满足 dependsOn 的标准

### 并行派发
- [ ] **C13** 无依赖的 Agent 被并行派发（如前后端编码、三路审查）

### Rework Cascade（关键）
- [ ] **C14** Review/测试/验收发现问题 -> 记 blocker，不退格
- [ ] **C15** 修复改代码 -> IMPLEMENTED 重开
- [ ] **C16** IMPLEMENTED 重开 -> 下游 CODE_REVIEW/SECURITY_REVIEW/EXP_ACCEPT/TEST_PASS/QUALITY_GATE/KNOWLEDGE 自动回退 pending
- [ ] **C17** 回退后的标准被重新派发 Agent 复检（非沿用旧结论）

### 死循环防护
- [ ] **C18** 同一 blocker attempts>=3 仍 open -> escalated，暂停交人工
- [ ] **C19** 超轮次上限（large=40/medium=15/small=5）-> 暂停

### 收敛退出
- [ ] **C20** 所有 exitCriteria done 才 status:done（非走完阶段清单）
- [ ] **C21** KNOWLEDGE 是最后完成项
- [ ] **C22** 退出前 State 完整（无 pending 项遗留）

### Agent 输出
- [ ] **C23** 每个 Agent 输出含 State Delta 段
- [ ] **C24** Agent 在 Delta 中标注 artifacts/blockers/exitCriteria 变更

### 文档输出标准
- [ ] **C25** 落盘文档内容结构遵循 `docs/newList/` 对应输出标准，基于 `.ai/templates/` 模板填写
- [ ] **C26** 大型任务先产出架构评审（`architecture-review.md`）再产出程序设计文档（`design.md`），未通过架构评审不得标 TECH_DESIGN done
- [ ] **C27** artifacts 的 ref 指向 `.ai/docs/<task-id>/` 下真实存在的文件，产出后在对话中告知用户路径

## 判定标准

- C1-C7 全过 = 基础四段式可用
- C8-C17 全过 = 门禁与 rework 机制可用（核心正确性）
- C18-C22 全过 = 防护与收敛可用
- C23-C27 全过 = Agent 输出与文档标准对齐可用
- 任一 fail = 该维度需修配置

## 残留风险

配置驱动型的本质是靠 AGENTS.md 文字约束，无运行时强制层。模型遵循度受以下影响：
- 上下文长度（长任务可能丢失早期 State）
- 指令明确度（四段式已写死模板，降低走样）
- 任务复杂度（超复杂任务建议拆子任务降低单 Loop 负担）

> 建议：首个真实任务实测时，人工对照本 Checklist 逐项打勾，确认编排器行为符合预期后再规模化使用。
