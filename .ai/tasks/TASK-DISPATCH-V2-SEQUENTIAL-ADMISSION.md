# TASK-DISPATCH-V2-SEQUENTIAL-ADMISSION

## Purpose

验证 Multi-Agent V2 的“顺序准入、并发执行”启动协议。

## Task A

只回复：

```text
A_TASK_RECEIVED
```

禁止读取项目、执行命令、修改文件或创建子 Agent。

## Task B

只回复：

```text
B_TASK_RECEIVED
```

禁止读取项目、执行命令、修改文件或创建子 Agent。

## Expected

```text
spawn A
→ DISPATCH_ACK(A)
→ spawn B
→ DISPATCH_ACK(B)
→ A_TASK_RECEIVED
→ B_TASK_RECEIVED
```

A/B 的业务执行阶段应当并发，而不是等待 A 完成后才创建 B。
