# Agent Loop 白盒演练：收藏功能增强

> 本文件是 Agent Loop 的**白盒 dry-run 记录**。用真实历史任务（收藏功能增强）逐轮模拟编排器（Workflow Manager）的 Observe -> Plan -> Act -> Evaluate 决策链，验证四段式循环、门禁依赖、并行派发、rework cascade、收敛退出等核心机制的行为正确性。
>
> 演练基于真实项目产物文档：`.ai/docs/favorites-enhancement-requirement.md`、`-design.md`、`-testcases.md`。迭代序列为合理推演（配置驱动型 Loop 无运行时，dry-run 即人工模拟编排器决策），非实际运行日志。
>
> 状态模型定义见 `.ai/knowledge/loop-state-model.md`；验证 checklist 见 `.ai/knowledge/loop-verification-checklist.md`。

---

## 演练背景

**任务**：收藏功能增强--新增收藏页面、侧边栏入口、收藏优先排序、后端分页接口、权限校验补强、遗留代码清理。

**规模判定**：跨模块（后端 st-core + 前端路由/页面/组件/lib）、新增页面与接口、涉及权限校验（云盘安全敏感逻辑）-> **大型任务**，启动完整 Loop，退出标准 12 项。

**注入的 rework 场景**：iter6 Security Review 发现 `toggleFavorite` 缺权限校验（用户可收藏无权访问的文件），iter7 后端修复触发 rework cascade，验证级联回退与复检机制。

---

## 退出标准依赖图（大型，12 项）

```
REQ_ANALYSIS
  +--> IMPACT_ANALYSIS
  +--> EXP_DESIGN
        +--> TECH_DESIGN  (also <- IMPACT_ANALYSIS)
              +--> TESTCASES
                    +--> IMPLEMENTED
                          +--> CODE_REVIEW
                          +--> SECURITY_REVIEW
                          +--> EXP_ACCEPT
                                +--> TEST_PASS  (also <- CODE_REVIEW, SECURITY_REVIEW)
                                      +--> QUALITY_GATE  (also <- SECURITY_REVIEW, EXP_ACCEPT)
                                            +--> KNOWLEDGE
```

> 完整 dependsOn 定义见 `loop-state-model.md`。

---

## 初始 State（iter=0）

```yaml
goal:
  objective: "完善收藏功能：收藏页/侧边栏入口/优先排序/分页接口/权限校验/清理遗留代码"
  scope: "后端 st-core + 前端路由/页面/组件/lib，无数据库变更"
  completionCriteria: ["8 项验收标准 AC1-AC8 全部满足"]

scale: large

exitCriteria:
  REQ_ANALYSIS: pending
  IMPACT_ANALYSIS: pending
  EXP_DESIGN: pending
  TECH_DESIGN: pending
  TESTCASES: pending
  IMPLEMENTED: pending
  CODE_REVIEW: pending
  SECURITY_REVIEW: pending
  EXP_ACCEPT: pending
  TEST_PASS: pending
  QUALITY_GATE: pending
  KNOWLEDGE: pending

artifacts: { prd: pending, design: pending, testcases: pending, code: pending,
             review: pending, security: pending, testReport: pending, knowledge: pending }
blockers: []
iteration: 0
status: running
```

> **C1/C2/C3 覆盖**：编排器判定 scale=large 并告知用户；State 含 goal/scale/exitCriteria，artifacts 全 pending，blockers 空；exitCriteria 12 项匹配 large。

---

## iter1 - REQ_ANALYSIS

**Observe**：全部 12 项 pending。REQ_ANALYSIS 的 dependsOn 为空，可直接启动。无 blockers。

**Plan**：并行派发 `product-manager` + `ui-designer` 协作执行需求分析。理由：需求分析是整条链的源头，无前置依赖；PM 与 UI 同步工作产出 PRD + UI/UX 设计文档。

**Act**：
- `product-manager`：Grill Me 拷打需求，收集需求缺口（无独立收藏页/无侧边栏入口/无优先排序/遗留 localStorage），产出需求文档，含 8 项验收标准。
- `ui-designer`：与 PM 同步工作，产出 UI/UX 设计文档（收藏页布局、交互流程、状态设计、组件选型、视觉规范）。
- 两份文档经需求评审多方会议（PM + UI + 前端 + 后端 + 测试）定版。

**Evaluate**：应用 Delta -> REQ_ANALYSIS=done（prd 与 uiSpec 均已产出并定版）；artifacts.prd=done（ref: `.ai/docs/favorites-enhancement-requirement.md`）；artifacts.uiSpec=done。依赖检查通过。iteration=2。

**State Delta**：
- exitCriteria: `REQ_ANALYSIS` -> done
- artifacts: `prd` -> done（ref: `.ai/docs/favorites-enhancement-requirement.md`），`uiSpec` -> done

**State 快照**：`REQ_ANALYSIS=done`，其余 11 项 pending，blockers=[]

> **C13 覆盖**：PM + UI 并行协作产出 PRD + uiSpec。

---

## iter2 - 并行 IMPACT_ANALYSIS + EXP_DESIGN

**Observe**：REQ_ANALYSIS=done。IMPACT_ANALYSIS dependsOn=[REQ_ANALYSIS] 已满足；EXP_DESIGN dependsOn=[REQ_ANALYSIS] 已满足。两者互不依赖，可并行。无 blockers。

**Plan**：并行派发 `impact-analyzer` + `experience-reviewer`/`ui-reviewer`。理由：两个标准的前置均已满足且彼此独立，并行最高效。

**Act**：
- `impact-analyzer`：分析影响范围--后端 FavoriteController/Service/Mapper、前端路由/App.tsx/新页面/组件/lib，无数据库变更（file_favorite 表已存在）。
- `experience-reviewer` + `ui-reviewer`：体验评审--页面入口合理性、操作路径、loading/empty/error/disabled 状态覆盖、视觉一致性。

**Evaluate**：应用 Delta -> IMPACT_ANALYSIS=done；EXP_DESIGN=done。依赖检查通过。iteration=3。

**State Delta**：
- exitCriteria: `IMPACT_ANALYSIS` -> done，`EXP_DESIGN` -> done

**State 快照**：`REQ_ANALYSIS=done, IMPACT_ANALYSIS=done, EXP_DESIGN=done`，其余 9 项 pending

> **C13 覆盖**：无依赖的 Agent 被并行派发。

---

## iter3 - TECH_DESIGN

**Observe**：TECH_DESIGN dependsOn=[IMPACT_ANALYSIS, EXP_DESIGN] 两项均 done，可启动。

**Plan**：派 `architect` 产出技术设计。理由：TECH_DESIGN 是 IMPLEMENTED/TESTCASES 的前置，最高价值。

**Act**：architect 产出设计文档--后端分页接口 `GET /favorite/page`、Mapper 分页 SQL、`toggleFavorite` 权限校验方案（B3，计划调用 `validateAccessible`/`countInaccessibleAncestors`）、前端 FavoritesPage/favoriteFileSource/侧边栏/排序/清理遗留代码。

**Evaluate**：应用 Delta -> TECH_DESIGN=done；artifacts.design=done（ref: `.ai/docs/favorites-enhancement-design.md`）。依赖检查：IMPACT_ANALYSIS + EXP_DESIGN 均 done，通过。iteration=4。

**State Delta**：
- exitCriteria: `TECH_DESIGN` -> done
- artifacts: `design` -> done（ref: `.ai/docs/favorites-enhancement-design.md`）

**State 快照**：前 4 项 done，其余 8 项 pending

> **C8 覆盖**：TECH_DESIGN 等 IMPACT_ANALYSIS + EXP_DESIGN 都 done 才推进。

---

## iter4 - TESTCASES

**Observe**：TESTCASES dependsOn=[TECH_DESIGN] 已满足。

**Plan**：派 `tester` 编写测试用例。理由：大型任务门禁要求 IMPLEMENTED 依赖 TESTCASES，须先有用例。

**Act**：tester 产出 19 条测试用例，覆盖侧边栏入口、收藏页展示/操作/视图、优先排序、首页查看全部、分页接口、权限校验、租户隔离、遗留清理、边界异常。

**Evaluate**：应用 Delta -> TESTCASES=done；artifacts.testcases=done。依赖检查通过。iteration=5。

**State Delta**：
- exitCriteria: `TESTCASES` -> done
- artifacts: `testcases` -> done（ref: `.ai/docs/favorites-enhancement-testcases.md`）

**State 快照**：前 5 项 done，其余 7 项 pending

---

## iter5 - 并行 IMPLEMENTED（前端 + 后端）

**Observe**：IMPLEMENTED dependsOn=[TECH_DESIGN, TESTCASES] 两项均 done，可启动。

**Plan**：并行派发 `backend-engineer` + `frontend-engineer`。理由：前后端无相互阻塞，并行编码最高效。

**Act**：
- `backend-engineer`：实现 `pageFavorites` 分页接口、`selectFavoriteNodesPage` Mapper、`toggleFavorite`（**此处遗漏权限校验--为演练注入的安全缺陷**）。
- `frontend-engineer`：新增 FavoritesPage、favoriteFileSource、侧边栏"我的收藏"入口、文件列表收藏优先排序、首页"查看全部"、删除遗留 `src/lib/favorites.ts`。

**Evaluate**：应用 Delta -> IMPLEMENTED=done；artifacts.code=done。依赖检查通过。iteration=6。

**State Delta**：
- exitCriteria: `IMPLEMENTED` -> done
- artifacts: `code` -> done

**State 快照**：前 6 项 done，CODE_REVIEW/SECURITY_REVIEW/EXP_ACCEPT/TEST_PASS/QUALITY_GATE/KNOWLEDGE pending

> **C9 覆盖**：IMPLEMENTED 等 TECH_DESIGN + TESTCASES 都 done 才编码。**C13 覆盖**：前后端并行。

---

## iter6 - 并行 CODE_REVIEW + SECURITY_REVIEW（发现安全问题）

**Observe**：CODE_REVIEW dependsOn=[IMPLEMENTED] done；SECURITY_REVIEW dependsOn=[IMPLEMENTED] done。两者互不依赖，可并行。

**Plan**：并行派发 `reviewer` + `security-reviewer`。理由：两个审查标准前置均满足且独立。

**Act**：
- `reviewer`：代码质量审查通过，CODE_REVIEW PASS。
- `security-reviewer`：审查发现 `toggleFavorite` 仅校验文件存在与状态，**未校验当前用户对目标文件的访问权限**--用户可收藏无权访问的文件节点，违反云盘权限安全规则。新增 blocker B1，SECURITY_REVIEW 不标 done。

**Evaluate**：应用 Delta -> CODE_REVIEW=done；blocker B1=open（raisedBy=security-reviewer, raisedAt=6, attempts=1）；SECURITY_REVIEW 维持 pending（不满足）。iteration=7。

**State Delta**：
- exitCriteria: `CODE_REVIEW` -> done
- blockers: 新增 `B1`（desc: "toggleFavorite 缺权限校验", status: open）

**State 快照**：前 7 项至 CODE_REVIEW=done，`SECURITY_REVIEW=pending`，B1 open

> **C10 覆盖**：CODE_REVIEW/SECURITY_REVIEW 等 IMPLEMENTED done 才审查。**C14 覆盖**：Review 发现问题记 blocker，不退格。

---

## iter7 - rework cascade（后端修复触发级联回退）

**Observe**：B1 open（toggleFavorite 缺权限校验），需后端修复。SECURITY_REVIEW=pending。

**Plan**：派 `backend-engineer` 修复 B1（补权限校验）。理由：open blocker 优先处理，安全缺陷必须修复。

**Act**：backend-engineer 在 `toggleFavorite` 中补权限校验--调用 `fileNodeMapper.countInaccessibleAncestors(nodeId)`，存在不可访问祖先节点则抛 FORBIDDEN。代码已变更。

**Evaluate**：修复完成，Delta 标记 IMPLEMENTED=done（实现含修复，完整）。**代码变更触发 rework cascade**：IMPLEMENTED 下游全部回退 pending--
- `CODE_REVIEW` done->pending（旧 review 针对旧代码，失效）
- `SECURITY_REVIEW` pending（维持）
- `EXP_ACCEPT` pending（维持）
- `TEST_PASS` pending（维持）
- `QUALITY_GATE` pending（维持）
- `KNOWLEDGE` pending（维持）

B1 仍 open（待 security-reviewer 复检确认修复有效）。iteration=8。

**State Delta**：
- exitCriteria: `CODE_REVIEW` -> pending（cascade 回退）
- blockers: `B1` 维持 open（attempts=1，待复检）

**State 快照**：`REQ_ANALYSIS..TESTCASES=done, IMPLEMENTED=done, CODE_REVIEW=pending(回退), SECURITY_REVIEW=pending, EXP_ACCEPT=pending, TEST_PASS=pending, QUALITY_GATE=pending, KNOWLEDGE=pending`，B1 open

> **C15 覆盖**：修复改代码触发 IMPLEMENTED 重开。**C16 覆盖**：IMPLEMENTED 重开 -> 下游全部级联回退 pending。**关键正确性**：rework 改了代码，旧 review 结论失效，必须重验而非沿用 stale 结论。

---

## iter8 - 并行复检 CODE_REVIEW + SECURITY_REVIEW

**Observe**：B1 open，CODE_REVIEW=pending（cascade 回退），SECURITY_REVIEW=pending，代码已修复。IMPLEMENTED=done。

**Plan**：并行派发 `reviewer`（复审 CODE_REVIEW）+ `security-reviewer`（复检 B1/SECURITY_REVIEW）。理由：回退后的标准需重新派发 Agent 复检，非沿用旧结论；两者独立可并行。

**Act**：
- `reviewer`：复审新代码（含权限校验逻辑 + 中文注释），CODE_REVIEW PASS。
- `security-reviewer`：复检 B1--`countInaccessibleAncestors` 权限校验已生效，用户无法收藏无权访问文件。B1 修复有效，SECURITY_REVIEW PASS。

**Evaluate**：应用 Delta -> CODE_REVIEW=done；SECURITY_REVIEW=done；B1=resolved。iteration=9。

**State Delta**：
- exitCriteria: `CODE_REVIEW` -> done，`SECURITY_REVIEW` -> done
- blockers: `B1` -> resolved

**State 快照**：前 8 项至 SECURITY_REVIEW=done，`EXP_ACCEPT/TEST_PASS/QUALITY_GATE/KNOWLEDGE=pending`，blockers=[]

> **C17 覆盖**：回退后的标准被重新派发 Agent 复检（非沿用旧结论）。**C5 覆盖**：Plan 基于当前 State 重新推导（cascade 后重新规划复检，非套用上轮下一步）。

---

## iter9 - 并行 TEST_PASS + EXP_ACCEPT

**Observe**：TEST_PASS dependsOn=[CODE_REVIEW, SECURITY_REVIEW] 两项均 done；EXP_ACCEPT dependsOn=[IMPLEMENTED] done。两者互不依赖，可并行。

**Plan**：并行派发 `tester` + `experience-reviewer`/`ui-reviewer`。理由：测试执行与体验验收前置均满足且独立。

**Act**：
- `tester`：执行 19 条测试用例，全部通过（含 TC-015 权限校验返回 403、TC-016 租户隔离）。
- `experience-reviewer` + `ui-reviewer`：体验验收--收藏页入口/展示/操作/视图切换/排序置顶/空状态均符合设计，EXP_ACCEPT PASS。

**Evaluate**：应用 Delta -> TEST_PASS=done；EXP_ACCEPT=done；artifacts.testReport=done。iteration=10。

**State Delta**：
- exitCriteria: `TEST_PASS` -> done，`EXP_ACCEPT` -> done
- artifacts: `testReport` -> done

**State 快照**：前 10 项至 EXP_ACCEPT=done，`QUALITY_GATE/KNOWLEDGE=pending`

> **C11 覆盖**：TEST_PASS 等 CODE_REVIEW + SECURITY_REVIEW 都 done 才测试。**C13 覆盖**：tester + experience-reviewer 并行。

---

## iter10 - QUALITY_GATE

**Observe**：QUALITY_GATE dependsOn=[TEST_PASS, SECURITY_REVIEW, EXP_ACCEPT] 三项均 done。无 open blockers。

**Plan**：派 `quality-gate` 执行最终门禁。理由：QUALITY_GATE 是收敛点，前置全满足。

**Act**：quality-gate 最终检查--所有验收标准 AC1-AC8 满足、测试全通过、安全审查通过、体验验收通过、文档齐全、无 open blockers。PASS。

**Evaluate**：应用 Delta -> QUALITY_GATE=done。iteration=11。

**State Delta**：
- exitCriteria: `QUALITY_GATE` -> done

**State 快照**：前 11 项至 QUALITY_GATE=done，`KNOWLEDGE=pending`

---

## iter11 - KNOWLEDGE（收敛退出）

**Observe**：KNOWLEDGE dependsOn=[QUALITY_GATE] done。最后一项 pending。

**Plan**：派 `knowledge-manager` 更新知识库。理由：KNOWLEDGE 是唯一剩余标准。

**Act**：knowledge-manager 更新知识库--收藏功能特性、分页接口、权限校验规则同步至 `.ai/knowledge/` 相关文档。

**Evaluate**：应用 Delta -> KNOWLEDGE=done。所有 12 项 exitCriteria 均 done -> `status=done`，EXIT。

**State Delta**：
- exitCriteria: `KNOWLEDGE` -> done
- artifacts: `knowledge` -> done
- status: `running` -> `done`

**终态 State 快照**：全部 12 项 done，status=done，blockers=[]，iteration=11

> **C20 覆盖**：所有 exitCriteria done 才 status:done。**C21 覆盖**：KNOWLEDGE 最后完成。**C22 覆盖**：退出前 State 完整无 pending 遗留。

---

## 验证 Checklist 覆盖映射

| Checklist 项 | 覆盖轮次 | 说明 |
|---|---|---|
| C1 scale 判定并告知用户 | 初始 State | 判定 large，告知"12 项" |
| C2 初始化 State 含 goal/scale/exitCriteria | 初始 State | artifacts 全 pending，blockers 空 |
| C3 exitCriteria 集匹配 scale | 初始 State | large=12 |
| C4 每轮有 Observe | iter1-11 | 每轮均读 State 现状 |
| C5 Plan 基于当前 State 重新推导 | iter8（典型） | cascade 后重新规划复检 |
| C6 Act 传入 State 快照 | iter1-11 | 每轮派发带相关 artifacts/blockers |
| C7 Evaluate 应用 Delta 更新 State | iter1-11 | 含勾选 exitCriteria |
| C8 TECH_DESIGN 等 IMPACT+EXP | iter3 | 两者 done 后才启动 |
| C9 IMPLEMENTED 等 TECH_DESIGN+TESTCASES | iter5 | 两者 done 后才编码 |
| C10 CODE_REVIEW/SECURITY_REVIEW 等 IMPLEMENTED | iter6 | IMPLEMENTED done 后才审查 |
| C11 TEST_PASS 等 CODE_REVIEW+SECURITY_REVIEW | iter9 | 两者 done 后才测试 |
| C12 不跳过未满足 dependsOn | 全程 | 每轮 Evaluate 强制检查 |
| C13 无依赖 Agent 并行派发 | iter2/5/6/8/9 | 五处并行 |
| C14 Review 发现问题记 blocker 不退格 | iter6 | B1 记录，不退格 |
| C15 修复改代码触发 IMPLEMENTED 重开 | iter7 | 代码变更触发 |
| C16 下游级联回退 pending | iter7 | 6 项下游全回退 |
| C17 回退标准重新派发复检 | iter8 | reviewer + security-reviewer 复检 |
| C18 blocker attempts>=3 升级人工 | - | 本演练无 blocker 触达 3 次 |
| C19 超轮次上限升级人工 | - | 本演练 11 轮未触达上限 |
| C20 所有 done 才 status:done | iter11 | 12 项全 done |
| C21 KNOWLEDGE 最后完成 | iter11 | 最后一项 |
| C22 退出前 State 完整无 pending | iter11 | 无遗留 |
| C23 每个 Agent 输出含 State Delta | iter1-11 | 每轮均含 |
| C24 Delta 标注 artifacts/blockers/exitCriteria | iter1-11 | 每轮均标注 |

### 未覆盖项说明

- **C18（死循环升级）**：本演练的 B1 在 iter8 即 resolved，未触达 attempts>=3。该机制已在 `loop-state-model.md` 定义，需构造反复失败场景单独验证。
- **C19（超轮次升级）**：本演练 11 轮收敛，未触达 large=40 上限。同上，需构造超长任务验证。

> C1-C17 + C20-C24 共 22 项被覆盖；C18/C19 为防护机制，依赖 `loop-state-model.md` 文字约束，建议在首个真实任务实测时人工构造失败场景补验。