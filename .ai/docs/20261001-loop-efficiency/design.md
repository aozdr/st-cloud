# Loop 效率优化设计

## 目标、用户与范围

降低开发者在常规修改中承担的编排、重复文档、确认和串行等待成本。仅调整流程工具及入口，不改变业务 API、数据模型或数据库迁移要求。

## 已核对的官方依据

- [Rethinking skills and prompts](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)：按需读取、渐进加载、避免过度规定步骤和重复测试；先定义完成边界。
- [Evaluation best practices](https://developers.openai.com/api/docs/guides/evaluation-best-practices)：多 Agent 增加交接复杂度，应由评估结果驱动采用。
- [Using PLANS.md](https://developers.openai.com/cookbook/articles/codex_exec_plans)：复杂功能和重大重构使用持续更新的执行计划。采用按复杂度触发的原则，不照搬完整模板。

以下门禁数量、风险字段和文件名是本仓库决策，并非 OpenAI 强制规定。

## 决策与步骤

1. definition v3：small 默认无持久化流程，显式要求审计时保留 4 项；medium 使用 DESIGN、IMPLEMENTED、VERIFIED、ACCEPT；large 使用 REQ_ANALYSIS、TECH_DESIGN、IMPLEMENTED、CODE_REVIEW、VERIFIED、ACCEPT。
2. medium 默认单 Agent：design.md 包含需求、影响、测试计划；verification.md 合并变更总结、自检、测试、体验与知识同步。large 增加 requirement.md 和独立 codereview.md。UI 方案默认在设计中，不为无 UI 任务创建 skip/child。
3. large CODE_REVIEW 包含安全维度并由独立 reviewer 执行。VERIFIED 只依赖 IMPLEMENTED，可先运行；ACCEPT 汇合测试与评审。主线程执行测试和 Goal 验收，无需另派 tester/accept child。
4. State 保留 schemaVersion=2，definitionVersion 扩展为 2/3；v3 必须记录 UI、安全、数据库、API 契约和不可逆风险。安全/数据库/API 契约/不可逆影响必须选 large；普通跨目录但行为和兼容性稳定的改动按风险判断。
5. 冻结 v2 定义供旧 State 校验；默认 init v3，读取旧 State 自动选择冻结定义，不重写历史证据。旧迁移工具仍生成 v2。
6. 只有范围、兼容性或风险的实质未决决策才询问用户；自检不声称独立评审。
7. DAG 只维护在 exit-criteria.yaml；静态验证默认不遍历历史 State/任务，显式全量审计保留。

## 异常、兼容与风险

缺证据、错误 revision、未确认决策、scope 越界、未结束派发和 blocker 继续阻止完成。代码变化仍使所有当前代码证据失效；不实现按文件精准失效，避免误复用。v2 严格职责分离及单人授权不变。v3 large 独立评审不可由单人授权绕过。

旧 State 不静默升级；续作可用原定义与当前有效证据，也可新建 v3 任务逐项核对证据。本任务的未完成 V2 初始化快照保留在本目录 state-v2-initial.json；重新创建 V3 State，并逐项 Evaluate 真实结果，不继承原门禁通过状态。

## 验证与完成标准

用例见 testcases.md。满足 TASK 四条验收、受影响测试通过、静态检查无新增失败。以门禁数、必需文档、默认派发数衡量结构开销；没有端到端时间/Token 实测，不宣称性能百分比。无需要用户裁决的未决事项，直接实施。
