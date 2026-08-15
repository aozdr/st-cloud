# Agent 技能配置规范

> 本文件集中定义四类角色可用的技能（Skill）清单。2026-08-14 起：**派发强制携带**——主线程按 `.ai/knowledge/skill-mapping.md` 将 `skillRefs`（SKILL.md 绝对路径）写入 Dispatch Envelope，child 执行前必须读取对应 SKILL.md。技能不改变阶段门禁，仅增强执行质量。

## 总则

- 技能以 `skillRefs`（绝对路径）形式随派发信封强制携带
- 技能清单由 `skill-mapping.md` 统一维护，本文件为四类角色视角的同步说明
- 技能不改变标准开发流程的阶段门禁，仅增强 Agent 在各环节的执行质量
- 需发现新技能时使用 `find-skills`（`npx skills find`）

## 技能清单

### executor（执行者）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `prd-development` | 构建结构化 PRD | 需求文档输出 |
| `user-story` | 用户故事 + Gherkin 验收标准 | 需求细化 |
| `java-spring-boot` | Spring Boot 架构设计 | 复杂架构设计 |
| `mysql` | MySQL 数据库架构与调优 | 数据库设计/调优 |
| `vercel-react-best-practices` | React/Next.js 性能最佳实践 | 编码/审查 |
| `vercel-composition-patterns` | React 组件组合模式 | 组件设计 |
| `design-guide` | 设计系统规范 | UI 组件开发 |
| `frontend-design-ui-ux` | 设计语言 + UX/UI 规范 | 新功能 UI 设计 |
| `competitive-analysis` / `company-research` | 竞品分析 | 需求发现 |

### reviewer（审查者）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `code-review` | 两轴代码审查（标准符合度 + 需求符合度） | Code Review / 安全审查 |
| `web-design-guidelines` | Web 界面规范审查 | UI 审查 |
| `frontend-design-ui-ux` | 设计语言 + UX/UI 规范 | 新功能 UI 设计 |

### tester（测试者）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `webapp-testing` | Playwright Web 应用测试 | 测试执行 |
| `web-design-guidelines` | Web 界面规范审查 | UI 验收 |

## 调用约定

- 技能以 `skillRefs` 随派发信封强制携带（绝对路径），child 执行前必须读取对应 SKILL.md
- 完整映射见 `.ai/knowledge/skill-mapping.md`；本文件与映射保持同步
- 技能产出作为 Agent 工作的辅助，最终决策仍由 Agent 职责决定
