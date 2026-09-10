# Change Report — Agent 角色收敛（15 → 4）

## 背景

原配置在 `.ai/agents/` 下定义了 15 个角色文件（共 85.5KB），但运行时所有角色使用同一模型（deepseek-v4-flash）、同一工具集与同一沙箱，角色之间无真实差异化能力，本质是 15 份 prompt 模板。实际派发统计显示只有 backend-engineer（14 次）、frontend-engineer（6 次）、tester（3 次）、reviewer / security-reviewer / architect（各 1 次）被真实使用；project-manager 全项目 0 引用，其余角色从未独立派发，职责实际由主线程消化或作为阶段上下文混在执行中。用户确认按推荐方案收敛。

## 方案

15 个角色 → 4 类，职责通过 Dispatch Envelope 的 `taskType` 切换上下文：

| 新角色 | 覆盖职责 | taskType |
|--------|----------|----------|
| workflow-manager | 主线程编排（唯一保留文件） | orchestrate |
| executor | 需求/需求发现/影响分析/架构/设计/UI设计/编码/知识库 | requirement / discovery / impact / architecture / design / implement / ui-design / knowledge |
| reviewer | 代码/安全/UI/体验评审、质量门禁 | review / security / ui-review / exp-review / quality |
| tester | 用例编写与测试执行 | testcases / test |

职责要点收敛到 `.ai/knowledge/role-context.md`，主线程据此构建自包含信封；子代理只读信封，不再依赖角色文件。

## 修改文件

- 删除 `.ai/agents/` 下 14 个角色文件（保留 workflow-manager.md）
- 新增 `.ai/knowledge/role-context.md`（四类角色职责上下文）
- 同步更新：AGENTS.md（团队成员表/文档类型/并行派发规则）、dispatch-template.md（role/taskType 枚举）、task-template.md（归属 Agent）、loop-state-model.md（artifact owner 与示例）、document-management.md（文档归属表）、skill-mapping.md（阶段↔角色映射）、conventions.md（角色表）、agent-loop-runbook.md、workflow-manager.md（角色示例）、architecture-review-template.md、feature-development.md
- 测试夹具 TASK-FILE-INBOX-VERIFY-A/B/C.md、TASK-DISPATCH-V2-DEMO.md 的白名单引用改为 role-context.md

## 验证

- 活跃协议文档（AGENTS.md / templates / knowledge / agents / workflows）grep 无旧角色引用（仅保留 role-context 的"原角色"注解、历史 dry-run 记录与文件名）
- 所有修改/新增文件 UTF-8 无 BOM
- `.ai/agents/` 仅剩 workflow-manager.md（85.5KB → 28KB，角色定义体量降为约 1/3）
- 历史 TASK/state/docs 中的旧角色名保留作记录，不重写

## 风险与注意

- 历史文档（loop-dryrun-*.md、.ai/docs/**）仍含旧角色名，属历史记录，不视为可派发角色
- 派发新任务时必须使用四类角色名 + taskType；若误用旧角色名，信封校验会发现（role 枚举不匹配）
- 后续可继续精简 28KB 的 workflow-manager.md 与 AGENTS.md 的重复协议段（第二阶段，未纳入本次）
