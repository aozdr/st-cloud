# Agent Dispatch Protocol V5

## 核心原则

```text
用户自然语言
    ↓
Workflow Manager
    ↓
Goal / Plan / TASK
    ↓
Dispatch Builder
    ↓
Child Agent
```

用户不负责 TASK，Agent 不负责理解整个用户需求。

## 1. 用户层

用户只描述：

- 想做什么；
- 现象/问题；
- 目标效果；
- 已知约束（如果有）。

例如：

```text
文件分享链接增加过期时间，并补测试。
```

## 2. 编排层

Workflow Manager 自动负责：

- Goal
- 规模
- Plan
- Agent 选择
- TASK 创建
- 依赖
- Dispatch
- Evaluate
- Rework

## 3. 执行层

子 Agent 只接收：

- Role
- TASK
- Dispatch
- State 最小快照
- 明确 artifact
- 明确 skills

不得自己从整个用户需求推导新的工作。

## 4. Runtime

执行型子 Agent 必须使用当前 Codex Runtime 实际支持的“带任务消息启动”方式。

关键不是某个配置字符串，而是：

> **spawn 时必须真的把 Dispatch Message 传给子线程。**

如果子线程只收到：

```text
AGENTS.md
Skills
Workspace
```

却没有 TASK：

```text
DISPATCH_FAILED
```

不得让子 Agent等待用户。

## 5. Context

如果 Runtime 支持 fresh/bounded child，则优先使用。

如果当前 Runtime 采用父线程继承上下文，则：

- 不把父线程历史当作任务来源；
- Dispatch Message 必须明确“唯一任务”；
- scope 作为工作边界；
- 不能声称 Prompt 本身实现了真正的上下文隔离。

## 6. 错误

```text
DISPATCH_INVALID
```

派发包缺字段。

```text
DISPATCH_FAILED
```

子线程没有真正收到 TASK。

```text
BLOCKED
```

真实外部条件阻塞。

```text
FAILED
```

执行失败。

## 7. 完成

```text
Child Agent
   ↓
State Delta
   ↓
Workflow Manager Evaluate
   ↓
Pass / Rework
```

子 Agent 的 done 不等于用户需求完成。


# V6 Runtime Injection Hard Gate

## 1. “已生成 Dispatch”不等于“已派发 Dispatch”

必须区分：

```text
Dispatch Built
```

和：

```text
Dispatch Delivered
```

只有当完整 Dispatch Envelope 实际出现在 child 的启动 message 中，才算 Delivered。

```text
TASK
 ↓
Build Dispatch
 ↓
Validate
 ↓
spawn(message=<Dispatch>)
 ↓
DISPATCH_ACK
 ↓
Delivered
```

仅仅把：

```text
.ai/tasks/TASK-xxx.md
.ai/state/xxx.yaml
```

写入磁盘，或者在 Workflow Manager 自己的上下文中生成 YAML，都不能证明子 Agent 收到了任务。

## 2. Dispatch Envelope 必填字段

为了兼容执行 Agent 对错误字段的检查，单任务必须同时提供：

```yaml
taskId: "TASK-..."
taskRef: ".ai/tasks/TASK-xxx.md"
taskRefs:
  - ".ai/tasks/TASK-xxx.md"
stateRef: ".ai/state/....yaml"
role: "..."
taskType: "implementation | review | test | design | security | documentation"
objective: "..."
exitCriterion: "..."
scope:
  include: []
  exclude: []
acceptance: []
validation: []
forbidSpawn: true
```

`taskRef` 是当前唯一任务文件；`taskRefs` 只是兼容字段，不得用多个 TASK 混淆一个 child。

## 3. Spawn Hard Gate

Workflow Manager 在执行 spawn 前必须在本轮内部完成：

```text
dispatchMessage = 完整自包含字符串
assert dispatchMessage 包含：
  dispatchId
  taskId
  taskRef
  stateRef
  role
  taskType
  objective
  exitCriterion
  scope
  acceptance
  validation
  forbidSpawn
```

然后：

```text
spawn(..., message=dispatchMessage)
```

**不能只说“请执行 TASK-001”，也不能只传 role。**

## 4. Child ACK

child 第一条响应：

```text
DISPATCH_ACK
```

必须带：

```text
dispatchId
taskId
role
objective
```

如果没有 ACK：

```text
DISPATCH_FAILED
```

Workflow Manager 只重派该 dispatch。

## 5. 错误分类

```text
DISPATCH_INVALID
```

child 收到 message，但 message 缺字段。

```text
DISPATCH_FAILED
```

child 没有收到 TASK 或没有 ACK。

```text
DISPATCH_RUNTIME_INJECTION_FAILED
```

Workflow Manager 已构建完整 message，但实际 spawn 调用没有把 message 注入 child。

`DISPATCH_*` 都不是业务 blocker，不增加 blocker.attempts。

## 6. 并行派发原子性

N 个并行 TASK 必须有 N 个独立 message：

```text
TASK-FE → Dispatch-FE → message_FE → child_FE
TASK-BE → Dispatch-BE → message_BE → child_BE
```

禁止共享 message。

一个 child 失败时，只重派对应 dispatch。

## 7. 自然语言用户入口

用户永远不需要提供上述字段。

Workflow Manager 自动完成：

```text
用户一句话
↓
Goal
↓
Plan
↓
TASK
↓
Dispatch Message
↓
spawn(message=...)
```


## Runtime Override V6 — 以实际送达为准

本项目已验证的关键事实：**直接把任务文本传入 child 创建动作，可以正常工作。**

因此执行型 child 当前采用 `fork_turns`。上下文可能包含父线程历史，但这不是任务来源；任务来源必须是 child 创建动作实际收到的完整 `dispatchMessage`。

### 不得把“文件落盘”当作“消息已送达”

必须验证：

```text
actual child input contains:
  taskRef
  stateRef
  objective
  scope
  acceptance
  validation
  role
  taskType
```

### DISPATCH_INVALID 恢复

收到 `DISPATCH_INVALID` 后，Workflow Manager 必须重新创建 child，并把完整 `dispatchMessage` 直接放入 child 的实际任务输入；不能停在错误消息，也不能向用户索要任务。

同一 TASK 自动重试 2 次；仍失败才报告 `DISPATCH_RUNTIME_INJECTION_FAILED`。

### File Inbox 兜底（非 OpenAI provider）

**2026-08-14 核验**：DeepSeek 等非 OpenAI provider 下，spawn/followup message 被运行时丢弃（`encrypted_content` 不可消费），“把 message 放入 child 实际输入”物理上不可行。因此：

- 每次 spawn 前必须先把完整信封写入 `.ai/dispatch/inbox-<dispatchId>.md`；
- child 无可见派发内容时按 AGENTS.md 1.1 节从收件箱消费（读 inbox → 校验 → 归档 → ACK）；
- 两次重派仍失败时输出 `DISPATCH_RUNTIME_INJECTION_FAILED`，同时保留收件箱现场转人工，不再重复修改 Prompt。

完整规范见 `.ai/knowledge/file-dispatch-runtime.md`。
