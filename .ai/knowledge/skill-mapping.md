# 流程 ↔ 技能映射

> 技能按任务需要选择。主线程构建 Dispatch Envelope 时只填入当前任务真正需要的 `skillRefs`；child 只读取被选择的技能。**不新建任何项目级 skill 文件**，也不扫描全量技能。

## taskType → skillRefs 映射（按需选择）

| taskType | 可选技能（标识由运行时注册表解析） |
|----------|-------------------------------------------------------------|
| requirement / discovery | 需求文档需要结构化 PRD 时用 `prd-development/SKILL.md`；用户故事或 Gherkin 明确需要时再用 `user-story/SKILL.md`；需求存在未决范围时用 `grilling/SKILL.md`；竞品/公司研究仅在用户要求或目标明确依赖时加载 |
| architecture / design / implement（后端） | Java/Spring 变更用 `java-spring-boot/SKILL.md`；数据库结构、迁移、查询或索引变更用 `mysql/SKILL.md`；两者按实际涉及内容选择 |
| design / implement / ui-design（前端） | React 性能问题用 `vercel-react-best-practices/SKILL.md`；组件组合问题用 `vercel-composition-patterns/SKILL.md`；已有设计系统/样式令牌变更用 `design-guide/SKILL.md`，新 UI/UX 设计或完整 UI 审查用 `frontend-design-ui-ux/SKILL.md`，两者默认择一；设计任务有未决范围时再用 `grilling/SKILL.md` |
| review / security | 代码审查用 `code-review/SKILL.md`；安全审查仅在安全敏感变更时加载 |
| exp-review / ui-review | 仅涉及 Web 平台规范时用 `web-design-guidelines/SKILL.md`；需要完整 UI/UX 评审时用 `frontend-design-ui-ux/SKILL.md`，默认择一 |
| testcases / test | 仅浏览器测试用 `webapp-testing/SKILL.md`；仅 UI 验收时用 `web-design-guidelines/SKILL.md` |
| accept / knowledge / impact | `-`（无适用第三方技能） |

> 说明：前后端由 TASK 实际文件和验收范围判断：`st-web`、前端源码目录或 `.tsx/.jsx/.css` 文件归前端；`src/main/java`、Java 文件或后端模块归后端；同一 TASK 同时涉及两端时只加载各自需要的技能。`skillRefs` 字段按 schema 提供；无适用技能时填入 `["-"]`，不因缺少第三方技能而阻塞普通任务。

## 选择细则

- `scope.include` 含 `st-web`、前端源码目录或 `.tsx/.jsx/.css` 时按前端映射选择；含 `src/main/java`、`.java` 或后端模块目录时按后端映射选择，未涉及的技术栈不加载技能。
- 同一任务优先选择一个能覆盖验收标准的最小技能集合；不要因为关键词相似而叠加技能。
- `grilling`、`prd-development` 和 `user-story` 只在对应文档或未决事项确实存在时加载。

> **Grill Me 引擎说明**：`grill-me` 只是入口壳（`disable-model-invocation: true`，正文仅一句转发），实际执行拷打的是 `grilling`。仅在任务存在未决范围、规则或风险时将 `grilling/SKILL.md` 写入 skillRefs，不要填 `grill-me/SKILL.md`。
> requirement 与 design 任务仍需覆盖目标、边界和风险；只有存在未决事项时才加载 Grill，并将遗留问题点收敛到 ≤3 个，见 AGENTS.md 与 `.ai/loop/exit-criteria.yaml` 的 `grill: true` 标记。

> **标识与发现**：skillRefs 使用运行时技能注册表标识。child 不扫描全量技能；若被选择的技能不可用，报告缺失并在不依赖该能力时继续。

> **重叠技能处理**：同一任务默认只选一个 UI 主技能和一个测试主技能；只有验收标准明确覆盖两个独立维度时才组合，避免重复或相互矛盾的指导。
