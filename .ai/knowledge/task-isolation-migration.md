# Task Isolation Migration V6

## 为什么必须改

旧配置同时存在：

```text
workflow-manager:
  优先使用 fork_turns=1
  禁止 all

dispatch-template:
  禁止 none
  默认 all
```

这两个规则互相冲突。

更关键的是，`fork_turns=all` 会让执行 Agent 继续看到主线程历史。

因此：

```text
Role + all parent context + TASK
```

并不是真正的 Task Isolation。

## 新规则

执行型子 Agent：

```text
Multi-Agent V2
fresh/bounded child
`fork_turns`
```

消息本身必须是完整 Dispatch。

## 不要做的事情

不要试图用：

```text
忽略父上下文
请只看 TASK
不要看前面的消息
```

来替代 runtime isolation。

这些只是提示词约束，模型仍然可能看到父上下文。

## 验证标准

第一次测试必须故意构造：

主线程：

```text
用户说：
任务 A 是数据库审查。
另外还有一个安全审查任务。
```

派发给 backend：

```text
唯一任务：
只修改 st-core/upload/**
```

子 Agent 必须：

- 不提安全审查；
- 不处理数据库之外的任务；
- 不读取其他 TASK；
- 只执行 Dispatch 中的 objective；
- 返回 State Delta。

如果子 Agent 仍然能引用父线程任务，说明 runtime 仍未隔离。

## 当前 Runtime 验证结果

当前 Codex Runtime 已确认使用 Multi-Agent V2，并实际支持：

```text
spawn_agent(
  task_name: string,
  message: string,
  fork_turns: "none" | "all" | positive integer
)
```

本项目执行型 child 固定使用：

```text
fork_turns = "none"
```

但经过实际并发测试发现：同一父 turn 中并发创建两个 child 时，存在“一个 child 收到 message、另一个 child 待命/DISPATCH_MISSING”的现象。因此项目采用：

```text
spawn(A, message=A, fork_turns=none)
→ ACK(A)
→ spawn(B, message=B, fork_turns=none)
→ ACK(B)
→ A/B 并发执行
```

这不是“串行执行任务”，而是“串行完成启动准入、随后并发执行”。

## Runtime 不支持 V2 时

不要继续修改 Prompt。

应先升级/启用 Codex Multi-Agent V2，或者使用独立 Codex session/runner。

否则只能做到：

```text
task discipline
```

无法做到：

```text
context isolation
```
