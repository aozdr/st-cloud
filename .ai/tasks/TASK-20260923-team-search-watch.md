# TASK-20260923-team-search-watch

## 目标

在已实现的团队全文搜索、文件关注与变更提醒基础上，完成当前修订回归、评审、知识库同步和最终验收。2026-09-23 用户要求全部状态通过，后续明确改为剩余工作均由当前模型执行；评审按用户授权单人自检记录，不冒充独立 Agent。

## 范围

- 以 `.ai/docs/20260923-team-search-watch/` 内当前文档为定版输入，覆盖搜索权限/游标、关注与取消、可靠变更通知、前端交互、API、SQL/H2 及部署兼容边界。
- 修正索引 Outbox 发布顺序中认证租户快照可能缺失或冲突的问题；限制通知列表 page/size，避免无界逐条查询。
- 运行受影响后端回归、当前代码构建、前端构建和 lint、MySQL/H2 schema 对比，并记录浏览器窄屏已验收证据及未覆盖的外部环境。
- 复核权限边界、输入校验、事务/Outbox 幂等、跨租户数据和通知安全目标；同步 `.ai/knowledge/` 业务/API/数据模型/架构知识。

## 验收标准

1. 搜索只向当前仍有权的团队成员返回当前可见节点；游标、候选预算及旧索引身份规则有测试证据。
2. 关注生命周期和异步通知可靠、幂等，当前权限失效时不泄漏通知细节；两项本次集成修正有回归测试。
3. 受影响 Maven 测试、`st-api` 聚合构建、前端 build/lint 与 schema 对比均有真实退出结果。
4. 代码/安全复核和体验验收说明结论、证据及环境限制；知识库与实现契约一致。
5. `.ai/state/20260923-team-search-watch.yaml` 只由主线程维护；不伪造 dispatch/独立评审身份，评审证据如属主线程自检须明确标注。

## 写入范围

- `.ai/docs/20260923-team-search-watch/**`
- `.ai/tasks/TASK-20260923-team-search-watch.md`
- `.ai/state/20260923-team-search-watch.yaml`
- `.ai/knowledge/{api-reference.md,architecture.md,business-domain.md,data-model.md}`
- `.ai/scripts/loopctl.ps1`、`.ai/schema/loop-state.schema.json`、`.ai/tests/loop-v2/unit/state-engine.tests.ps1`、`.ai/knowledge/agent-dispatch-protocol.md`（补齐主线程直接执行任务的真实 Evaluate 路径）
- `.ai/loop/exit-criteria.yaml`（仅记录用户明确授权的本 TASK 单人执行例外，默认角色分离仍生效）
- 已授权功能代码/回归测试：`st-core`、`st-team`、`st-search`、`st-sync`、`st-web` 及 H2 schema。

## 排除范围

- 生产发布/迁移、实际 ES 全量重建、对外发送通知或消息、业务数据清理。
- 无关功能、历史归档协议、与本任务无关的重构、Git 提交或合并。

## 当前执行约束

产品实现、集成、代码与安全自检、体验及测试验收均由当前主线程完成。此前执行记录仅作背景，不替代当前修订验证。用户原话、仅本 TASK 适用的 `singleAgentAuthorization` 及主线程实际身份保存在当前 State 和审阅资料索引中；State Evaluate 与 Goal 完成判定仍由主线程执行，不伪造 dispatch 或独立结果。
