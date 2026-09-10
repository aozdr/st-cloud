# 设计：AI 开发流程优化

## 总体方案
增量补齐，保留 Agent Loop V4 全部既有结构。核心是引入「Task 文件」作为编码唯一输入，并以 ADR / Change Report / 工程约束 / 技能映射补齐知识沉淀与门禁表达。

## 修改范围与落盘清单
- 新增：`.ai/tasks/`（README）、`.ai/decisions/ADR/`（README）、`.ai/templates/task-template.md`、`.ai/templates/adr-template.md`、`.ai/knowledge/skill-mapping.md`
- 修改：`AGENTS.md`（工程约束 7 条 + 文档类型 + 知识库检查项）、`.ai/agents/workflow-manager.md`（Task 规划 + TASK 落盘校验）、`.ai/agents/frontend-engineer.md` / `backend-engineer.md`（Task 驱动 + 固定执行流程 + Change Report）、`.ai/knowledge/document-management.md`（Task/Change Report/ADR 类型与存放）、`.ai/knowledge/loop-state-model.md`（task/changereport artifacts + 门禁）、`.ai/knowledge/agent-output-standard.md`（Change Report 行）、`.ai/scripts/verify-loop.ps1`（第 6 项校验）

## 数据/接口设计
- Loop State artifacts 新增：`task`（ref 指向 .ai/tasks/TASK-xxx.md）、`changereport`（ref 指向 changereport.md）
- 文档类型新增：task / changereport / ADR
- ADR 命名 `ADR-xxx-<slug>.md`，固定存 `.ai/decisions/ADR/`

## 风险与回退
- 风险：Task 文件若与 design 脱节会导致范围漂移 -> 由 WM 依 design.md+testcases.md 生成并落盘，随 State 校验
- 风险：AGENTS.md 改错导致流程断链 -> verify-loop.ps1 静态校验兜底
- 回退：全部为增量文件与文本追加，git 可回滚