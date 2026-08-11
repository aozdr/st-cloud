# 文档产出与留存规范

> 本规范定义星云盘研发流程中各类输出文档的产出、存放、命名、用户可见性与长期留存规则。是 AGENTS.md「文档产出与留存」的实施细则，所有中大型任务必须遵守。
> 所有文档的输出格式遵循 `docs/newList/` 下的输出标准，对应模板存放于 `.ai/templates/`。

## 核心原则

- **必产出**：中大型任务必须产出需求文档与程序设计文档；小型任务可直接执行但鼓励补记
- **必落盘**：文档必须写入项目目录 `.ai/docs/`，不得仅停留在对话中或临时文件
- **必可见**：产出后必须在对话中向用户告知文档路径，确保用户能在编辑器中打开查看
- **必留存**：文档作为项目资产长期保留，供后续回顾、复盘、知识库同步，不得删除

## 文档类型与归属

| 文档 | 产出 Agent | 归属 exitCriteria | 输出标准 | 模板 |
|------|-----------|-------------------|---------|------|
| 需求文档（PRD） | Product Manager | REQ_ANALYSIS（大型）/ DESIGN（中型含需求时） | `docs/newList/ai-requirement-document-standard.md` | `.ai/templates/requirement-template.md` |
| UI 设计文档（uiSpec） | UI Designer | REQ_ANALYSIS / EXP_DESIGN | `docs/newList/ai-ui-design-document-standard.md` | `.ai/templates/ui-design-template.md` |
| 需求发现报告 | Requirement Discovery | 可选上游（不进 Loop 强制门禁） | `docs/newList/ai-requirement-discovery-agent-standard.md` | `.ai/templates/discovery-template.md` |
| 架构设计评审 | Architect | TECH_DESIGN（大型任务前置） | `docs/newList/ai-architecture-review-standard.md` | `.ai/templates/architecture-review-template.md` |
| 程序设计文档 | Frontend Engineer + Backend Engineer | TECH_DESIGN（大型）/ DESIGN（中型） | `docs/newList/ai-design-document-standard.md` | `.ai/templates/design-template.md` |
| 测试用例 | Tester | TESTCASES | `docs/newList/ai-test-case-standard.md` | `.ai/templates/test-case-template.md` |
| Code Review 记录 | Reviewer | CODE_REVIEW | `docs/newList/ai-code-review-standard.md` | `.ai/templates/code-review-template.md` |

> 安全审查（SECURITY_REVIEW）记录 `security.md`、影响分析 `impact.md`、体验评审 `exp-review.md`、测试报告 `testreport.md` 暂无独立 newList 标准，沿用现有格式。

## 存放目录（按迭代归档）

所有任务文档**按迭代（任务）归档**：每个迭代在 `.ai/docs/` 下创建一个同名文件夹，该迭代产出的全部文档放入此文件夹：

```
.ai/docs/
  <task-id>/              # 每个迭代一个文件夹，task-id 与 Loop State 的 taskId 一致
    discovery.md          # 需求发现报告（可选上游）
    requirement.md        # 需求文档
    uispec.md             # UI 设计文档
    impact.md             # 影响分析
    exp-review.md         # 体验评审
    architecture-review.md # 架构设计评审（大型任务，先于 design.md）
    design.md             # 程序设计文档
    testcases.md          # 测试用例
    codereview.md         # Code Review 记录
    security.md           # 安全审查记录
    testreport.md         # 测试报告
```

- 该目录是任务文档的唯一存放地，与 `.ai/knowledge/`（知识库，结构化事实源）区分
- 同一迭代全程向**同一文件夹**追加文档，不跨迭代混放；编排器初始化 State 时即创建该文件夹
- 文档随项目版本管理，纳入 git，不放入 `.gitignore`

## 命名规范

```
.ai/docs/<task-id>/<type>.md
```

- `task-id`：文件夹名，与 Loop State 的 taskId 一致（建议 `YYYYMMDD-<slug>`，如 `20260809-share-permission`）
- `type`：文件名，取值 `discovery` / `requirement` / `uispec` / `impact` / `exp-review` / `architecture-review` / `design` / `testcases` / `codereview` / `security` / `testreport`
- 示例（`<task-id>` 为实际任务标识）：
  - `.ai/docs/<task-id>/requirement.md`
  - `.ai/docs/<task-id>/design.md`
  - `.ai/docs/<task-id>/uispec.md`
  - `.ai/docs/<task-id>/architecture-review.md`

> 历史文档（采用 `<task-id>-<type>.md` 扁平命名，如 `favorites-enhancement-requirement.md`）保留原位不动，新迭代一律使用文件夹结构。

## 用户可见性

文档落盘后，产出 Agent 或编排器**必须**在对话中：

1. 明确告知文档的相对路径（如 `.ai/docs/<task-id>/requirement.md`，具体命名见上节）
2. 简述文档核心内容（背景、范围、验收标准 / 架构、接口、数据设计）
3. 邀请用户审阅，等待用户确认或反馈后再进入下一阶段

> 文档对用户不可见 = 未完成产出。不得在用户无法查看文档的情况下推进到下游阶段。

## 长期留存

- 任务收敛 `status=done` 后，文档保留在 `.ai/docs/`，**不移除、不归档到别处**
- 文档是后续回顾、复盘、需求变更溯源、知识库同步的依据
- rework 导致文档修订时，覆盖更新同一文件（保留最新版），同时在 Loop State history 记录修订原因；不保留多版本副本（git 已提供历史版本）

## 与 Loop State 的关系

- `artifacts.prd.ref` 必须指向 `.ai/docs/<task-id>/requirement.md` 的真实路径
- `artifacts.uiSpec.ref` 必须指向 `.ai/docs/<task-id>/uispec.md` 的真实路径
- `artifacts.design.ref` 必须指向 `.ai/docs/<task-id>/design.md` 的真实路径
- `artifacts.archReview.ref`（大型任务）指向 `.ai/docs/<task-id>/architecture-review.md`
- 编排器在 Evaluate 段校验 ref 指向的文件真实存在，否则对应 exitCriteria 不得标 done

## 大型任务设计阶段顺序

大型任务的 TECH_DESIGN 阶段分为两步，均须落盘：

1. **架构设计评审**（`architecture-review.md`）：Architect 主笔，评估整体技术方案、影响范围、性能/安全/扩展性，评审通过后才进入程序设计
2. **程序设计文档**（`design.md`）：前后端工程师基于架构评审结论产出详细设计

> 架构评审是程序设计的前置条件，未通过架构评审不得产出最终 design.md（门禁由编排器在 Evaluate 段强制）。

## 小型任务例外

小型任务（直接执行）不强制产出需求/设计文档，但若改动涉及核心逻辑（权限/配额/文件处理/分享），鼓励补记一份简要设计说明到 `.ai/docs/<task-id>/`，命名同规范。
