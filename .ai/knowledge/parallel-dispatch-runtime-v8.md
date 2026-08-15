# Parallel Dispatch Runtime V8 — Sequential Admission / Concurrent Execution

## 目的

解决 Multi-Agent V2 中多个 child 在同一父 turn 并发创建时出现的启动消息路由/注入不稳定：

- Child A 收到 Dispatch，Child B 待命
- Child A `DISPATCH_MISSING`，Child B 正常
- 两个 child 创建成功，但只有一个真正进入任务执行态

## 已验证 Runtime

Codex Multi-Agent V2 提供：

```text
spawn_agent(
  task_name,
  message,
  fork_turns
)
```

执行型 child 使用：

```text
fork_turns = "none"
```

`message` 是 child 的唯一启动任务输入，必须包含完整 Dispatch Envelope。

## V8 核心策略

不要在同一个父 turn 中并发创建多个 child。

### 正确流程

```text
Build all Dispatch
      ↓
Validate all Dispatch
      ↓
spawn_agent(A, message=A, fork_turns=none)
      ↓
DISPATCH_ACK(A)
      ↓
spawn_agent(B, message=B, fork_turns=none)
      ↓
DISPATCH_ACK(B)
      ↓
A + B concurrently execute
```

### 注意

这里没有等待 A 的业务执行完成。

只等待：

```text
DISPATCH_ACK
```

ACK 表示：

> child 已收到启动任务并完成启动协议。

ACK 后 A 继续执行，Workflow Manager 立即创建 B。

B ACK 后，A/B 同时处于执行阶段。

## 禁止模式

```text
spawn(A) + spawn(B) 同一父 turn 并发
```

```text
spawn(A)
spawn(B)
然后让 child 自己从 TASK / State / 项目文件找任务
```

```text
spawn(A)
wait A 完成
spawn(B)
```

```text
spawn(A)
不带 message
后续再注入 message
```

## Dispatch 与 Runtime 的边界

`DISPATCH_ENVELOPE` 是项目协议，用于验证 child 收到的任务是否完整。

它不能替代 Runtime 的实际 message 注入。

真正的注入动作必须是：

```text
spawn_agent.message = 完整 dispatchMessage
```

## ACK 协议

有效 child 第一轮必须：

```text
DISPATCH_ACK
dispatchId: <id>
taskId: <id>
```

ACK 前：

- 不读取项目
- 不读取 TASK/State
- 不搜索
- 不执行命令
- 不修改文件
- 不等待用户

如果 child 报：

```text
DISPATCH_MISSING
```

立即判定对应 spawn 的 message 没有正确进入 child。

只重派对应 TASK。

## 失败处理

同一个 TASK 最多重试 2 次。

如果顺序准入仍无法让 child 收到 message：

```text
DISPATCH_RUNTIME_INJECTION_FAILED
```

此时停止继续修改 Prompt。

## V8.2 多文件认领式收件箱（非 OpenAI provider 强制，2026-08-14 V2）

**2026-08-14 核验**：在 DeepSeek 等非 OpenAI provider 下，spawn message 的任务文本进入 `encrypted_content` 并被丢弃；且子代理上下文无自身身份字段，无法“按名读文件”。因此采用“一任务一文件 + 原子认领”：

```text
预写 inbox-A.md（任务 A 完整信封）与 inbox-B.md（任务 B 完整信封）
   ↓
spawn_agent(A, message=A, fork_turns=none)   # message 双写冗余，丢失无影响
spawn_agent(B, message=B, fork_turns=none)
   ↓
每个 child 列出 inbox-*.md 候选，原子 Move-Item 认领一个到 archived/（同名）
   ↓
各自读取认领到的文件 → 校验 → DISPATCH_ACK（携带各自 dispatchId）
   ↓
A + B 并发执行，主线程按 dispatchId 归集
```

隔离保证：两个 child 永远不会读取同一个文件（认领即移动，先到先得）；文件预写后即可连续 spawn，不再要求“等 ACK 才写下一个文件”。详见 `.ai/knowledge/file-dispatch-runtime.md`（V2）。

## V8.3 认领确认与线程回收（2026-08-14 追加）

- **认领确认**：每个 child 在 `DISPATCH_ACK` 中声明 `claimedFile` + dispatchId/taskId；`taskRef` 指向的 TASK 文件缺失或与信封不匹配时返回 `INBOX_MISMATCH` 不执行。主线程记录 dispatchId→线程映射，与派发计划对照，重复/漏领/错位时按 dispatchId 归集或 interrupt 重派。
- **用完即关**：child 完成、数据收集后主线程立即 `interrupt_agent` 关闭；每次 spawn 前 `list_agents` 确认无残留。禁止滞留到 `pending_init`（占用并发槽位且无法 interrupt 释放）。

## V8.5 顺序准入（2026-08-14 追加，替代连续 spawn）

- **一次只派一个**：`写 inbox-<taskCode>.md → spawn → 等 ACK(taskCode 匹配) → 再写下一个`；ACK 后各 child 并发执行。
- 多信封连续 spawn 会造成子代理串领/错位（先到先得无身份绑定），已废弃；顺序准入 + taskCode 核对彻底消除。
- 其余规则（文件预写能力、原子认领、多文件隔离）保留，仅派发节奏改顺序。

## V8.4 测试串行化（2026-08-14 追加）

- **并发实现 child 禁止并行运行 `mvn test` / `mvn compile`**：Maven 本地仓库全局锁 + `-am` 共享上游 target 会导致并发构建互锁/假失败。
- 实现 child 只产出代码与静态自检（如 rg 复核），**验证统一由主线程或单一测试执行 agent 在实现全部完成后串行执行**。

## 一任务一消息一 child

```text
TASK-A → message-A → child-A
TASK-B → message-B → child-B
```

每个 child 必须有唯一 task_name 和 dispatchId。

不得共享可变 message。

## 成功标准

```text
A ACK
B ACK
A 执行
B 执行
A/B 不互相继承主线程历史
A/B 不创建孙 Agent
A/B 分别返回 State Delta
Workflow Manager 最终合并 State
```
