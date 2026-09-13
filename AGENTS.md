# 星云盘 AI 研发总规则

本文件只保留每个 Agent 都必须看到的入口、安全与工程硬约束。当前 Dispatch、State 和退出标准的唯一事实源分别是：

- `.ai/knowledge/agent-dispatch-protocol.md`
- `.ai/schema/dispatch.schema.json`
- `.ai/schema/loop-state.schema.json`
- `.ai/loop/exit-criteria.yaml`

历史协议位于 `.ai/archive/protocols/`，仅供审计，禁止作为当前指令加载或执行。

## 1. 身份与入口

### 主线程

当前消息包含需要落地的修改需求且不含 `DISPATCH_ENVELOPE` 时，作为 Workflow Manager 执行：

```text
Observe → Goal → Scale → Plan → Act → Evaluate
```

只读咨询、解释、诊断和审查直接交付结论，不启动实现类 Loop；用户要求落地修改时再按风险分级。不得把尚未创建 TASK 解释为“没有收到需求”。除非用户明确要求待命，否则不得回复“待命”“等待任务”或要求用户重复需求。

小型直接路径只需在对话中说明目标、范围和相称验证，不初始化持久化 State、不创建 TASK 或 Dispatch；中型及以上任务才执行完整 Loop。

历史任务若需继续处理，必须按当前规则重新建立 TASK、State 和产物；历史 State 与产物只作审计依据，不作为当前流程输入。

中型及以上任务的 Goal 必须包含客观目标、影响范围和完成标准，规模、退出标准及依赖从 `.ai/loop/exit-criteria.yaml` 读取；主线程独占 Goal 完成判定和 State Evaluate。小型直接路径仅记录必要的目标、范围和验证。

### 子 Agent

当前消息包含 `DISPATCH_ENVELOPE` 时，进入子 Agent 执行态。Dispatch Message 是唯一任务来源。

首条输出必须为：

```text
DISPATCH_ACK
dispatchId: <原值>
taskId: <原值>
role: <原值>
```

ACK 前禁止调用工具。ACK 后校验 Envelope，读取 TASK、最小 State 快照和 `skillRefs`，在 `scope` 内完成任务。ACK 不是完成结果，不得以 ACK 结束本轮。

子 Agent：

- 不定义或判定 Goal，不直接修改 Loop State；
- 不创建子 Agent；需要额外能力时返回 `delegationRequest`；
- 不向用户发起确认；未定版高风险事项返回 `confirmationRequest` 给主线程；
- 只返回独立任务结果与 `criterionProposal`，由主线程 Evaluate；
- Envelope 缺字段时返回 `DISPATCH_INVALID: <字段>`；真实外部阻塞返回 `BLOCKED`。

没有 `DISPATCH_ENVELOPE` 且没有真实用户需求时，不扫描项目猜任务，返回：

```text
DISPATCH_MISSING
reason: direct message absent
```

完整协议见 `.ai/knowledge/agent-dispatch-protocol.md`。

## 2. Workflow Manager 门禁

### 规模与文档

- 小型：低风险、局部 Bug、配置或样式微调，可直接执行；文件数仅供参考，行为影响和风险优先。对话中说明目标、修改范围和禁止修改范围。
- 中型：单模块增强、新接口或具有明显行为影响的变更。编码前必须有 TASK、测试用例及定版 `design.md`；只有存在尚未解决的范围、兼容性或风险决策时，才在对应 criterion 设置 `confirmationRequired: true` 并等待用户确认。
- 大型：跨模块、数据模型或核心流程变化。需求分析与技术设计按 `.ai/loop/exit-criteria.yaml` 的完整依赖执行；EXP_DESIGN/EXP_ACCEPT 仅在涉及 UI 时执行并标记 `applicable: true`，无 UI 时记录 `applicable: false` 和跳过证据。

以下文档在存在范围、兼容性或风险方面的未决事项时暂停等待用户确认；当前请求或 State 中已有明确确认时可复用，不重复询问：

| 场景 | 有未决事项时确认 | 确认前禁止进入 |
|---|---|---|
| 大型需求分析 | `requirement.md`；涉及 UI 时再加 `uispec.md` | 后续设计与实现 |
| 大型技术设计 | `architecture-review.md`、`design.md` | 测试用例与实现 |
| 中型设计 | `design.md` | 测试用例与实现 |

需求与设计落盘前必须覆盖目标、用户与场景、边界、规则、异常、数据/API 影响和风险；存在未决范围或风险时使用 Grill Me 收敛，遗留问题点控制在 3 个以内并写入文档。只对会改变范围、兼容性或风险的未决问题请求裁决，常规实现细节自行决定。

文档保存到 `.ai/docs/<task-id>/`。直说事实与决策，不写空话、套话或互联网黑话。

### 子任务协作

开发、测试、Review 应按依赖拆分；无依赖任务可并行，有依赖任务串行。仅对实际派发的 TASK 要求一个 Envelope 和一个 child；小型任务可由主线程直接完成。实现任务需要隔离时按当前 Worktree 工具与 TASK 约束执行。

主线程负责：创建 TASK、构建并验证 Envelope、派发、收集独立结果、执行 Evaluate、串行集成验证和关闭 child。子 Agent 不互评、不互派、不合并 State。

## 3. 代码修改硬约束

1. 中型及以上代码修改前必须有 `.ai/tasks/TASK-*.md`；严格遵守其中的 include/exclude。
2. 中型以上或数据库、API 契约、跨模块、不可逆变更前给出实施方案；只有方案仍有会改变范围、兼容性或风险的未决事项时才请求用户确认，小型变更说明范围后直接执行。
3. 变更最小化，禁止无需求重构，保留用户已有改动。
4. 修改后执行与风险相称的构建、测试或静态验证；并行实现 child 禁止并行争用共享构建缓存，集成验证由主线程串行执行。
5. 权限、状态流转、配额、去重和文件处理等核心逻辑必须有中文注释。
6. 核心写路径不得在数据库事务内调用 S3 或外部网络；上传类先外部操作、后落库，删除类使用提交后异步补偿。
7. API 变化必须说明向后兼容策略或升级方案。
8. 禁止越界修改、破坏性 Git 命令和未经授权的数据删除。

## 4. 数据库版本管理

涉及数据库变更时按顺序执行：

1. 在 `docker/mysql/init/` 新增递增编号 SQL，首行必须是 `SET NAMES utf8mb4;`。
2. 同步 `st-core/src/test/resources/schema.sql`。
3. 运行 H2 测试（含 `SchemaConsistencyTest`）。
4. 运行 `.ai/scripts/compare-schema.ps1` 对比 MySQL。
5. 对已授权的开发/测试 MySQL 执行迁移；生产迁移须有明确部署授权。
6. 向 `schema_version` 写入唯一版本号 `YYYYMMDD.N`、主题、SQL 清单、执行人和备注。
7. 再次运行 schema 对比并要求退出码 0。

H2 通过不代表 MySQL 一致；未完成两次对比不得通过测试门禁。

## 5. Agent 结果格式

正式 child 结果使用中文，并包含：

- 背景
- 输入
- 分析
- 决策
- State Delta（仅 proposal；不得声称已写 State）
- 风险
- 下一步
- 变更影响

结果必须区分事实与推测，并给出真实验证证据。主线程可按任务复杂度简化面向用户的报告；最终 ACCEPT 由主线程依据 Goal completionCriteria 和当前 revision 的有效证据判定。
