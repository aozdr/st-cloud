# TASK-REVIEW-STANDARDS-001（全库 Standards 审查 — reviewer/review）

## 元信息

- Task ID: `TASK-REVIEW-STANDARDS-001`
- 归属 Agent: reviewer（taskType=review）
- 创建者: workflow-manager
- 日期: 2026-08-14
- 模式: 只读审查（禁止修改任何业务代码）

## 目标

对 `E:\code\st-cloud` 全库（约 422 个源码文件 / 11 个模块）做 Standards 轴审查：文档化标准符合度 + Fowler 坏味道 + 优化建议。产出 `.ai/docs/20260814-project-code-review/standards.md` 并返回 State Delta。

## 标准来源（必须逐条对照）

- `.ai/knowledge/conventions.md`（编码规范、文件编码 UTF-8 无 BOM、DDL 幂等、依赖注入约定等）
- `.ai/knowledge/architecture.md`（后端分层架构）
- `.ai/knowledge/frontend.md`（前端规范）
- `.ai/knowledge/testing.md`（测试分层规范）
- `AGENTS.md` 工程约束 7 条（TASK 文件、修改范围、测试验证、迁移方案、API 兼容等）

## Fowler 坏味道基线（逐项扫描，仓库标准优先，均属判断项非硬性违规）

1. **Mysterious Name**：函数/变量/类型名不能揭示用途 → 重命名；无诚实名字说明设计混乱。
2. **Duplicated Code**：同一逻辑形状出现在多处 → 抽取共享实现。
3. **Feature Envy**：方法过多访问他对象数据 → 移到数据所在对象。
4. **Data Clumps**：同组字段/参数反复结伴出现 → 封装为类型。
5. **Primitive Obsession**：用原始类型/字符串表达领域概念 → 建立专属小类型。
6. **Repeated Switches**：对同一类型重复 switch/if 级联 → 用多态或共享 map。
7. **Shotgun Surgery**：一个逻辑变更被迫散落多文件 → 聚拢到一个模块。
8. **Divergent Change**：一个文件因多种无关原因被修改 → 按单一职责拆分。
9. **Speculative Generality**：为不存在需求添加的抽象/参数/钩子 → 删除，回到真实需要。
10. **Message Chains**：长链 a.b().c().d() 导航 → 用首个对象上的方法隐藏。
11. **Middle Man**：类/函数多数只转发 → 删除，直连真实目标。
12. **Refused Bequest**：子类/实现忽略或重写大部分继承 → 改组合。

## 已知问题基线（标注"已知/待整改"，不重复计为新问题）

- `.ai/docs/20260813-project-code-review/codereview.md`：2026-08-13 WIP 两轴 review 已列问题（字段注入、28 号脚本幂等、UploadStatus 魔法数字、令牌桶重复、状态注释漂移等）

## 扫描策略（全库 422 文件，勿全量载入上下文）

1. 用 `rg` 探测坏味道：重复代码模式、大文件/长方法、硬编码魔法数、`@Autowired(required=false)` 字段注入、DDL 无幂等守卫、TODO/FIXME、命名漂移。
2. 精读代表性文件：各模块 Controller/Service/Mapper/Entity、前端页面/组件/状态管理、`docker/mysql/init/*.sql` 与 `st-core/src/test/resources/schema.sql`。
3. 按模块组织输出（st-common / st-core / st-sync / st-search / st-team / st-web / st-desktop / st-share / st-auth / st-admin / st-api / st-preview / docker/mysql/init）。

## 范围

- include（允许）：读取 `st-*/**`、`docker/mysql/init/**`、`.ai/knowledge/conventions.md`、`architecture.md`、`frontend.md`、`testing.md`、`AGENTS.md`、`.ai/docs/20260813-project-code-review/**`、`.ai/dispatch/**`（收件箱）；写入 `.ai/docs/20260814-project-code-review/standards.md`
- exclude（禁止）：修改任何 `st-*` 代码、数据库脚本、`.ai/` 其它文件；创建子 Agent；读取其它审查章节

## 输出（standards.md 结构）

1. 审查概览（模块/文件规模/标准来源清单）
2. 硬性违规（违反文档化标准：引用标准文件+规则+代码位置）
3. 坏味道（判断项：命名+代码位置+修复建议）
4. 通过项（符合标准的结构与做法）
5. 优化建议（可执行、不空泛，按优先级排序）

## 验收标准

- `standards.md` 存在且覆盖上述结构
- 硬性违规逐条引用标准出处与代码位置；坏味道命名+位置；优化建议可执行
- 已知问题标注"已知/待整改"
- 未修改任何 `st-*` 代码；未创建子 Agent

## 验证

- 主线程检查 standards.md 结构与内容质量、会话日志确认未写业务代码
