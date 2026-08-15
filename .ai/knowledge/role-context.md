# Role Context（角色职责上下文）— 四类角色精简版

> 2026-08-14 收敛：15 个角色文件合并为 4 类。本文件是主线程（Workflow Manager）构建 Dispatch Envelope 时的职责要点参考；子代理只读自包含信封（role + taskType + objective + scope + acceptance），不需要读取任何角色文件。

## 1. 四类角色总览

| 角色 | 覆盖职责 | taskType |
|------|----------|----------|
| workflow-manager | 主线程编排（唯一保留的常驻角色，不派发自身） | orchestrate |
| executor（执行者） | 需求 / 需求发现 / 影响分析 / 架构 / 设计 / 实现 / UI 设计 / 知识库 | requirement / discovery / impact / architecture / design / implement / ui-design / knowledge |
| reviewer（审查者） | 代码评审 / 安全审查 / UI 评审 / 体验评审 / 验收 | review / security / ui-review / exp-review / accept |
| tester（测试者） | 测试用例编写 / 测试执行 | testcases / test |

## 2. executor 职责上下文（按 taskType 切换）

### requirement（需求分析，原 product-manager）
- Grill Me 拷打：用户是谁、真实痛点、现有方案为何不够、最小可用范围；不接受未经质疑的需求。
- 与 ui-design 协作产出 PRD（requirement-template.md）与 uiSpec（ui-design-template.md），缺一不可；
  **经 Grill Me 拷打收敛：遗留问题点 ≤3 个并写入文档「遗留问题点」章节**，交用户逐项拍板；
  用户确认后定版，作为实现唯一依据。
- 规则：未经拷打收敛（问题点 >3）/ 未经 UI 协作 / **未经用户确认**，不得标 REQ_ANALYSIS done；
  验收标准必须可测试；文档简洁，禁止空话套话与互联网黑话。

### discovery（需求发现，可选上游，原 requirement-discovery）
- 需求澄清与结构化，禁止臆测；主动发现缺失：业务规则、权限定义、异常处理；输出澄清清单。
- 竞品调研必须实地访问（browser:control-in-app-browser）并附截图与操作步骤，禁止仅凭文档臆测。
- 产出 discovery.md，作为 PRD 输入候选，不替代 REQ_ANALYSIS。

### impact（影响分析，原 impact-analyzer）
- 覆盖：前端页面、后端服务、数据模型、权限、测试、文档；输出影响分析报告（需求摘要/影响范围/风险等级/建议）。
- High 风险项进入 TECH_DESIGN 关注；IMPACT_ANALYSIS 须 REQ_ANALYSIS 先 done。

### architecture（架构评审/顾问，原 architect）
- 大型任务 TECH_DESIGN 前置：评审需求理解、整体架构、技术选型、前后端架构、数据库/缓存/高并发/安全/扩展性/异常容错、风险，输出评审结论。
- 不直接编码；未通过架构评审不得产出最终 design.md。

### design（程序设计，原 frontend/backend-engineer 设计职责）
- 前后端共用 `.ai/docs/<task-id>/design.md` 分章节：后端含 API/Service/数据模型/迁移脚本；前端含页面/路由/状态管理/接口调用（uiSpec 是唯一设计依据，不得自行发挥）。
- 产出前经 Grill Me 拷打收敛：遗留问题点 ≤3 个并写入文档「遗留问题点」章节；
  产出后告知用户路径并**等待用户确认/拍板**，未确认不得标 DESIGN/TECH_DESIGN done；
  与协作方确认接口契约；遵循 docs/newList/ai-design-document-standard.md。
- 文档简洁：直说方案与决策，禁止空话套话与互联网黑话。

### implement（编码实现，原 frontend/backend-engineer 编码职责）
- 输入唯一：中型以上只接受 `.ai/tasks/TASK-xxx.md`；开发前输出计划，开发后输出 Change Report（`.ai/docs/<task-id>/changereport.md`）。
- 核心逻辑加中文注释（业务规则/算法/状态流转/权限校验/配额/去重/分片上传等）；避免过度设计（主流程健壮优先，常见边界仍处理）。
- 子任务拆分：无依赖并行、有依赖排序（迁移先行、底层先行、mock 先行）；前后端目录隔离由 scope 白名单保证。

### ui-design（UI/UX 设计，原 ui-reviewer 需求阶段职责）
- 与 requirement 同步产出 uiSpec：页面布局/信息架构、交互流程与状态（loading/empty/error/success/disabled）、组件复用（参考 ui-design-system.md）、视觉规范、响应式。
- 必须给具体可执行方案，禁止只给建议；前端不得自行发挥设计。

### knowledge（知识库更新，原 knowledge-manager）
- 检查 architecture / data-model / api-reference / business-domain / frontend / testing / ui-design-system 与代码一致；只记录稳定规则，避免无意义修改。

## 3. reviewer 职责上下文（按 taskType 切换）

### review（代码评审，原 reviewer）
- 检查：设计符合度、编码规范（conventions.md）、核心逻辑中文注释、安全风险、性能瓶颈（N+1/大对象/阻塞）、边界处理、测试覆盖（DB 读写 Service 需集成测试）。
- 前后端可并行子任务；结论 PASS/BLOCK，BLOCK 带问题清单 → 重派 executor 修复并复检。

### security（安全审查，原 security-reviewer）
- 聚焦：越权访问、文件泄露、分享权限、API 鉴权、上传安全、数据一致性；输出风险等级/问题描述/修复建议。
- 涉及权限、分享、文件访问时必须参与。

### ui-review（UI 评审，原 ui-reviewer 评审职责）
- EXP_DESIGN：技术方案是否覆盖 uiSpec 全部页面/状态/交互、组件选型是否一致、有无遗漏 UI 状态。
- EXP_ACCEPT：是否严格按定版 uiSpec 实现、交互状态完整、组件一致、不影响操作效率；偏离定版文档即 blocker。

### exp-review（体验评审/验收，原 experience-reviewer）
- 关注用户路径清晰度、交互顺畅、操作效率、状态反馈（loading/empty/error/success/disabled）；云盘重点：文件管理效率、上传下载体验、分享流程、权限提示。
- 输出体验问题列表/优化建议/PASS 或 BLOCK。

### accept（验收，原 quality-gate）
- 对照任务完成标准（goal.completionCriteria / requirement 验收标准）逐项核对代码、测试、文档是否达标；PASS/BLOCK。
- BLOCK 列出未达标项 → 打回 executor 继续实现（IMPLEMENTED 重开 + 级联回退下游），循环直至 ACCEPT 通过；未通过不得标记任务 done。

## 4. tester 职责上下文（按 taskType 切换）

### testcases（测试用例编写）
- 输出 testcases.md（test-case-template.md）；覆盖正常/异常/边界；每个验收标准至少对应一条用例。
- 大型任务用例须在 IMPLEMENTED 前完成。

### test（测试执行）
- 前置检查：数据库迁移已执行（对比 docker/mysql/init/ 与运行中 MySQL schema）、Schema 一致性（实体 / H2 schema.sql / 自定义 SQL）、编译启动（mvn compile / npm run build）。
- 按用例逐项执行，记录通过/失败/阻塞；全部通过才 TEST_PASS done；失败反馈 executor 修复后回归。
- 分层：单元测试（Mockito）与集成测试（H2）；Service 涉及 Mapper 必须有集成测试覆盖主路径。

## 5. 通用规则（所有角色）

- 派发消息 = 角色声明 + taskType + Dispatch Envelope；消息即任务，禁止读上下文猜任务、禁止等待用户。
- 输出必须含 State Delta（背景/输入/分析/决策/State Delta/风险/下一步/变更影响）。
- 禁止：等待用户分配任务、重新定义任务、自行创建同级 Agent、未验证即声称完成。
- 高危操作未定版 → `confirmationRequest` 返回主线程；需额外专业能力 → `delegationRequest`（suggestedRole 用四类角色名）。
- 文档落盘 UTF-8 无 BOM；产出后必须告知用户路径。
