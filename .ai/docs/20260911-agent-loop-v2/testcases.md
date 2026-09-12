# Agent Loop V2 测试用例

> Task ID：`TASK-20260911-agent-loop-v2-testcases`
> 关联设计：`.ai/docs/20260911-agent-loop-v2/design.md`
> 日期：2026-09-11
> 状态：待实施后执行

## 一、测试目标

验证 Agent Loop V2 的 Schema、状态迁移、Direct Message Dispatch、Worktree 生命周期、协议迁移和最终验收。测试重点不是“脚本能运行”，而是非法状态不能通过、结论不能复用旧 revision、失败可以安全重试、最终结果可由证据复核。

## 二、执行约定

### 2.1 测试类型

| 类型 | 说明 | 执行时机 |
|---|---|---|
| 静态 | 搜索活跃规则、重复定义、失效引用和越界路径 | 每个 Phase 完成后 |
| 单元 | 使用最小 fixture 验证 Schema、DAG 和状态机 | Phase 1～3 |
| 集成 | 在临时 Git 仓库或隔离工作目录验证 Dispatch、Worktree、CI | Phase 3～6 |
| 迁移 | 对未完成 State 副本执行 dry-run/正式迁移并校验不变量 | Phase 6 |
| 人工验收 | 核对迁移报告、最终清单及用户确认记录 | Phase 0、6 |

### 2.2 通用执行规则

1. 所有破坏性、迁移和 Git 故障注入只在临时目录或复制件执行，不直接改动真实历史记录。
2. 下文以 `loopctl` 表示设计中的统一状态工具；实现可为 PowerShell 脚本或等价入口，但参数语义和退出码必须一致。
3. 成功用例期望退出码 `0`；拒绝类用例期望非零退出码，并包含稳定错误码，不能仅靠自然语言判断。
4. 每条自动化用例保存：命令、fixture、stdout、stderr、退出码、执行时间和被测 revision。
5. 涉及 State 写入的失败用例必须额外比对执行前后摘要，确认失败不产生部分写入。
6. 涉及 Git 的用例必须记录 `baseSha`、`commitSha`、目标分支、HEAD 和 worktree 清单。

### 2.3 建议 fixture 目录

```text
.ai/tests/loop-v2/
├─ fixtures/
│  ├─ state/
│  ├─ dispatch/
│  ├─ migration/
│  └─ protocols/
├─ unit/
├─ integration/
└─ expected/
```

fixture 文件名应包含用例 ID，例如 `TC-FG-01-missing-accept.yaml`，使失败证据可反向定位到本文。

## 三、P0 必测用例

P0 是进入最终验收的最小阻断集；任一失败均不得将 `TEST_PASS` 或 `ACCEPT` 标为 `done`。

| 用例 ID | 类型 | Phase | 场景 | 关键预置/操作 | 预期结果 |
|---|---|---:|---|---|---|
| TC-P0-01 | 集成 | 0 | 当前基线真实性 | 执行现有 `verify-loop.ps1`，核对相关 State、文档、Git 配置 | 退出码 0；三者描述一致 |
| TC-FG-01 | 单元 | 1 | 缺少 ACCEPT | 从合法 medium State 删除 `ACCEPT` 后执行 `loopctl validate` | 非零；`CRITERIA_SET_MISMATCH` |
| TC-FG-02 | 单元 | 1 | 额外 QUALITY_GATE | 向合法 State 加入 `QUALITY_GATE` | 非零；`CRITERIA_SET_MISMATCH` |
| TC-FG-03 | 单元 | 1 | 错误 dependsOn | 修改任一标准的依赖，保持 ID 合法 | 非零；`DEPENDENCY_MISMATCH` |
| TC-FG-04 | 单元 | 1 | 缺用户确认 | 将确认型标准置为 done，但移除确认人、时间或 artifact 之一 | 非零；`CONFIRMATION_EVIDENCE_MISSING` |
| TC-FG-05 | 单元 | 1 | 实现和验收为同一执行者 | IMPLEMENTED 与 ACCEPT 的 `by` 使用同一身份 | 非零；`ROLE_SEPARATION_VIOLATION` |
| TC-FG-06 | 单元 | 1 | open blocker 下标 done | 存在 open/escalated blocker，尝试完成标准或任务 | 非零；`OPEN_BLOCKER` |
| TC-REV-01 | 单元 | 2 | code revision 变化 | Review/Security/Test/Accept 已完成后改变 `revision.code` 并 evaluate | 四项及其后继自动 stale |
| TC-REV-02 | 单元 | 2 | 旧 revision 证据复用 | 用旧 `validatedRevision` 再次 propose/evaluate | 非零；`REVISION_MISMATCH` |
| TC-DSP-02 | 集成 | 3 | ACK 错配 | 返回错误 dispatchId、taskId、role，各执行一次 | 每次均 failed；不得进入 running |
| TC-DSP-04 | 集成 | 3 | ACK_ONLY | Agent 仅返回 ACK，无结果文件和 proposal | 标记 failed；创建新 attempt 的恢复建议 |
| TC-DSP-05 | 集成 | 3 | 重复 attempt | 同一 idempotencyKey 创建两个不同 dispatchId | 旧 attempt 不得覆盖新 attempt；只能有一个结果被 evaluate |
| TC-WT-03 | 集成 | 4 | scope 越界 | 在 include 外或 exclude 内制造改动后执行 commit 阶段 | 提交前拒绝；输出越界路径；无新 commit |
| TC-WT-06 | 集成 | 4 | 孤儿 worktree/branch | 制造无活跃 ledger 对应的 worktree 和 branch 后 strict reconcile | 非零；逐项报告孤儿资源；阻止收敛 |
| TC-MIG-03 | 迁移 | 6 | 未完成 State 迁移 | 对全部 running/incomplete State 副本迁移并由新 Planner 读取 | 全部成为合法 V2 State 且可继续推进 |
| TC-ACC-01 | 人工验收 | 6 | 最终确认 | 用户逐项核对迁移报告和验收清单并记录身份、时间、artifact | ACCEPT 方可由 evaluate 置 done |

## 四、Phase 0：恢复当前记录真实性

### TC-P0-01 当前基线门禁恢复

- 类型：集成。
- 前置：保存当前 State、相关任务文档和 `git config --get core.hooksPath` 输出。
- 步骤：运行现有 `verify-loop.ps1`；核对 `20260910-ai-process-optimization` 仍为未完成；核对悬空 CODE_REVIEW TASK 已完成或明确取消；核对 State 依赖来自当前定义。
- 预期：退出码 0；不存在“文档称已完成、State 或 Git 实际未完成”的差异。
- 证据：验证日志、State 摘要、CODE_REVIEW 处置记录、hooksPath 输出。

### TC-P0-02 hooksPath 描述一致性

- 类型：静态 + 人工验收。
- 步骤：读取仓库实际 `core.hooksPath`；搜索当前文档中“已安装/未安装”描述；对比路径存在性与 hook 文件可执行性。
- 预期：配置、文件和文档三者一致；未安装时必须明确写“未安装”，不能按存在处理。
- 证据：配置值、文件检查结果、对应文档行。

### TC-P0-03 基线记录不得虚假完成

- 类型：单元。
- 步骤：复制基线 State，将未完成任务人工改为 done，但不补 CODE_REVIEW 证据，再执行验证。
- 预期：非零；指出缺失证据或未满足依赖；原文件摘要不变。

## 五、Phase 1：State Schema 与 False Green 门禁

### 5.1 六类 False Green 反例

| 用例 ID | 输入变异 | 执行 | 预期稳定错误码 | 写入不变量 |
|---|---|---|---|---|
| TC-FG-01 | 删除 canonical 集合中的 `ACCEPT` | `loopctl validate <fixture>` | `CRITERIA_SET_MISMATCH` | fixture 不变 |
| TC-FG-02 | 增加未定义的 `QUALITY_GATE` | 同上 | `CRITERIA_SET_MISMATCH` | fixture 不变 |
| TC-FG-03 | 将 `TEST_PASS.dependsOn` 改成错误集合 | 同上 | `DEPENDENCY_MISMATCH` | fixture 不变 |
| TC-FG-04 | 确认型 done 缺 `userConfirmedAt`、确认人或 artifact；三个子例分别执行 | 同上 | `CONFIRMATION_EVIDENCE_MISSING` | fixture 不变 |
| TC-FG-05 | IMPLEMENTED 与 ACCEPT 使用同一 `by` | 同上 | `ROLE_SEPARATION_VIOLATION` | fixture 不变 |
| TC-FG-06 | 保留 open 或 escalated blocker 后执行 `complete`；两个子例分别执行 | `loopctl complete <fixture>` | `OPEN_BLOCKER` | status 不变 |

### 5.2 Schema 与依赖补充用例

| 用例 ID | 类型 | 场景与步骤 | 预期结果 |
|---|---|---|---|
| TC-SCH-01 | 单元 | 合法 small、medium、large State 各 validate 一次 | 均为 0，标准集合分别精确匹配定义 |
| TC-SCH-02 | 单元 | 缺 `schemaVersion`、未知版本、缺 `definitionVersion` 分别验证 | 均非零，错误字段明确 |
| TC-SCH-03 | 单元 | 重复 criterion ID | 非零；`DUPLICATE_CRITERION_ID` |
| TC-SCH-04 | 单元 | done 节点的任一依赖为 pending/stale/blocked，分别执行 | 均非零；`DEPENDENCY_NOT_SATISFIED` |
| TC-SCH-05 | 单元 | large 将 SECURITY_REVIEW skipped | 非零；large 中该标准必选 |
| TC-SCH-06 | 单元 | medium 在定义允许时跳过 SECURITY_REVIEW，并提供完整 skipReason/approvedBy/evidenceRef | 为 0；缺任一字段则非零 |
| TC-SCH-07 | 单元 | 新 V2 State 的 done 记录分别缺 by、dispatchId、evidenceRef、validatedRevision、completedAt | 每个子例均非零且定位缺失字段 |
| TC-SCH-08 | 单元 | 完成态历史 State 标 `legacy: true` 后验证及尝试写入 | 验证可读；任何迁移/修改命令拒绝写入 |
| TC-SCH-09 | 单元 | Dispatch fixture 覆盖合法、缺字段、额外禁用字段、类型错误 | 仅合法 fixture 通过；错误定位到 JSON/YAML path |

## 六、Phase 2：状态迁移工具化

| 用例 ID | 类型 | 场景与执行 | 预期结果 |
|---|---|---|---|
| TC-STM-01 | 单元 | 对同一输入连续执行两次 `init` | 第二次无重复节点、事件或 artifact，结果摘要相同 |
| TC-STM-02 | 单元 | propose 合法 criterionProposal，再 evaluate | Agent 不能直接写 status；evaluate 后才产生状态变化 |
| TC-STM-03 | 单元 | proposal 缺 evidenceRef 或 validatedRevision | 非零；State 与 history 均不变 |
| TC-STM-04 | 单元 | 每个改变状态的命令成功执行 | history 新增结构化事件，含 eventId、动作、前后状态、操作者、时间和 revision |
| TC-STM-05 | 单元 | 重放同一 eventId/proposal | 幂等返回，不能重复完成或重复追加 acceptanceEvidence |
| TC-REV-01 | 单元 | code revision 改变后执行 `stale/evaluate` | CODE_REVIEW、SECURITY_REVIEW、TEST_PASS、ACCEPT 及 DAG 后继 stale |
| TC-REV-03 | 单元 | design revision 改变 | 所有直接或间接依赖设计的已完成标准按 canonical DAG stale |
| TC-REV-02 | 单元 | 使用旧 revision 的 review/test/accept evidence | evaluate 全部拒绝；错误包含期望和实际 revision |
| TC-ACC-02 | 单元 | completionCriteria 有 N 条，仅提供 N-1 条 acceptanceEvidence | ACCEPT 拒绝；报告未覆盖条目；补齐后才通过 |
| TC-STM-06 | 单元 | artifact 从 ready 变 missing | 其提供的 criterion 与全部 DAG 后继自动 stale |
| TC-STM-07 | 单元 | incomplete 分别恢复为 running、废弃为 abandoned | 两条合法路径通过；直接 incomplete→done 拒绝 |
| TC-BLK-01 | 单元 | 同 fingerprint blocker 连续 repair 三次 | repairAttempts[] 追加且不重复 blocker；达到阈值后 escalated |
| TC-BLK-02 | 单元 | 文案不同但 fingerprint 相同的 blocker 再次上报 | 合并到同一 blocker，保留完整 attempts 历史 |
| TC-STM-08 | 单元 | 在落盘前注入写失败 | 原 State 完整保留；备份可恢复；不得留下半写文件 |

## 七、Phase 3：Direct Message Dispatch

| 用例 ID | 类型 | 场景与执行 | 预期结果 |
|---|---|---|---|
| TC-DSP-01 | 单元 | 使用模板生成最小合法 Envelope；逐个删除必填字段重试 | 合法输出通过 schema；缺任一字段时模板拒绝生成 |
| TC-DSP-02 | 集成 | ACK 的 dispatchId、taskId、role 各错一次 | ledger 进入 failed，错误字段明确，不进入 running |
| TC-DSP-03 | 集成 | spawn 后超过 ACK 时限无响应 | attempt 标 failed/timeout；关闭并对账旧 child 后允许新 attempt |
| TC-DSP-04 | 集成 | 只返回 ACK，不产生结果文件/criterionProposal | 判定 ACK_ONLY，禁止 evaluated，给出新 attempt 恢复路径 |
| TC-DSP-05 | 集成 | 同一 TASK、同一 idempotencyKey 启动两个不同 dispatchId | ledger 保留两个 attempt；旧结果不能覆盖新 attempt |
| TC-DSP-06 | 集成 | 同一个 dispatchId 重放 returned/evaluated 事件 | 幂等；结果文件只消费一次，State 只迁移一次 |
| TC-DSP-07 | 单元 | 驱动 planned→spawned→acked→running→returned→evaluated | 每步只允许合法前驱；跳步和逆向迁移拒绝 |
| TC-DSP-08 | 集成 | Agent 返回结果文件后模拟主线程中断，再执行 reconcile | 从 ledger/result 恢复到 returned 或 evaluated，不丢结论 |
| TC-DSP-09 | 集成 | 两个 Agent 同时返回不同结果文件 | 文件互不覆盖；主线程串行合并 changereport，顺序可追踪 |
| TC-DSP-10 | 静态 | 搜索 `.ai/dispatch`、inbox claim、Move-Item claim、双写、第三方 provider 活跃分支 | 当前目录和入口中零活跃命中；archive 命中允许且被标历史 |
| TC-DSP-11 | 单元 | taskCode 相同但 dispatchId 不同，ACK 使用正确主键 | 以 dispatchId/taskId/role 判断；taskCode 不参与认领唯一性 |

## 八、Phase 4：Worktree 生命周期事务化

所有用例在临时 Git 仓库执行，禁止对主仓库故障注入。

| 用例 ID | 类型 | 场景与执行 | 预期结果 |
|---|---|---|---|
| TC-WT-01 | 集成 | 完整执行 prepare→commit→merge→verify→cleanup | 每步 ledger 完整；最终无残留 worktree/branch |
| TC-WT-02 | 集成 | commit 成功后故意制造 merge 冲突；解除冲突后重试 merge | 首次保留 commitSha 和现场；重试不重复 commit 并可继续 |
| TC-WT-03 | 集成 | include 外新增文件、exclude 内修改文件，各执行一次 commit | 两次均在 `git add -A` 前拒绝；列出真实越界路径；无 commit |
| TC-WT-04 | 集成 | prepare、commit、merge、verify、cleanup 各重复执行一次 | 已完成步骤幂等；不重复 worktree、commit、merge 或删除错误资源 |
| TC-WT-05 | 集成 | merge 前分别制造主树脏、目标分支错误、HEAD 漂移 | 每个子例均非零并在 merge 前停止 |
| TC-WT-06 | 集成 | 制造孤儿 worktree、孤儿 branch、无 Agent 的 active dispatch，各执行 strict reconcile | 逐项发现；strict 非零；阻止完成 |
| TC-WT-07 | 集成 | 并行批次第二个 worktree prepare 失败 | 整批停止；不得降级到共享目录；已建资源进入可对账状态 |
| TC-WT-08 | 集成 | verify 失败后执行 cleanup | 默认拒绝清理并保留现场；修复验证后 cleanup 成功 |
| TC-WT-09 | 集成 | worktree 已人工删除但 ledger 未更新，执行 reconcile | 标记差异并给出安全修复动作，不误删其他 worktree |
| TC-WT-10 | 单元 | scope 使用相似前缀、大小写变化、`..`、符号链接逃逸 | 规范化后机械校验；所有逃逸路径拒绝 |

## 九、Phase 5：协议与文档瘦身

| 用例 ID | 类型 | 场景与执行 | 预期结果 |
|---|---|---|---|
| TC-DOC-01 | 静态 | 枚举当前协议入口和 archive 协议 | 当前协议恰好一份；archive 文件不被入口引用为现行规则 |
| TC-DOC-02 | 静态 | 对身份、ACK、状态、Worktree 等硬规则生成规范化文本指纹 | 同一硬规则不得在两个权威文件重复定义 |
| TC-DOC-03 | 静态 | 检查 AGENTS.md 内容类别 | 只保留身份、入口、安全边界、确认门禁、代码和数据库硬约束 |
| TC-DOC-04 | 静态 | 搜索 V14/V15、File Inbox、第三方模型、顺序认领和双写 | 活跃文件零运行时分支；只允许 archive 历史说明 |
| TC-DOC-05 | 静态 | 校验所有内部 Markdown 路径引用 | 当前引用存在；归档前后映射完整；无断链 |
| TC-DOC-06 | 单元 | 用协议样例验证 ACK 顺序、taskRef/taskRefs、pending/stale、ACCEPT/KNOWLEDGE | 文档、schema 与工具行为一致，无互斥定义 |
| TC-DOC-07 | 静态 | 检查体验文档 artifact 路径 | EXP_DESIGN 仅指向 `exp-review.md`；EXP_ACCEPT 仅指向 `exp-accept.md` |
| TC-DOC-08 | 人工验收 | 随机选择三条规则，从 AGENTS 入口追踪到唯一协议/schema/工具 | 均可单向追踪，不需在多版本规则中裁决 |

## 十、Phase 6：CI、迁移与最终验收

| 用例 ID | 类型 | 场景与执行 | 预期结果 |
|---|---|---|---|
| TC-MIG-01 | 迁移 | 对未完成 State 副本执行 dry-run | 输出确定性迁移计划；源文件无变化；重复 dry-run 相同 |
| TC-MIG-02 | 迁移 | 对完成态历史 State 执行迁移扫描 | 内容摘要不变，仅按设计允许方式识别为 legacy/只读 |
| TC-MIG-03 | 迁移 | 迁移全部 running/incomplete State，再由新 Planner 逐个 plan | 全部通过 V2 validate 且可继续推进；无不可达状态 |
| TC-MIG-04 | 迁移 | 在迁移中途注入失败后重跑 | 失败不半写；重跑可恢复；迁移报告记录失败 attempt |
| TC-CI-01 | 集成 | 本地与 CI 分别执行 schema、State、协议引用、worktree strict 校验 | 使用同一入口和规则；两边均退出 0 |
| TC-CI-02 | 集成 | 模拟找不到 PowerShell、找不到仓库根 | pre-commit 均非零，不能 fail-open |
| TC-CI-03 | 静态 | 全仓搜索活跃 File Inbox 与第三方 provider 路径 | archive 外零命中；archive 命中不会被脚本或协议入口加载 |
| TC-CI-04 | 集成 | 运行六类 False Green 与全部 P0 反例集 | 反例全部按预期失败；测试框架自身退出 0 |
| TC-CI-05 | 集成 | 启动和结束各执行 reconcile | 无 open blocker、孤儿 worktree/branch、未评估 dispatch |
| TC-CI-06 | 单元 | 修改一个已被 review/test/accept 的代码 revision 后跑总门禁 | 门禁非零并报告 stale；重新验证新 revision 后恢复为 0 |
| TC-ACC-01 | 人工验收 | 用户审阅最终迁移报告和验收清单 | 记录确认人、时间、artifact 和当前 revision 后才允许 ACCEPT done |
| TC-ACC-03 | 集成 | 执行最终 `loopctl complete` | 仅在所有标准、证据、资源对账通过时为 0；否则列出全部阻断项 |

## 十一、Goal 完成标准追踪矩阵

| State completionCriteria | 主验证用例 | 辅助用例 | 通过判据 |
|---|---|---|---|
| File Inbox 与第三方模型兼容仅保留于 archive 历史资料，不存在活跃执行路径 | TC-DSP-10、TC-CI-03 | TC-DOC-01、TC-DOC-04 | archive 外零活跃入口或分支；archive 不被加载 |
| 非法 State、错误依赖和无证据 done 会被严格门禁拒绝 | TC-FG-01～06 | TC-SCH-02～09、TC-STM-03 | 全部非法 fixture 非零，且失败不写 State |
| Review、Security、Test、Accept 证据绑定当前 revision | TC-REV-01、TC-REV-02 | TC-REV-03、TC-CI-06 | revision 改变触发 stale；旧证据拒绝 |
| Worktree 生命周期幂等、scope 可机械校验、残留资源可对账 | TC-WT-01、TC-WT-03、TC-WT-06 | TC-WT-02、04、05、07～10 | 重试无副作用；越界拒绝；孤儿使 strict 非零 |
| 当前协议只有一份，AGENTS.md 不再重复协议正文 | TC-DOC-01、TC-DOC-02、TC-DOC-03 | TC-DOC-05、06、08 | 唯一现行协议；无权威文件重复硬规则 |

## 十二、设计完成标准追踪矩阵

| design.md 第九节完成标准 | 覆盖用例 |
|---|---|
| 所有 Phase 验收项通过 | TC-P0-01～03、TC-SCH-01～09、TC-STM-01～08、TC-DSP-01～11、TC-WT-01～10、TC-DOC-01～08、TC-MIG-01～04、TC-CI-01～06、TC-ACC-01/03 |
| `verify-loop` 与新 schema/state 测试退出码均为 0 | TC-P0-01、TC-SCH-01、TC-CI-01 |
| 六类 False Green 反例全部失败 | TC-FG-01～06、TC-CI-04 |
| Review/Test/Accept 证据均绑定当前 revision | TC-REV-01～03、TC-CI-06 |
| 未完成任务全部迁移；完成态历史记录未被篡改 | TC-MIG-01～04 |
| File Inbox 与第三方模型兼容只存在于 archive 历史说明中 | TC-DSP-10、TC-DOC-04、TC-CI-03 |
| 用户对最终迁移报告和验收清单确认通过 | TC-ACC-01 |

## 十三、Phase 验收追踪矩阵

| Phase | 设计验收要求 | 覆盖用例 |
|---:|---|---|
| 0 | verify-loop 为 0；文档、State、Git 配置真实一致 | TC-P0-01～03 |
| 1 | 六类 False Green 全拒绝；Schema、集合、依赖、确认、角色、blocker 严格校验 | TC-FG-01～06、TC-SCH-01～09 |
| 2 | revision cascade、旧证据拒绝、incomplete 恢复/废弃、逐项验收证据 | TC-STM-01～08、TC-REV-01～03、TC-ACC-02、TC-BLK-01～02 |
| 3 | Envelope 完整；ACK 异常、无 ACK、ACK_ONLY、重复 attempt 有确定恢复路径；无 File Inbox | TC-DSP-01～11 |
| 4 | merge 可重试；scope 越界拒绝；孤儿资源阻止收敛 | TC-WT-01～10 |
| 5 | 当前协议唯一；AGENTS 无重复正文；历史可追溯但非现行 | TC-DOC-01～08 |
| 6 | 本地/CI 为 0；无活跃兼容路径；State 可续跑；无 blocker/孤儿/未评估 dispatch | TC-MIG-01～04、TC-CI-01～06、TC-ACC-01/03 |

## 十四、执行顺序与退出门禁

1. Phase 0 先跑 TC-P0-01～03，建立可信基线。
2. Phase 1～2 先跑 Schema/状态机单元测试，再允许 Dispatch 和 Worktree 集成测试。
3. Phase 3～4 的失败注入必须使用隔离环境；先验证正常路径，再验证恢复路径。
4. Phase 5 静态门禁通过后才执行迁移，避免新 State 引用旧协议。
5. Phase 6 先 dry-run，后迁移副本，再迁移真实未完成 State；最后执行完整 P0 集合。
6. 自动化全部通过后生成测试报告，列出用例 ID、结果、证据路径和 revision。
7. 用户确认 TC-ACC-01 前，`ACCEPT` 保持 pending；任何 P0 失败时，`TEST_PASS` 不得 done。

## 十五、遗留问题点

无。具体脚本文件名可在实现阶段调整，但必须保留本文定义的行为、稳定错误分类、退出码语义和追踪关系。
