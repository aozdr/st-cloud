# Agent Loop V5 操作手册

## 主线程激活规则\n\n当前会话只要收到真实用户需求，就立即按 Workflow Manager 执行 Observe → Goal → Scale → Plan → Dispatch/Act；不得回复“待命”“尚未收到具体需求”或“请直接描述任务”。\n\n`当前没有收到具体任务`只允许作为已经创建的 child 的 Dispatch 异常。\n\n## 用户只需要做什么？

正常开发场景下，用户只描述目标：

> 增加文件分享密码功能

Workflow Manager 应自动完成：

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

不要求固定文案，但必须体现：

```text
已读取 TASK
已读取 State
已确认 Scope
我是 <role>，任务类型 <type>
开始执行
```

不能出现：

```text
请告诉我任务是什么
请下达任务
等待任务
```

> V2：首条消息必须同时声明角色身份与任务类型（backend/frontend/review/security/test/doc），让调度方与用户能立即确认“谁在做、做什么类型的事”。

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

而不是要求用户重新描述需求。

## 调度检查清单（Workflow Manager 每轮派发时）

派发前：

- [ ] **环节串行**：当前环节对应的上一环节全部子线程已关闭（list_agents 无残留），才开启本环节新线程
- [ ] Envelope 六必填齐全：`taskRef` / `stateRef` / `objective` / `scope` / `acceptance` / `validation`
- [ ] Envelope 含 `forbidSpawn: true`
- [ ] 派发消息 = Role Definition + Dispatch Envelope，未只贴角色定义
- [ ] **自包含派发**：消息含角色声明 + 任务类型 + 技能 SKILL.md 路径 + scope 白/黑名单
- [ ] **上下文隔离已裁剪**：未携带编排器主会话历史（最小 fork）
- [ ] **fork 运行时约束**：项目不固定 `fork_turns`；以当前 Codex Runtime 实际可用参数为准。无论上下文继承策略如何，任务来源只能是 child 实际收到的 Dispatch Message，不得从父线程历史猜任务
- [ ] **任务类型匹配**：前端/后端任务派 executor（taskType=implement，scope 隔离目录）、评审派 reviewer、测试派 tester，未交叉
- [ ] **关联任务已合并**：有依赖/强关联的多个 TASK 合并给同一 Agent（taskRefs 列出全部），未拆成多个并行 Agent

派发后：

- [ ] `list_agents` 校验层级 = 1（WM -> 专业 Agent）；出现两级以上立即 interrupt 并重派
- [ ] 用 `wait_agent` 等待该 Agent 单次返回；子 Agent 之间不互等、不互相派发
- [ ] 子 Agent 首条消息已声明角色/任务类型，未出现“等待任务”类回复
- [ ] **子 Agent 未发起确认请求**：未调用 request_user_input / 未向用户提问 / 未返回“请确认”类交互；需要用户决策时只返回 confirmationRequest / delegationRequest / BLOCKED / DISPATCH_INVALID
- [ ] 子 Agent 全程未读取 scope 白名单之外的目录（抽查其读取范围）

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

- [ ] `requirement.md` / `design.md` 已含「遗留问题点」章节（Grill Me 拷打收敛，≤3 个）
- [ ] 文档路径已呈现给用户，用户已逐项拍板（State 记录 `userConfirmedAt`）
- [ ] 未确认前未派发任何下游 TASK（IMPACT_ANALYSIS / TESTCASES / IMPLEMENTED 等）
- [ ] 文档未出现空话套话/互联网黑话（简洁性检查）

用户确认动作只由 Workflow Manager 向用户发起；子 Agent 不得发起文档确认请求。

## 最小可执行 Dispatch

```yaml
dispatch:
  dispatchId: "DISPATCH-20260813-demo-01"
  taskId: "20260813-demo"
  role: "executor"
  taskRefs: [".ai/tasks/TASK-20260813-demo-xxx.md"]
  stateRef: ".ai/state/20260813-demo.yaml"
  objective: "实现分享密码校验"
  exitCriterion: "IMPLEMENTED"
  forbidSpawn: true
  scope:
    include: ["st-share/**", "docker/mysql/init/**"]
    exclude: ["st-web/**", "st-desktop/**", ".ai/**"]
  acceptance:
    - "错误密码拒绝访问"
    - "正确密码允许访问"
  validation:
    - "mvn test"
  output:
    changereportRef: ".ai/docs/20260813-demo-xxx/changereport.md"
  mode: "execute"
```

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
