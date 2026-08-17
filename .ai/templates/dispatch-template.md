# Dispatch Template V9 — Reliable Task Injection / V2 Sequential Admission / V15 Worktree Isolation

> 内部协议。用户永远不需要填写。

## 核心规则

**TASK 必须直接进入子线程创建动作的任务输入。**

```text
TASK
 ↓
Dispatch Builder
 ↓
完整 dispatchMessage
 ↓
创建 child 时把 dispatchMessage 作为实际任务/提示/message 参数
 ↓
DISPATCH_ACK
 ↓
执行
```

落盘 `.ai/tasks/*.md`、`.ai/state/*.yaml` 或主线程自己打印 Dispatch，都不算派发。

## 当前运行时约束

当前 Codex Multi-Agent V2 已验证支持 `fork_turns="none"`。执行型 child 固定使用 `fork_turns="none"`，保证 fresh child 不继承主线程历史。

硬要求是：每个 child 必须独立创建，并在创建动作中实际携带自己的完整 `dispatchMessage`。
## 完整启动消息

每个 TASK 都生成独立 message：

```text
你是 <role>。

这是一个正式执行任务，不是角色注册。

<<<CHILD_DISPATCH_START>>>
DISPATCH_ENVELOPE

dispatchId: <dispatch-id>
taskCode: <短码，如 SEC-01/TST-02/PE-03，全局唯一，用于认领确认>
etaMinutes: <预计执行分钟数，按规模：前端组件10-15/多组件20-30/后端接口15-20/后端+测试25-40/全量测试30-45/评审15-25/迁移10-15>
taskId: <task-id>
taskRef: <task-file>
stateRef: <state-file>
worktreeRoot: <V15 实现任务专属 worktree 绝对路径，如 D:\code\st-cloud\.ai\worktrees\be01；非实现任务填 "-">
mainRoot: <主仓库绝对路径，如 D:\code\st-cloud；协调文件读取与 changereport 写回>
forbidGitMvn: true
role: <executor|reviewer|tester>
taskType: <requirement|discovery|impact|architecture|design|implement|ui-design|knowledge|review|security|ui-review|exp-review|accept|testcases|test>
skillRefs:
- <技能 SKILL.md 绝对路径，按 skill-mapping.md 映射填充，至少 1 项；无适用技能填 "-" 并注明>
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

执行规则：
1. **收到启动消息后第一动作只能是纯文本 `DISPATCH_ACK`。**
2. **ACK 前禁止调用工具、读取项目、读取 TASK/State、搜索、编译或修改任何文件。**
3. ACK 后**先读取 `skillRefs` 指向的每个 SKILL.md 全文**，再读取 TASK / State / Scope 并立即执行。
   - **ACK 只是认领确认，禁止以 ACK 结束本轮 turn**；必须继续完整执行任务并返回 State Delta（仅回 ACK 无产出 = `DISPATCH_ACK_ONLY`，主线程会重派或降级直接执行）。
4. 立即执行，不等待用户。
5. 只执行本 TASK。
4. 不创建子 Agent。
5. 不从父线程历史推导其他任务。
6. 完成后返回 State Delta。
```

### 重要：消息必须真的传入 child

不要：

```text
spawn(child)
```

再期待 child 自己读取 TASK。

必须：

```text
create child
  task/prompt/message = <完整 dispatchMessage>
```

如果当前工具的参数名不是 `message`，使用它实际提供的“任务/提示”输入参数；关键是**完整 dispatchMessage 必须出现在 child 的实际输入中**。

## 必填字段

```text
dispatchId
taskCode
etaMinutes
taskId
taskRef
stateRef
worktreeRoot
mainRoot
forbidGitMvn
role
taskType
skillRefs
objective
exitCriterion
scope.include
scope.exclude
acceptance
validation
forbidSpawn
```

## ACK

第一轮：

```text
DISPATCH_ACK
dispatchId: ...
taskCode: <认领到的短码>
taskId: ...
role: ...
objective: ...
```

没有 ACK：

```text
DISPATCH_FAILED
```

立即检查 child 创建动作是否真的包含完整 message。

## DISPATCH_INVALID 自动恢复

收到：

```text
DISPATCH_INVALID
```

不要停止，不要问用户。

立即：

```text
检查实际 child 输入
→ 重建完整 dispatchMessage
→ 重新创建同一 TASK 的 child
→ 等待 ACK
```

同一 TASK 最多自动重试 2 次；两次仍失败才报告：

```text
DISPATCH_RUNTIME_INJECTION_FAILED
```

## 并行

N 个 TASK = N 个 message = N 个 child：

```text
TASK-FE → message_FE → child_FE
TASK-BE → message_BE → child_BE
```

不得共享 message，不得先创建空 child 再补任务。

## V15 Worktree 隔离（实现任务）

- 并行实现批次中，每个实现 child 在独立 worktree 内工作；worktree 由主线程创建（`git worktree add -b codex/<taskCode> .ai/worktrees/<taskCode> main`）。
- 子 Agent 只写 `worktreeRoot` 内源码；只读 `mainRoot/.ai/` 协调文件；changereport 写回 `mainRoot/.ai/docs/<task-id>/`。
- **forbidGitMvn = true**：子 Agent 禁止执行任何 git / mvn / npm 构建命令；验证统一由主线程在合并后串行执行。
- 主线程负责：提交（`git -C <wt> add -A` + commit）、合并（`--no-ff`）、清理（`git worktree remove` + `git branch -d`，禁止 `--force`）。
- `git worktree add` 失败时，主线程降级 V14 共享目录 + scope 白名单模式，TASK 无需重写。

## File Inbox 兜底（非 OpenAI provider 强制，多文件认领版）

**已知事实（2026-08-14 核验）**：DeepSeek 等非 OpenAI provider 下，V2 的 spawn message 会被运行时丢弃（任务文本进入 `encrypted_content`，子代理看不到），且子代理上下文无自身身份字段、无法“按名读文件”。因此：

1. **每个任务一个独立收件箱文件 `.ai/dispatch/inbox-<dispatchId>.md`**（UTF-8 无 BOM，模板见下），全部文件可在 spawn 前一次性写齐。
2. spawn 的 message 仍传同一完整信封（官方 OpenAI 通道双写冗余；DeepSeek 下即使丢失也无影响）。
3. child 未收到 message 时，按 AGENTS.md 1.1 节列出 `inbox-*.md` 候选并原子认领（Move-Item 到 `archived/` 同名文件）后回复 `DISPATCH_ACK`。
4. ACK 判据 = ACK 字段匹配 + `archived/inbox-<dispatchId>.md` 存在（已被认领）。
5. 隔离保证：原子认领使两个 child 永远不会读取同一个文件；主线程按 dispatchId 归集结果，不依赖 child 名称。

inbox-<dispatchId>.md 模板：

```text
FILE_INBOX_DISPATCH v1
status: pending
dispatchId: <dispatch-id>
taskId: <task-id>
taskRef: <task-file>
stateRef: <state-file>
role: <role>
taskType: <type>
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
<自然语言任务说明>
```
