# Agent Loop V5 操作手册

> 本手册只在中型及以上落地任务或用户明确要求持久化 Loop 时按需读取；小型直接任务、只读咨询和审查不加载完整手册。

## 主线程激活规则

需要落地的中型及以上修改需求，按 Workflow Manager 执行 Observe → Goal → Scale → Plan → Dispatch/Act；只读咨询、诊断和审查直接交付结论。小型低风险修改说明目标、范围和相称验证后直接完成，不初始化持久化 State。

`当前没有收到具体任务`只允许作为已经创建的 child 的 Dispatch 异常；没有真实用户需求时按 AGENTS.md 返回 `DISPATCH_MISSING`，不扫描项目猜任务。

## 用户只需要做什么？

正常开发场景下，用户只描述目标：

> 增加文件分享密码功能

对中型及以上任务，Workflow Manager 应自动完成：

```text
Goal
→ Scale
→ State
→ Plan
→ TASK
→ Dispatch
→ Agent 执行
→ Evaluate
→ Rework
→ Review
→ Test
→ 验收(ACCEPT)
→ Knowledge
```

用户不需要手工输入：

- Agent 名称
- TASK 文件
- State 文件
- Dispatch 字段

## 如何判断 Loop 是否真的在运行？

看到：

```text
Observe
Plan
Dispatch
Act
Evaluate
```

才表示 Loop 在运行。

如果只出现：

```text
Agent Definition
等待任务
```

说明 Dispatch 没有发生。

## 子 Agent 正常启动后的第一句话

必须严格输出：

```text
DISPATCH_ACK
dispatchId: <Envelope.dispatchId>
taskId: <Envelope.taskId>
role: <Envelope.role>
```

不能出现：

```text
请告诉我任务是什么
请下达任务
等待任务
```

ACK 后再校验 Envelope、读取选定的 skillRefs 和最小 State 快照并执行；ACK 前不得调用工具。

## 调度失败处理

如果出现：

```text
当前没有收到具体任务
```

Workflow Manager 应自动进入：

```text
Observe
→ 检查 Dispatch
→ 修复缺失字段
→ 重新 Dispatch
```

而不是要求用户重新描述需求；若异常来自缺少真实用户需求，则按 AGENTS.md 的 `DISPATCH_MISSING` 处理。

## 调度检查清单（Workflow Manager 每轮派发时）

派发前：

- [ ] **依赖就绪**：启动 TASK 前只检查其声明依赖已 Evaluate 且不存在 scope/构建资源冲突；无关 child 不阻塞当前 TASK
- [ ] Envelope 已通过 `.ai/schema/dispatch.schema.json` 校验；不在本手册复制必填字段列表
- [ ] Envelope 含 `forbidSpawn: true`
- [ ] 派发消息 = Role Definition + Dispatch Envelope，未只贴角色定义
- [ ] **自包含派发**：消息含角色声明 + 任务类型 + 当前任务所需 skillRefs + scope 白/黑名单；技能标识由运行时注册表解析
- [ ] **上下文隔离已裁剪**：未携带编排器主会话历史（最小 fork）
- [ ] **fork 运行时约束**：项目不固定 `fork_turns`；以当前 Codex Runtime 实际可用参数为准。无论上下文继承策略如何，任务来源只能是 child 实际收到的 Dispatch Message，不得从父线程历史猜任务
- [ ] **任务类型匹配**：前端/后端任务派 executor（taskType=implement，scope 隔离目录）、评审派 reviewer、测试派 tester，未交叉
- [ ] 每个 Envelope 的 `taskRefs` 只服务一个可独立验收的 TASK；独立 TASK 保持分开并按依赖并行

派发后：

- [ ] `list_agents` 校验层级 = 1（WM -> 专业 Agent）；出现两级以上立即 interrupt 并重派
- [ ] 仅对当前阶段依赖、共享资源或最终收敛所需的 child 使用 `wait_agent`；超时只表示本次等待结束，需按状态继续等待，不把超时当作完成；无关 child 不阻塞阶段推进，子 Agent 之间不互等、不互相派发
- [ ] 子 Agent 首条消息严格为 `DISPATCH_ACK` 三元组，未出现“等待任务”类回复
- [ ] **子 Agent 未发起确认请求**：未调用 request_user_input / 未向用户提问 / 未返回“请确认”类交互；需要用户决策时只返回 confirmationRequest / delegationRequest / BLOCKED / DISPATCH_INVALID
- [ ] 子 Agent 的写入不超出 `scope.include`，`scope.exclude` 始终禁止；读取仅限完成 TASK 所需的相关文件

返回后处理（高危确认）：

- [ ] 子 Agent 返回 `confirmationRequest` 时：Workflow Manager 将 reason/operation/affected/risk/proposedPlan 呈现给用户确认，**不把确认动作交回子 Agent**；确认后生成新的 Dispatch 指示继续

返回后：

- [ ] artifact / changereport 真实存在
- [ ] acceptance 全部满足
- [ ] validation 有真实结果
- [ ] dependsOn 与 blocker 已检查
- [ ] 以上全过才勾选 exitCriteria done

## 需求/设计文档确认门禁（20260815 起）

派发 REQ_ANALYSIS / DESIGN / TECH_DESIGN 相关 TASK 时额外检查：

- [ ] 存在未决范围、兼容性或风险时，`requirement.md` / `design.md` 含「遗留问题点」章节（Grill Me 收敛，≤3 个）；无未决事项时不强制补写该章节
- [ ] 仅当 criterion 设置 `confirmationRequired: true` 时，才需向用户呈现文档并确认影响范围、兼容性或风险方面的未决问题（State 记录 `userConfirmedAt`）
- [ ] 涉及 UI 才派发 EXP_DESIGN/EXP_ACCEPT；无 UI 时在 State 标记 `applicable: false` 并保存 skip 证据
- [ ] 未确认的实质决策未被用于下游 TASK（已明确确认的决策可复用）
- [ ] 文档未出现空话套话/互联网黑话（简洁性检查）

用户确认动作只由 Workflow Manager 向用户发起；子 Agent 不得发起文档确认请求。

## Dispatch 示例

不要在本手册复制 Envelope 字段或示例；使用 [Dispatch 模板](../templates/dispatch-template.md)，并以 `.ai/schema/dispatch.schema.json` 校验后的结果为准。

## 一个健康的 Rework

```text
Security Review
→ B1: 分享下载缺鉴权
→ Workflow Manager 创建修复 TASK
→ Backend 修复
→ code changed
→ CODE_REVIEW stale
→ SECURITY_REVIEW stale
→ TEST_PASS stale
→ Reviewer + Security Reviewer
→ Tester
→ 验收(ACCEPT)
```

不要：

```text
Security Review
→ Backend 自己叫 Reviewer
→ Reviewer 自己叫 Tester
```
