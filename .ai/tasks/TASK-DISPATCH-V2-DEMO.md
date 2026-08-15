# TASK：Dispatch V2 自包含派发演练（只读验证）

## 元信息
- Task ID: `TASK-DISPATCH-V2-DEMO`
- 关联 State: `.ai/state/20260813-code-review-rework.yaml`（仅读取）
- 归属 Agent: backend-engineer（演练角色）
- 创建者: workflow-manager
- 日期: 2026-08-13
- 模式: verify（只读，禁止修改任何业务代码）

## 目标

验证 Dispatch V2 自包含派发协议可执行：
1. 首条消息必须声明：已读取 TASK + State + Scope；我是 backend-engineer，任务类型 backend，开始执行 <objective>。
2. 全程只读取 scope.include 白名单内的文件；不读取 st-web/**、st-desktop/** 等白名单外目录。
3. 不创建/派发任何子 Agent。
4. 返回完整 State Delta（背景/输入/分析/决策/State Delta/风险/下一步/变更影响）。

## 范围（只读）

- include（允许读取）：
  - `.ai/knowledge/agent-dispatch-protocol.md`
  - `.ai/templates/dispatch-template.md`
  - `.ai/state/20260813-code-review-rework.yaml`
  - `.ai/knowledge/role-context.md`
  - `C:/Users/Administrator/.agents/skills/java-spring-boot/SKILL.md`
- exclude（禁止读取/修改）：
  - `st-web/**`、`st-desktop/**`、`st-sync/**`、`st-core/**`
  - `.ai/` 其它目录

## 验收标准

- 回复首句包含角色与任务类型声明。
- **未向用户发起任何确认请求**（未调用 request_user_input / 未提问 / 未返回“请确认是否继续”）；涉及高危操作需要确认时，返回 `confirmationRequest` 给 Workflow Manager。
- 未读取白名单外目录（不得引用 st-web/st-desktop/st-core/st-sync 内容）。
- 未产生任何子 Agent。
- 返回完整 State Delta，且未修改任何文件。

## 验证

- 由 Workflow Manager 用 `list_agents` 校验无后代 Agent。
- 抽查回复中引用的文件均在白名单内。
