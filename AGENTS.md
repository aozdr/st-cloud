# 星云盘 AI 研发规则

本文件是常驻入口，只放决策边界和工程硬约束。按任务需要读取说明，禁止先扫描全部知识库、历史 State 或技能。

## 1. 入口与执行路径

用户直接提出修改需求时，主线程负责确定目标、范围、风险和完成标准，持续完成实现与验证。只读咨询/诊断/审查直接交付结论，不创建实现 Loop。没有 TASK 是主线程的待办，不是让用户重复需求的理由。

- small：低风险局部修复、配置或样式调整；在对话说明目标、范围与相称验证后直接完成，不创建 TASK/State/Dispatch。只有用户要求持久化审计时才使用 small State。
- medium：边界明确的模块增强或兼容改动；默认主线程执行，编码前创建一份 TASK 和精简 design.md（含验收与测试计划）。验证统一写 verification.md，不另建测试用例、变更报告或知识同步任务。
- large：复杂跨模块、核心写路径、安全/权限、数据库、API 契约或不可逆变更；使用完整计划和独立评审。需求、影响、架构、UI 与测试计划按需合入 requirement.md/design.md，评审记录 codereview.md，验证记录 verification.md。额外文档只为独立决策或用户要求创建。

文件数仅作参考；普通兼容跨目录修改不自动升级。流程分级与 DAG 唯一维护在 `.ai/loop/exit-criteria.yaml`。只在使用持久化 State 时读取 `.ai/knowledge/loop-state-model.md`；需要执行步骤时读 `.ai/knowledge/agent-loop-runbook.md`。不要为每个门禁创建 Agent。

已有当前任务继续使用绑定版本与仍有效证据，不重复初始化。V2 State 按冻结 V2 定义校验，不自动升级；旧协议仅供审计，不加载为当前指令。需要升级时新建 V3 任务，逐项复核可用证据。

## 2. 计划、确认与协作

计划覆盖目标、用户场景、边界、规则、异常、数据/API 影响、风险、验证和完成标准。常规实现细节自行决定；仅对会改变范围、兼容性或风险的实质未决事项请求用户裁决，先完成可供审阅的方案。已有授权可复用，不重复确认。

medium 默认单 Agent 自检；large 的 CODE_REVIEW 必须由独立 reviewer 完成，包含适用的安全、数据与 UI 维度。主线程可运行测试、更新知识库并完成最终验收；如实标注自检。只有可独立交付且能减少等待或隔离专业上下文的工作才派发子任务；没有收益就主线程完成。

测试在实现完成后即可运行，最终验收等待所有必需证据。并行实现必须无文件重叠，不争用共享构建缓存，集成验证由主线程串行执行。按需使用当前 Worktree 工具，不为只读评审创建 worktree。

只有实际派发才读取 `.ai/knowledge/agent-dispatch-protocol.md` 和 `.ai/schema/dispatch.schema.json`，一 TASK/一 Envelope/一 child。子 Agent 收到 DISPATCH_ENVELOPE 后先输出 DISPATCH_ACK（dispatchId/taskId/role 原值），ACK 前不调用工具；ACK 后校验消息，读取 TASK、最小 State 快照和所需技能，并继续执行至返回真实结果。

子 Agent 只在 scope 内工作，不写 State、不定义 Goal、不互派、不向用户确认；额外能力或实质决策返回 delegationRequest/confirmationRequest。只有主线程 Evaluate 并判定 Goal 完成。无 Envelope 且无真实用户需求时返回 DISPATCH_MISSING，不扫描项目猜任务。

## 3. 代码修改硬约束

1. medium/large 编码前有 TASK，严格遵守 include/exclude；变更最小化，保留用户已有改动，不做无需求重构。
2. 修改后完成与风险相称的构建、测试或静态验证。通过后仅因新改动、失败或未解决风险重跑，不因门禁阶段切换重复执行。
3. 权限、状态流转、配额、去重和文件处理等核心逻辑有中文注释。
4. 核心写路径不得在数据库事务内调用 S3/外部网络；上传先外部操作后落库，删除使用提交后异步补偿。
5. API 变化说明向后兼容策略或升级方案。
6. 禁止越界修改、破坏性 Git 命令、未经授权的数据删除；生产部署/迁移需要明确授权。
7. 证据必须真实、匹配当前 revision；代码变化使旧代码证据失效。缺证据、open blocker 或未结束派发不得宣布完成。

## 4. 数据库版本管理

数据库变更按顺序完成，不因流程精简跳过：

1. 在 docker/mysql/init/ 新增递增编号 SQL，首行为 SET NAMES utf8mb4;。
2. 同步 st-core/src/test/resources/schema.sql。
3. 运行 H2 测试（含 SchemaConsistencyTest）。
4. 运行 `.ai/scripts/compare-schema.ps1` 对比 MySQL。
5. 对已授权开发/测试 MySQL 执行迁移；生产迁移须有明确部署授权。
6. schema_version 写入唯一版本 YYYYMMDD.N、主题、SQL 清单、执行人和备注。
7. 再次 schema 对比，退出码必须为 0。

H2 通过不能代替 MySQL 两次对比。

## 5. 产物与交付

文档在 `.ai/docs/<task-id>/`，内容和证据只记录一次，其他位置引用；模板是可裁剪参考，不为填空重复写章节。只有稳定规则变化才更新对应知识条目。

child 的中文报告包含结果、证据、风险/阻塞和 criterionProposal；背景、输入、分析与下一步仅在有信息增量时补充，不强制八段空表。proposal 不得声称已写 State。最终报告说明改了什么、验证结果和实际限制。

本规则采用 OpenAI 官方的按需加载、精简指令、复杂任务才做持续计划及评估驱动多 Agent原则；仓库具体门禁见 `.ai/docs/20261001-loop-efficiency/design.md` 的官方来源与工程决策。
