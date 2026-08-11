# Agent 技能配置规范

> 本文件集中定义各 Agent 可用的技能（Skill）清单与调用约定。技能是 Agent 的能力增强，按需调用，不替代 Agent 职责。

## 总则

- 技能以 `技能名` 形式引用，实际工作时由 Agent 按场景判断是否加载
- 技能清单在各 Agent 定义文件的「技能配置」章节同步声明
- 技能不改变标准开发流程的阶段门禁，仅增强 Agent 在各环节的执行质量
- 需发现新技能时使用 `find-skills`（`npx skills find`）

## 技能清单

### 产品经理（product-manager.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `grill-me` | 对需求进行拷打式深度拷问 | 需求分析阶段（必须先调用） |
| `prd-development` | 构建结构化 PRD | 需求文档输出 |
| `user-story` | 用户故事 + Gherkin 验收标准 | 需求细化 |
| `roadmap-planning` | 战略路线图规划 | 迭代规划 |
| `product-strategy-session` | 产品策略会话 | 产品方向决策 |
| `company-research` | 竞品/公司研究 | 竞品分析 |

### 架构顾问（architect.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `java-spring-boot` | Spring Boot 架构设计 | 复杂架构设计 |
| `mysql` | MySQL 数据库架构与调优 | 数据库设计/调优 |

### 前端工程师（frontend-engineer.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `vercel-react-best-practices` | React/Next.js 性能最佳实践 | 编码/审查 |
| `vercel-composition-patterns` | React 组件组合模式 | 组件设计 |
| `design-guide` | 设计系统规范 | UI 组件开发 |
| `web-design-guidelines` | Web 界面规范审查 | UI 审查 |
| `frontend-design-ui-ux` | 设计语言 + UX/UI 规范 | 新功能 UI 设计 |
| `webapp-testing` | Playwright 前端功能测试 | 前端功能验证 |

### 后端工程师（backend-engineer.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `java-spring-boot` | Spring Boot 开发 | 编码/设计 |
| `mysql` | MySQL 数据库设计与查询调优 | 数据模型/查询 |

### Reviewer（reviewer.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `code-review` | 两轴代码审查（标准符合度 + 需求符合度） | Code Review |

### 测试工程师（tester.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `webapp-testing` | Playwright Web 应用测试 | 测试执行 |

### 需求发现分析师（requirement-discovery.md）

| 技能 | 用途 | 调用时机 |
|------|------|----------|
| `competitive-analysis` | 竞品功能对比分析 | 竞品差距分析 |
| `company-research` | 公司研究简报 | 跨产品研究 |
| `product-strategy-session` | 产品策略会话 | 借鉴契合度评估 |
| `find-skills` | 发现更多可用技能 | 需要扩展能力时 |

## 调用约定

- 技能名在文档中用反引号引用，便于识别
- Agent 在对应工作环节按需加载技能，非每次必须全量调用
- 技能产出作为 Agent 工作的辅助，最终决策仍由 Agent 职责决定
- 集中规范与本仓库各 Agent 文件的「技能配置」章节保持同步
