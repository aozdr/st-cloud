# Agent Loop 白盒演练：死循环与超轮次升级（C18/C19）

> 本文件补齐 `loop-dryrun-favorites.md` 未覆盖的两项防护机制：**C18（blocker 反复失败升级）** 与 **C19（超轮次升级）**。收藏功能演练中 B1 在 iter8 即 resolved、11 轮收敛，未触达这两个升级条件；此处构造专门场景验证。
>
> 升级条件与 attempts 语义见 `.ai/knowledge/loop-state-model.md`「死循环与升级」章节。

---

## 场景一：死循环升级（C18，blocker attempts >= 3）

### 背景

**任务**：新增"文件批量删除"接口（后端 + 前端勾选删除）。涉及文件操作（云盘安全敏感逻辑）-> SECURITY_REVIEW 启用。

**规模**：中型，6 项 + 1 条件项（SECURITY_REVIEW 激活）。

**注入场景**：Security Review 发现批量删除缺所有权校验（用户可删他人文件），后端反复修复均不完整，验证 attempts 自增与升级。

### 退出标准依赖图（中型）

```
DESIGN -> TESTCASES -> IMPLEMENTED -> CODE_REVIEW
                                   -> SECURITY_REVIEW(条件)
TEST_PASS <- CODE_REVIEW, SECURITY_REVIEW
KNOWLEDGE <- TEST_PASS
```

### 逐轮演练

**iter1 - DESIGN**
- Observe：全部 pending，DESIGN 无前置。
- Plan：派 backend-engineer 出设计。
- Act：批量删除接口设计（所有权校验、事务、配额回补）。
- Evaluate：DESIGN=done。iteration=2。

**iter2 - TESTCASES**
- Observe：DESIGN=done，TESTCASES 依赖 DESIGN 已满足。
- Plan：派 tester 写验收用例（轻量）。
- Act：列出验收点（删自己文件、删他人返回 403、配额回补、事务原子性）。
- Evaluate：TESTCASES=done。iteration=3。

**iter3 - IMPLEMENTED**
- Observe：DESIGN+TESTCASES done，可编码。
- Plan：前后端并行编码。
- Act：后端批量删除接口 + 前端勾选删除 UI。
- Evaluate：IMPLEMENTED=done。iteration=4。

**iter4 - CODE_REVIEW（与 SECURITY_REVIEW 并行启动）**
- Observe：IMPLEMENTED done，CODE_REVIEW/SECURITY_REVIEW 均可启动，互不依赖。
- Plan：并行派 reviewer + security-reviewer。
- Act：
  - reviewer：CODE_REVIEW 通过。
  - security-reviewer：发现批量删除仅校验"登录态"未校验"文件所有权"，可越权删除 -> 新增 blocker B1。
- Evaluate：CODE_REVIEW=done；B1=open，attempts=0；SECURITY_REVIEW 仍 pending。iteration=5。
- State Delta：blockers += B1（raisedBy=security-reviewer，attempts=0）

**iter5 - 修复 B1（第一次，失败）**
- Observe：B1 open，需后端修复。
- Plan：派 backend-engineer 修复所有权校验。
- Act：后端补了"单文件删除"接口的所有权校验，但**批量接口的循环里漏传 userId 校验**。
- Evaluate：代码已变更 -> IMPLEMENTED 重开 -> 下游 cascade（CODE_REVIEW/SECURITY_REVIEW/TEST_PASS 回退 pending）。复检 B1：仍 open（批量路径仍可越权）-> **attempts=1**。iteration=6。
- 关键：修复改了代码，触发 rework cascade，但本场景聚焦 attempts 计数，cascade 细节见 favorites 演练。

**iter6 - 修复 B1（第二次，失败）**
- Observe：B1 open，attempts=1，CODE_REVIEW 回退 pending。
- Plan：派 backend-engineer 继续修复 B1 + reviewer 复审。
- Act：后端补了批量循环校验，但**校验用的是请求参数 userId 而非登录态 userId**，可伪造。
- Evaluate：复检 B1：仍 open（可伪造 userId 越权）-> **attempts=2**。iteration=7。

**iter7 - 修复 B1（第三次，失败 -> 升级）**
- Observe：B1 open，attempts=2，接近上限。
- Plan：派 backend-engineer 第三次修复 B1。
- Act：后端改用登录态 userId，但**事务里部分分支漏了校验**。
- Evaluate：复检 B1：仍 open -> **attempts=3 >= 3** -> B1 `status=escalated`，`State.status=blocked_escalation`，**编排器暂停 Loop，交人工处理**。iteration=8。

**终态**：status=blocked_escalation，B1=escalated(attempts=3)，Loop 暂停。人工裁决后恢复 `running`，attempts 不重置（继续累计或人工清零后重试）。

### 覆盖项

| Checklist | 覆盖 | 说明 |
|---|---|---|
| C18 | 是 | attempts 0->1->2->3，第三次未果即 escalated，暂停交人工 |
| attempts 语义 | 是 | 仅"派发修复后仍 open"才 +1；创建时为 0 |
| cascade | 是 | 每次修复改代码触发 IMPLEMENTED 重开+下游回退 |

> 关键验证点：attempts 在"修复后复检仍 open"时才自增，非每轮自增；非修复轮（如 iter4 并行派 reviewer）不触发 B1 的 attempts。

---

## 场景二：超轮次升级（C19，iteration 超上限）

### 背景

**任务**：大型，"团队空间"模块。需求在开发中多次变更，反复触发 rework cascade，导致轮次堆积。

**规模**：大型，12 项，上限 iteration=40。

**注入场景**：需求三次大改，每次改 PRD -> 重开 REQ_ANALYSIS -> cascade 重走 TECH_DESIGN/TESTCASES/IMPLEMENTED 及下游，轮次消耗远超预期。

### 简化逐轮（聚焦轮次累积与升级触发）

- iter1-6：首轮 REQ_ANALYSIS -> IMPACT_ANALYSIS -> EXP_DESIGN -> TECH_DESIGN -> TESTCASES -> IMPLEMENTED。
- iter7-9：CODE_REVIEW / SECURITY_REVIEW / EXP_ACCEPT，发现若干问题，局部 rework。
- iter10-12：TEST_PASS 通过，QUALITY_GATE 前用户提出**需求变更 1**（空间配额规则改变）-> PRD 改 -> REQ_ANALYSIS 重开 -> cascade 重开 TECH_DESIGN/TESTCASES/IMPLEMENTED 及全部下游。
- iter13-22：第二次完整走完设计->实现->审查->测试。
- iter23：QUALITY_GATE 前**需求变更 2**（权限模型调整）-> 再次 cascade。
- iter24-34：第三次重走。
- iter35：**需求变更 3**（存储后端从本地改对象存储）-> 第四次 cascade。
- iter36-40：第四次推进至 TEST_PASS，仍未收敛（QUALITY_GATE/KNOWLEDGE pending）。

**iter41 - 超轮次升级**
- Observe：`iteration=41 > 40（large 上限）`，任务未收敛（QUALITY_GATE/KNOWLEDGE pending）。
- Plan：**不再派发**。升级条件触发。
- Evaluate：`State.status=blocked_escalation`，记录升级原因"超轮次上限（41>40），疑似需求不稳定致反复 rework"，**暂停 Loop 交人工**。

**终态**：status=blocked_escalation，iteration=41，QUALITY_GATE/KNOWLEDGE pending。人工裁决：拆分任务/冻结需求/提高上限后恢复。

### 覆盖项

| Checklist | 覆盖 | 说明 |
|---|---|---|
| C19 | 是 | iteration=41 > large 上限 40 -> 暂停升级人工 |
| cascade 累积效应 | 是 | 需求变更重开 REQ_ANALYSIS 级联重走下游，是轮次超限的根因 |

> 关键验证点：升级在 Observe 段基于 iteration 判定，优先于本轮 Plan；超轮次升级暗示任务本身需拆分或需求需冻结，是 Loop 的"熔断"机制。

---

## 综合覆盖映射（补齐 favorites 演练的未覆盖项）

| Checklist | favorites 演练 | 本演练 | 状态 |
|---|---|---|---|
| C18 死循环升级 | 未覆盖 | 场景一 | 已补 |
| C19 超轮次升级 | 未覆盖 | 场景二 | 已补 |
| C14 记 blocker 不退格 | 覆盖 | 场景一 | 复核 |
| C15 修复改代码触发重开 | 覆盖 | 场景一 | 复核 |
| C16 下游级联回退 | 覆盖 | 场景一/二 | 复核 |

> 至此 loop-verification-checklist.md 的 C1-C24 全部有对应演练覆盖（C18/C19 由本文件补齐）。

## 残留说明

- 本文件为配置驱动型 Loop 的人工模拟，验证的是编排器决策逻辑的正确性，非运行时日志。
- attempts 自增、iteration 上限判定均依赖 `loop-state-model.md` 文字约束，首个真实任务实测时仍建议人工对照打勾。