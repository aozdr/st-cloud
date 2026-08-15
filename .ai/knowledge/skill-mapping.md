# 流程 ↔ 技能映射

> 2026-08-14 更新：由"按需引用"改为**"派发强制携带（skillRefs）"**。主线程构建 Dispatch Envelope 时按下表填充 `skillRefs`（SKILL.md 绝对路径），child 执行前必须读取对应 SKILL.md。**不新建任何项目级 skill 文件**，仅强制加载现有第三方能力型技能。

## taskType → skillRefs 映射（派发时强制填充）

| taskType | skillRefs（绝对路径前缀 `C:/Users/Administrator/.agents/skills/`） |
|----------|-------------------------------------------------------------|
| requirement / discovery | `prd-development/SKILL.md`、`user-story/SKILL.md`；discovery 另加 `competitive-analysis/SKILL.md`、`company-research/SKILL.md` |
| architecture / design / implement（后端） | `java-spring-boot/SKILL.md`、`mysql/SKILL.md` |
| design / implement / ui-design（前端） | `vercel-react-best-practices/SKILL.md`、`vercel-composition-patterns/SKILL.md`、`design-guide/SKILL.md`、`frontend-design-ui-ux/SKILL.md` |
| review / security | `code-review/SKILL.md` |
| exp-review / ui-review | `web-design-guidelines/SKILL.md`、`frontend-design-ui-ux/SKILL.md` |
| testcases / test | `webapp-testing/SKILL.md`、`web-design-guidelines/SKILL.md` |
| accept / knowledge / impact | `-`（无适用第三方技能） |

> 说明：后端 taskType 是否前端由 scope.include 判断（含 st-web → 前端技能；含 st-*/java → 后端技能）。`skillRefs` 为必填字段，缺失即 `DISPATCH_INVALID`。

## 按技术栈自动扩展（子代理执行前自动补充，skillRefs 为最小必读集）

| 任务实际涉及 | 自动补充读取的技能（绝对路径前缀 `C:/Users/Administrator/.agents/skills/`） |
|--------------|--------------------------------------------------------------------------|
| 后端：Java/Spring/Maven/SQL/实体/Mapper/Service | `java-spring-boot/SKILL.md`、`mysql/SKILL.md` |
| 前端：tsx/ts/React/组件/样式 | `design-guide/SKILL.md`、`frontend-design-ui-ux/SKILL.md`、`vercel-react-best-practices/SKILL.md`、`vercel-composition-patterns/SKILL.md` |
| 测试：测试用例/测试执行 | `webapp-testing/SKILL.md`、`web-design-guidelines/SKILL.md` |
| 代码评审/安全审查 | `code-review/SKILL.md` |
| 需求分析 | `grill-me/SKILL.md`、`prd-development/SKILL.md`、`user-story/SKILL.md` |

> **子代理自主发现**：skillRefs 与上表均为参考；child 读取任务后应自行扫描已安装技能（`.agents/skills/`、CODEX_HOME/skills、插件技能），读取各 SKILL.md 的 name/description 判断适配性，加载所有适合当前任务的技能。
