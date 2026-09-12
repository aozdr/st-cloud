# Dispatch Message Template V2

> 当前格式以 `.ai/schema/dispatch.schema.json` 为准；运行规则见 `.ai/knowledge/agent-dispatch-protocol.md`。

每个 TASK 单独复制本模板，完成 schema 校验后，把整段内容直接传入 `spawn_agent.message`。

```text
DISPATCH_ENVELOPE
schemaVersion: 2
dispatchId: <本 attempt 唯一；重派必须更换>
taskId: <稳定 TASK id>
idempotencyKey: <同一 TASK 所有 attempt 保持不变>
taskCode: <可选可读标签，不参与身份校验>
role: <executor|reviewer|tester>
taskType: <任务类型>
objective: <唯一目标>
taskRefs:
  - <TASK 文件>
stateRef: <Loop State 文件>
artifactRefs:
  - <输入或预期产物>
skillRefs:
  - <SKILL.md；无额外技能填 "-">
scope:
  include:
    - <允许写入路径>
  exclude:
    - <禁止路径>
acceptance:
  - <客观验收标准>
validation:
  - <验证步骤或命令>
forbidSpawn: true
forbidGitMvn: <true|false>
etaMinutes: <正整数>
output:
  artifactRef: <本 dispatch 独立结果文件>

执行要求：
1. 首条输出只能是：
   DISPATCH_ACK
   dispatchId: <原值>
   taskId: <原值>
   role: <原值>
2. ACK 前禁止调用工具；ACK 后校验 Envelope 并执行。
3. Dispatch Message 是唯一任务来源；只读必要 TASK/State，严格遵守 scope。
4. 禁止创建子 Agent、修改 Loop State或向用户提问。
5. 只写本 dispatch 独立结果；返回事实、证据与 criterionProposal。
6. ACK 不是完成结果，必须在同轮完整执行。
```

主线程调用语义：

```text
spawn_agent(
  task_name = <唯一运行时名称>,
  message = <上面的完整消息>
)
```

ACK 校验只比较：

```text
dispatchId + taskId + role
```

重派规则：保持 `taskId/idempotencyKey`，生成新 `dispatchId` 和新 child；旧 attempt 的迟到结果不得进入 Evaluate。
