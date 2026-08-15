# File Inbox Dispatch Runtime V2 — 多文件认领式投递

> 本项目协议文档。针对非 OpenAI provider（DeepSeek 等）下 Codex Multi-Agent V2 的任务消息投递缺陷，提供不依赖 spawn message 的确定性投递通道。V2 起改为“一任务一文件 + 原子认领”，实现文件级任务隔离。

## 背景（为什么需要它）

已核实的运行时缺陷（openai/codex#37822，本机 CLI 0.147.0-alpha.6.6 / Desktop 26.803.81509 复现）：

- `spawn_agent` / `followup_task` 的任务文本被框架放入 `encrypted_content`，`content` 只有空的 NEW_TASK 信封；
- 子代理上下文只构建自 `content`，`encrypted_content` 被静默丢弃（无解密错误日志）；
- DeepSeek 的 Responses 端点无法消费该通道，因此子代理永远收不到任务，表现为“待命 / DISPATCH_MISSING / 把自己当主线程”。

Wake-up 通道正常：子代理会被创建、轮次会被触发，只是拿不到任务文本。

结论：**在 DeepSeek 下，spawn message 不是可靠投递通道；文件收件箱才是。**

## 为什么是多文件认领（V2）

身份探针实测（2026-08-14）：子代理上下文中没有任何自身身份字段（`agent_path` / `task_name` 均不可见），因此“按名字读 `inbox-<task_name>.md`”不可行。V2 改为：主线程为每个任务写一个独立文件，子代理用**原子 Move-Item 认领**——两个子代理同时认领也永远不会读到同一个文件，实现文件级任务隔离。

## 适用条件

- 子代理首轮上下文**没有可见派发内容**（无 DISPATCH_ENVELOPE / dispatchId / taskId），且**没有真实用户需求文本**（只有系统注入内容）；
- 或子代理后续轮次被 followup_task / send_message 触发，但上下文仍无可见派发内容。

主线程（有真实用户消息）不进入本模式。

## 目录与文件

```text
.ai/dispatch/inbox-<dispatchId>.md            # 每个任务一个独立收件箱文件（UTF-8 无 BOM）
.ai/dispatch/archived/inbox-<dispatchId>.md   # 已被子代理认领的文件（同名原子移动）
```

- 文件名中的 dispatchId 全局唯一，不得复用。
- 整个 `.ai/dispatch/` 目录已被 .gitignore 忽略，属于运行时状态，不入库。

## 信封格式（inbox-<dispatchId>.md）

主线程在 spawn 前写入，UTF-8 无 BOM（模板见 dispatch-template.md 的 File Inbox 节）：

```text
FILE_INBOX_DISPATCH v1
status: pending
dispatchId: <dispatch-id>
taskCode: <短码，如 SEC-01，全局唯一>
etaMinutes: <预计执行分钟数，用于超时中断>
taskId: <task-id>
taskRef: <task-file>
stateRef: <state-file>
role: <role>
taskType: <requirement|discovery|impact|architecture|design|implement|ui-design|knowledge|review|security|ui-review|exp-review|accept|testcases|test>
skillRefs:
- <技能 SKILL.md 绝对路径（按 skill-mapping.md 映射，至少 1 项；无适用技能填 "-"）>
objective: <唯一目标>
exitCriterion: <当前负责的退出标准>
scope.include:
- <允许范围>
scope.exclude:
- <禁止范围>
acceptance:
- <客观验收标准>
validation:
- <验证命令/步骤>
forbidSpawn: true

<<<CHILD_DISPATCH_END>>>

【唯一任务】
<把 TASK 的目标、必要输入、执行要求用自然语言完整写在这里>
```

## 主线程（Workflow Manager）写入流程

```text
1. 构建并校验全部 Dispatch Envelope（字段齐全，dispatchId/taskCode 唯一）
2. **顺序准入（硬规则）**：一次只放一个 pending 信封并 spawn 一个 child——
   `写 inbox-<taskCode>.md → spawn → 等 ACK(taskCode 匹配) → 再写下一个`
   （防止多信封并发导致子代理串领/错位；ACK 后已 spawn 的 child 与后续 child 并发执行）
3. spawn_agent(task_name=<唯一名>, message=<同一完整信封>, fork_turns="none")
4. 等待 DISPATCH_ACK（ACK.dispatchId/taskCode 匹配，且对应 archived/inbox-<taskCode>.md 存在）后，才允许写下一个信封并 spawn
5. 未 ACK / DISPATCH_MISSING(inbox empty) → 检查对应文件是否仍在，重试最多 2 次
6. 按 taskCode 归集各 child 结果，不依赖子代理名称
```

ACK 判定：子代理回复 `DISPATCH_ACK` 且带 dispatchId / taskId / role，且 `.ai/dispatch/archived/inbox-<dispatchId>.md` 存在（已被原子认领）。

## 子代理消费流程（认领三步）

见 AGENTS.md 1.1 节。唯一允许的 ACK 前工具调用：

```text
1. 列出 .ai/dispatch/inbox-*.md（按文件名排序）；无候选 → DISPATCH_MISSING: inbox empty
2. 原子认领：Move-Item 第一个候选到 .ai/dispatch/archived/（同名）
   失败（已被其它子代理认领）→ 取下一个候选重试；全部失败 → INBOX_CONFLICT
3. 读取认领到的文件，校验必填字段（缺失 → DISPATCH_INVALID: <缺失字段>）
4. 认领确认：taskRef != none 时校验 TASK 文件存在且与信封匹配（不匹配 → INBOX_MISMATCH，附 claimedFile + dispatchId，不执行）
5. 输出 DISPATCH_ACK（dispatchId / **taskCode** / taskId / role / objective / claimedFile）——认领声明；taskCode 为"我领到了码 X"的确认
6. **技能加载（自主发现）**：读取 skillRefs 指向的每个 SKILL.md 全文（缺失 → DISPATCH_INVALID: skillRefs）；随后**自主扫描已安装技能**（上下文技能目录或 shell 扫描 `.agents/skills/`、CODEX_HOME/skills，读 frontmatter name/description），按任务类型/技术栈匹配并读取适合当前任务的全部 SKILL.md
7. 读取 taskRef / stateRef 并按 scope / acceptance / validation 执行
```

> **技能自主发现**：主线程按 skill-mapping 填充 `skillRefs` 仅作参考最小集；child 读取任务后**自行扫描已安装技能并按任务匹配加载**——不依赖预填完备性，认领错位/预填缺失时技能仍与任务匹配。

## 并发与隔离

- 每个任务一个独立文件，内容互不可见；认领后原文件消失，其它子代理不可能再读到它；
- 原子 Move-Item 保证两个子代理并发认领时恰好各得一个文件（先到先得，失败者自动换下一个）；
- 主线程按 dispatchId 归集，不要求“child A 必须拿任务 A”；
- spawn 仍建议顺序（便于逐个确认），但文件写入与 spawn 不再需要串行等待。

## followup / rework / send_message

- 返工/rework 主路径 = 新文件 `inbox-<新dispatchId>.md` + 新 child（已验证可靠）；原 child 保持完成态即可。
- 原地 followup 为辅助路径：仅当目标 child 仍在执行中需要追加载荷时使用；被唤醒的 child 必须按 AGENTS.md 1.1 节先重查收件箱（禁止复述旧结论）。实测（2026-08-14）：**已完成与运行中的 child 被 followup 唤醒后均不重查收件箱**，因此**禁止把返工押注在原地唤醒上**。

## 实测记录（2026-08-14）

| 场景 | 结果 |
|------|------|
| V1 单收件箱：顺序 spawn 两个 child | 通过 |
| V1 rework：新收件箱 + 新 child | 通过 |
| V1/V2 原地 followup（已完成 / 运行中 child） | 失败（运行时限制）；新 child 兜底通过 |
| V2 多文件认领：双文件预写 + 双 child | 通过：各自认领不同文件、dispatchId 正确、互不可见（详见 testreport.md） |

结论：**spawn（含重派）是唯一可靠派发入口；多文件认领提供文件级隔离；原地 followup 不可用于注入新任务。**

## 冲突与失败处理

| 现象 | 判定 | 处理 |
|------|------|------|
| DISPATCH_MISSING: inbox empty | 无 `inbox-*.md` 候选 | 主线程检查写入，重派（最多 2 次） |
| DISPATCH_INVALID: <字段> | 认领到的信封字段缺失 | 主线程补全字段后重派 |
| INBOX_MISMATCH: <claimedFile>/<dispatchId> | 认领到的信封与 TASK 文件不匹配/文件缺失 | 主线程核对信封与 TASK，修正后重派 |
| INBOX_CONFLICT | 全部候选认领失败（权限/占用） | 主线程人工检查 `.ai/dispatch/` 后重派 |
| child 回复待命/主线程文案 | child 未进入收件箱模式 | 判定 AGENTS.md 兜底未生效，检查注入版本后重派 |
| DISPATCH_ACK_ONLY（completed 仅为 ACK 无产出） | child 未真正执行任务 | 第 1 次重派（注明"必须完整执行"）；第 2 次仍 ACK_ONLY → 小型/单文件任务主线程降级直接执行，中大型标记 DISPATCH_FAILED 转人工（以产物/taskCode 检测） |

两次重派仍失败：输出 `DISPATCH_RUNTIME_INJECTION_FAILED`，同时保留收件箱现场供人工检查。此时不再修改 Prompt，转人工排查。

## 主线程生命周期与认领核对（2026-08-14 追加）

1. **用完即关**：子线程返回最终结果、主线程完成数据收集后，立即 `interrupt_agent` 关闭该子线程（不得滞留到 `pending_init`——实测该状态无法 interrupt 释放且占用并发槽位，会导致 `agent thread limit reached`）。
2. **taskCode 认领核对**：每个信封带全局唯一 `taskCode` 短码；子代理 ACK 必须报出所领信封的 taskCode；主线程维护"派发计划：taskCode → 任务"映射，收集时核对每个 taskCode 恰好被一个线程认领且与计划一致；重复/漏领/错位按 taskCode 归集或 interrupt 重派。
3. **确认边界**：受运行时限制（主线程消息无法到达子代理），"确认无误后才开始"= 子代理自查（信封字段完整 + taskRef 匹配）后 ACK 报 taskCode + 主线程收集时核对；双向实时握手不可行，子代理不得等待主线程回复。
4. **每次 spawn 前**执行 `list_agents`，确认无残留（滞留线程先清理）。

## 严格执行检测与超时中断（2026-08-14 追加）

1. **产物验证（硬规则）**：子线程 completed 后，主线程必须核对 TASK 约定的产物（changereport/文件）是否存在且内容含 `taskCode`；仅回复 ACK 或产物缺失/不匹配 → 判定未执行（`DISPATCH_ACK_ONLY`），按应对方案重派或降级直接执行。
2. **会话日志抽查**：对可疑线程（completed 快/无写操作），抽查会话日志确认读取了 TASK/SKILL 并有产出动作。
3. **ETA 计时与超时中断**：信封 `etaMinutes` 为预计执行时间；主线程 spawn 时记录开始时刻，超时（按 1.5×eta 容差）未 completed → `interrupt_agent` 中断并标记 `ABNORMAL/TIMEOUT`，按异常处理（重派或降级，记录原因）。
4. 子代理侧：执行中如明显超过 ETA，在回复中自标 `STATUS: OVERTIME` 供主线程识别。

## 与既有文档关系

- 本协议不改变 Dispatch Envelope 字段定义（见 dispatch-template.md）；
- 在 spawn message 可用的环境（官方 OpenAI provider），message 仍是首选通道，文件收件箱作为双写冗余；
- V8 顺序准入（parallel-dispatch-runtime-v8.md）与 V14/V15.1（workflow-manager.md）继续有效，本协议将其“写收件箱”环节升级为“预写多文件 + 原子认领”。

## 验证方式

- 多文件隔离测试：预写 `inbox-<A>.md` / `inbox-<B>.md` 两个独立文件并 spawn 两个 child，验收：两个 child 各认领一个不同文件、ACK 携带各自 dispatchId、会话日志确认未读取对方文件；
- 收件箱为空：无候选时 spawn 探针，期望 `DISPATCH_MISSING: inbox empty`；
- rework：新文件 + 新 child 消费追加载荷。
