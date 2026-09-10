# 需求：AI 开发流程优化（对齐 AI-Agent-Development-Workflow-Optimization.md）

## 背景
目标文档要求将「AI 辅助编码」升级为「AI 软件工程流水线」：Task 驱动开发、开发者固定执行流程、Review 闭环、ADR 知识沉淀、流程型技能。当前已是 Agent Loop V4，多数设想已落地，本次仅补齐 5 项真实差距。

## 用户故事
作为开发者，我希望中型以上任务通过 TASK 文件明确「目标/修改范围/禁止修改范围/验收标准」，避免越界修改。
作为架构师，我希望重要决策记录为 ADR，沉淀「为何这样设计/放弃方案/后续限制」。
作为流程维护者，我希望 AGENTS.md 显式声明 7 条工程约束，并给出流程↔技能映射。

## 功能范围
- 新建 .ai/tasks/ 与 task-template.md，Workflow Manager 负责 Task 规划
- 工程师规则：Task 驱动 + 固定执行流程 + Change Report（.ai/docs/<task-id>/changereport.md）
- 新建 .ai/decisions/ADR/ 与 adr-template.md
- AGENTS.md 新增「代码修改强制约束（7 条）」与文档类型补充
- 新增 .ai/knowledge/skill-mapping.md（不新建 skill 文件）
- loop-state-model 增 task/changereport artifacts；verify-loop.ps1 扩展第 6 项校验

## 验收标准
- 存在 .ai/tasks/、.ai/decisions/ADR/、task-template.md、adr-template.md、skill-mapping.md
- AGENTS.md 含 7 条工程约束；WM/前后端工程师文档含 Task 驱动规则
- verify-loop.ps1 全部 PASS（退出码 0）
- 不触碰 st-web / st-backend 业务代码

## 边界条件
- 小型任务不强制 TASK 落盘，但对话中给出范围摘要
- 不删除任何既有 AGENTS/Agent/能力型技能