# Parallel Dispatch Runtime V7

## 目的

解决“主线程同时派发两个子任务，但只有一个子线程真正收到任务”的运行时注入问题。

### 核心结论

**并发执行 ≠ 两个任务写入同一个 child。**

必须严格满足：

```text
TASK-A
  ↓
独立 Dispatch Message A
  ↓
spawn(child-A, taskInput=A)

TASK-B
  ↓
独立 Dispatch Message B
  ↓
spawn(child-B, taskInput=B)
```

两个 child 必须拥有独立的 child/thread 标识、独立 Dispatch ID、独立 taskRef、独立 message。

## 禁止模式

### 1. 共享可变 message

错误：

```text
message = build(TASK-A)
spawn(message)
message.taskRef = TASK-B
spawn(message)
```

如果 Runtime/SDK 对参数对象存在引用复用，第二次派发可能覆盖第一次输入。

正确：

```text
messageA = build(TASK-A)
messageB = build(TASK-B)
spawn(messageA)
spawn(messageB)
```

### 2. 先 spawn 空 child，再发送任务

错误：

```text
spawn(child-A)
spawn(child-B)
send(A)
send(B)
```

这会把“创建 child”和“任务注入”拆成两个动作，容易出现路由/时序竞争。

正确：

```text
spawn(child-A, taskInput=messageA)
spawn(child-B, taskInput=messageB)
```

### 3. 用位置识别 child

错误：

```text
第一个 child = FE
第二个 child = BE
```

正确：

```text
dispatchId -> childId
taskId -> dispatchId
```

必须保存映射：

```yaml
dispatchBatch:
  batchId: BATCH-xxx
  items:
    - dispatchId: DISPATCH-A
      taskId: TASK-A
      childId: <runtime-child-id>
      status: dispatched
    - dispatchId: DISPATCH-B
      taskId: TASK-B
      childId: <runtime-child-id>
      status: dispatched
```

### 4. 共享 Loop State 写入

两个 child 不得同时直接修改 `.ai/state/*.yaml`。

child 只返回 State Delta。

Workflow Manager 串行合并：

```text
Delta-A
  ↓
Evaluate
  ↓
State write
  ↓
Delta-B
  ↓
Evaluate
  ↓
State write
```

## 推荐启动顺序

### Step 1：一次性构建全部消息

先生成 A/B 两个完整字符串，不启动任何 child。

### Step 2：逐项验证

每条消息必须包含：

- dispatchId
- taskId
- taskRef
- stateRef
- role
- taskType
- objective
- exitCriterion
- scope.include
- scope.exclude
- acceptance
- validation
- forbidSpawn=true

### Step 3：连续创建 child

在不等待 A 结果的情况下：

```text
spawn A with messageA
spawn B with messageB
```

这里的“连续”指**创建调用不依赖前一个 child 完成**，不是要求底层 Runtime 一定支持真正的同时 HTTP 请求。

### Step 4：立即登记映射

每次 spawn 成功后立即记录：

```text
dispatchId -> childId
```

不得通过“第几个 child”判断归属。

### Step 5：第一轮只验证 ACK

A 必须返回：

```text
DISPATCH_ACK
dispatchId: DISPATCH-A
taskId: TASK-A
```

B 必须返回：

```text
DISPATCH_ACK
dispatchId: DISPATCH-B
taskId: TASK-B
```

如果 A ACK、B 没 ACK：

- A 继续执行；
- B 单独标记 `DISPATCH_FAILED`；
- 只重派 B；
- 不重派 A；
- 不让 B 自己猜任务。

## 重要：不要把 fork_turns 写死

`fork_turns` 是 Runtime 启动能力，不是业务 Dispatch 协议。

因此项目内禁止同时出现：

```text
`fork_turns`
`fork_turns`
```

作为互相矛盾的硬规则。

项目只要求：

```text
fresh/bounded child
+
完整 taskInput
+
独立 childId
```

具体 Runtime 参数由当前 Codex Runtime 的真实能力决定。

## 故障判定

出现以下任一情况：

```text
child 第一条回复没有 DISPATCH_ACK
child 回复“没有收到任务”
child 的 ACK taskId 与预期不一致
两个 child 返回相同 dispatchId
两个 child 被记录为同一个 childId
```

均判定：

```text
DISPATCH_RUNTIME_INJECTION_FAILED
```

不是业务 blocker。

## 修复策略

只重派异常 TASK，最多自动重试 2 次：

```text
B failed
  ↓
检查 B 的实际 spawn input
  ↓
重新构建 messageB
  ↓
创建新的 child-B2
  ↓
等待 ACK
```

禁止：

```text
重新派发整个 batch
重新创建已经 ACK 的 A
让 child 自己读取其他 TASK
```

## 验证场景

必须测试：

```text
TASK-A：只读取 A.md 并输出 A-ACK
TASK-B：只读取 B.md 并输出 B-ACK
```

连续创建两个 child。

成功标准：

```text
A -> DISPATCH_ACK(A)
B -> DISPATCH_ACK(B)
```

不能接受：

```text
A -> ACK
B -> 等待任务
```

也不能接受：

```text
A -> ACK(B)
B -> ACK(A)
```


> V8 修订：当前 Multi-Agent V2 采用“顺序准入、并发执行”，以避免同一父 turn 并发 spawn 的启动消息注入竞态。详见 `parallel-dispatch-runtime-v8.md`。
