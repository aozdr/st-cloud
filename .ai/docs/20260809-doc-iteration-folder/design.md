# 程序设计文档：Loop 文档输出按迭代建文件夹归档

> task-id: `20260809-doc-iteration-folder`
> 归属 exitCriteria: TECH_DESIGN
> 产出 Agent: 流程规则优化（前端/后端工程师角色合并，本次为规则层改动）

## 1. 背景

当前 Loop 流程所有任务文档以扁平命名 `<task-id>-<type>.md` 存放在 `.ai/docs/` 根目录。随着迭代增多，根目录文件堆积，同一迭代的多份文档（requirement / uispec / design / testcases / codereview / testreport …）散落难以归拢，检索与回顾成本上升。

用户要求：**按迭代在原目录下生成一个文件夹，当前迭代输出的所有文档都放到这个文件夹中**。

## 2. 目标与影响范围

- **目标**：将文档存放从「扁平 `<task-id>-<type>.md`」改为「按迭代建文件夹 `.ai/docs/<task-id>/<type>.md`」，同一迭代全部文档归入同一文件夹。
- **影响范围**：仅 `.ai/` 规则与知识文件，不涉及业务代码、数据库、接口。
- **完成标准**：
  1. 中央规范 `document-management.md` 明确按迭代建文件夹的目录结构与命名规范
  2. 所有引用文档路径模式的规则文件同步更新
  3. Workflow Manager 初始化 State 时即创建迭代文件夹
  4. 历史文档保留原位，新迭代一律使用文件夹结构

## 3. 新目录与命名规范

```
.ai/docs/
  <task-id>/              # 每个迭代一个文件夹，task-id 与 Loop State 的 taskId 一致
    requirement.md        # 需求文档（PM）
    uispec.md             # UI/UX 设计文档（UI Designer）
    impact.md             # 影响分析（Impact Analyzer）
    exp-review.md         # 体验评审（Experience Reviewer）
    design.md             # 程序设计文档（FE + BE，分章节）
    testcases.md          # 测试用例（Tester）
    codereview.md         # Code Review 记录（Reviewer）
    security.md           # 安全审查记录（Security Reviewer）
    testreport.md         # 测试报告（Tester）
```

- **文件夹名** = `task-id`（建议 `YYYYMMDD-<slug>`）
- **文件名** = `<type>.md`（task-id 已体现在文件夹名，文件名不再重复）
- 同一迭代全程向同一文件夹追加文档，不跨迭代混放

## 4. 接口/数据设计

### 4.1 Loop State artifacts ref 路径变更

| artifact | 旧 ref | 新 ref |
|----------|--------|--------|
| prd | `.ai/docs/<task-id>-requirement.md` | `.ai/docs/<task-id>/requirement.md` |
| uiSpec | `.ai/docs/<task-id>-uispec.md` | `.ai/docs/<task-id>/uispec.md` |
| design | `.ai/docs/<task-id>-design.md` | `.ai/docs/<task-id>/design.md` |

编排器 Evaluate 段校验逻辑不变：ref 必须指向真实存在文件，否则对应 exitCriteria 不得标 done。

### 4.2 Workflow Manager 初始化增强

初始化 State 第 6 步新增动作：生成 `task-id` 后，除写入 `.ai/state/<task-id>.yaml`，同时创建迭代文档文件夹 `.ai/docs/<task-id>/`。

## 5. 修改清单

| 文件 | 改动 |
|------|------|
| `.ai/knowledge/document-management.md` | 重写「存放目录」「命名规范」；更新「用户可见性」「与 Loop State 的关系」「小型任务例外」「文档类型表注释」为文件夹路径 |
| `.ai/knowledge/loop-state-model.md` | artifacts prd/uiSpec/design ref 改文件夹路径；门禁依赖描述更新 |
| `AGENTS.md` | 「文档产出与留存」强制规则补充按迭代建子文件夹 |
| `.ai/agents/workflow-manager.md` | 初始化步骤新增创建文件夹；Evaluate 落盘校验与收敛汇总路径更新 |
| `.ai/agents/product-manager.md` | PRD/uiSpec 落盘路径改文件夹 |
| `.ai/agents/frontend-engineer.md` | design 落盘路径改文件夹 |
| `.ai/agents/backend-engineer.md` | design 落盘路径改文件夹 |
| `.ai/knowledge/conventions.md` | 需求/设计/测试用例落盘路径改文件夹 |

## 6. 历史文档迁移策略

- 历史文档（采用 `<task-id>-<type>.md` 扁平命名，如 `favorites-enhancement-requirement.md`、`20260809-pikpak-redesign-*.md`）**保留原位不动**，沿用既有「历史文档保留原名」惯例，避免批量移动破坏已完成 State 文件与知识库的历史引用。
- 新迭代一律使用文件夹结构。
- 若需统一迁移历史迭代文档至文件夹结构，可作为后续独立任务执行（需同步更新对应 State 文件与知识库引用）。

## 7. 风险点

- **历史引用不一致**：`.ai/knowledge/ui-design-system.md`、`loop-dryrun-*.md` 等仍引用旧扁平路径——这些是历史事实记录，保留不改，不影响新迭代。
- **路径校验**：编排器 Evaluate 段仍按 ref 真实存在性校验，文件夹结构不改变校验逻辑，无回归风险。
- **编码规范**：本次仅改规则文件，无业务代码变更，无安全敏感逻辑。

## 8. 验证方式

1. `rg "docs/<task-id>-" .ai/ AGENTS.md` 规则文件应无残留旧扁平模式（历史知识库记录除外）
2. `rg "docs/<task-id>/" .ai/ AGENTS.md` 应覆盖所有规则文件的路径引用
3. 本设计文档自身按新结构落盘于 `.ai/docs/20260809-doc-iteration-folder/design.md`，即为新规则的实证