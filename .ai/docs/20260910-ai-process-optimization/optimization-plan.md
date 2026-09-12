# .ai 流程设计优化计划（Grill Me 拷打产出）

> taskId：`20260910-ai-process-optimization` ｜ 日期：2026-09-10 ｜ 状态：R1 已落地

## 一、背景与目标

对 `.ai/` 下的 Agent Loop 流程设计做对抗式拷打（grilling，4 轮前沿问题），把「看起来完备」的协议变成「可执行、可证伪」的门禁，并修正记录层的失真。

目标（可验证）：

1. 静态门禁 `verify-loop.ps1` 退出码 0（基线转绿）
2. 退出标准项数在定义文件与三份文档间一致，且由脚本强制
3. 「标 done」有前置校验，不实记录全部回填并留痕
4. 门禁在提交路径上自动生效，不再依赖人工记得跑

## 二、拷打结论（已定决策）

| 编号 | 决策 | 落实轮次 |
|------|------|----------|
| D1 | 协议单一事实源：AGENTS.md 只留入口门禁与硬约束清单，协议正文下沉 `.ai/` | R2 |
| D2 | 抽机器可读退出标准定义，文档只引用不自行声明项数 | **R1** |
| D3 | 清理僵尸 TASK，静态校验列为改动 `.ai/` 的必跑门禁 | **R1** |
| D4 | 文件收件箱为唯一投递通道，文件名统一用 `taskCode`，去掉 spawn message 双写 | R2 |
| D5 | `skillRefs` 指向 `grilling`（引擎），不再指向 `grill-me`（转发壳） | **R1** |
| D6 | 用户确认门禁可验证化：固定编号的遗留问题点 + State 记录裁决 | R3 |
| D7 | 角色分离硬规则：IMPLEMENTED 的执行者不得同时承担评审/验收，State 记录 `by` | **R1（校验）** / R3（执行） |
| D8 | 「写 done」前置校验：依赖图 + 角色分离 + 产物存在性 + `userConfirmedAt` + Grill 问题点 ≤3 | **R1** |
| D9 | 历史不实 State 回填 `incomplete`/`abandoned` 并附 `backfill` 记录 | **R1** |
| D10 | ACK 判据收敛为「认领文件出现」，去掉文本 ACK 与多层重试阶梯 | R2 |
| D11 | 预提交门禁（`core.hooksPath` → 入库的 `.ai/githooks`） | **R1** |
| D12 | DB 迁移门禁升为独立条件项退出标准（`MIGRATION`） | R3 |
| D13 | Grill 门禁结构化：requirement/design 模板固定「遗留问题点」表，脚本校验 | R3 |
| D14 | 写终态动作收归脚本（除脚本外不得手写 `status: done`） | R3 |
| D15 | 归档用元数据表达（TASK frontmatter / State status），**不**引入目录分层 | R2 |
| D16 | 认领结果写进入库的 State history；`archived/` 仅作运行时现场 | R2 |
| D17 | 删除顺序准入，恢复「全部信封预写 + 并发 spawn」 | R2 |
| D18 | V15 worktree 隔离限缩为条件启用（仅同批次 ≥2 实现线程时） | R3 |
| D19 | iteration 上限按实测下调（large=20 / medium=10 / small=5），超限改为强制报告 | R3 |
| D20 | 验收（ACCEPT）为终态唯一，KNOWLEDGE 为其前置 | **R1（口径）** |

## 三、优化计划（分轮）

### R1 — 修真：让门禁可执行（本轮已完成）

范围：

- 新增 `.ai/loop/exit-criteria.yaml`：三档规模、依赖图、catalog（owner/taskType/产物/条件项/Grill/用户确认）、角色分离规则、State 取值
- 重写 `.ai/scripts/verify-loop.ps1`：以定义文件为源；新增「文档口径一致」「State 诚信」「回填标注跳过」三段
- State 回填 8 份（引用不存在的产物）+ TASK 标注 10 份
- 口径统一：`loop-state-model.md` / `feature-development.md` / `loop-verification-checklist.md`
- 技能映射修正：`grilling`；新增 `stateStatus`/`missing`/`backfill` 定义
- 新增 `.ai/githooks/pre-commit` + `.ai/scripts/install-hooks.ps1`

验收：`verify-loop.ps1` 退出码 0；`git config core.hooksPath` = `.ai/githooks`。

### R2 — 收敛：一份协议，一个通道

- AGENTS.md 瘦身：只保留身份识别、入口门禁、8 条代码约束、DB 版本流程；协议正文下沉
- 删除顺序准入与 spawn message 双写；收件箱文件名统一 `taskCode`（同步 `worktree.ps1`、`dispatch-template.md`、`file-dispatch-runtime.md`、AGENTS.md 1.1）
- 清理历史协议文档：`parallel-dispatch-runtime-v7.md` / `-v8.md` / `task-isolation-migration.md` / `agent-dispatch-protocol.md` 合并或删除
- 删除 `workflow-manager.md` 内 `fork_turns` 自相矛盾段落；模板字段对齐（inbox 模板补 `taskCode`/`etaMinutes`/`skillRefs`）
- 加「同一规则不得在 ≥2 个文件重复表述」的脚本校验

### R3 — 机制：把判定权交给脚本

- 写终态收归脚本；Grill 结构化模板 + 校验；`MIGRATION` 条件项
- 角色分离在真实任务上执行（独立线程评审/验收）
- worktree 条件启用；iteration 阈值下调

## 四、R1 执行记录

| 项 | 结果 |
|----|------|
| 静态门禁 | FAIL 93 → **0**（WARN 18，均为历史 State 缺 `by` 与旧版对比措辞） |
| 定义文件 | `.ai/loop/exit-criteria.yaml`（large=12 / medium=8 含 1 条件项 / small=4） |
| 校验脚本 | `.ai/scripts/verify-loop.ps1`（7 段：定义/门禁/口径/State 诚信/cross-ref/线性表述/配置） |
| State 回填 | 8 份：4 份停滞 `running` → `abandoned`；4 份不实 `done` → `incomplete`（29 份产物标 `missing`） |
| TASK 标注 | 10 份加「回填标注」，校验跳过其悬空引用 |
| 口径修正 | 3 份文档项数与终态（ACCEPT）统一 |
| 技能映射 | `grilling`（引擎）+ design 类型补齐 Grill 技能 |
| 预提交门禁 | `core.hooksPath=.ai/githooks`，涉及 `.ai/**` 或 `AGENTS.md` 的提交自动校验 |

## 五、遗留问题点

| 编号 | 问题 | 影响 | 待裁决 |
|------|------|------|--------|
| P1 | 本轮的 CODE_REVIEW / ACCEPT 由执行者本线程完成，违反 D7 角色分离 | 校验脚本因历史 State 无 `by` 字段只告警不判负；若按新规则执行，本轮评审无效 | 是否由独立线程重做评审，或本轮直接由用户人工验收 |
| P2 | 29 份历史产物永久缺失（`missing`） | 知识库引用已改为「以本文件为准」，但历史迭代无可追溯设计文档 | 接受「记录缺失」现状，还是要求补写其中关键几份 |
| P3 | 预提交门禁会阻塞涉及 `.ai/` 的提交 | 校验不通过时提交被拒（可 `--no-verify` 绕过） | 保留为强门禁，还是改为仅告警 |

## 六、风险

- 校验脚本用正则解析 YAML（PS 5.1 无 YAML 模块），对非预期缩进/写法可能漏解析；已用「项数断言」把漏解析转为显式 FAIL，但新增写法仍需同步脚本
- 回填改变了 9 份 State 的历史语义（`done` → `incomplete`/`abandoned`），虽保留 `backfill` 记录，仍属对审计链的改写
- `core.hooksPath` 为仓库级配置，换环境需重新执行 `install-hooks.ps1`
