> 已撤销：用户后续明确需求与程序设计由 GPT-6 编写。本 TASK 未作为文档验收依据，仅保留派发审计；当前文档已由主线程完成。

# TASK：团队全文搜索、文件关注与变更提醒需求分析

- Task ID: TASK-20260916-team-search-watch-requirement
- State: .ai/state/20260916-team-search-watch.yaml
- 执行模型：GPT-5.6-Luna，reasoning effort=max。用户明确禁止 GPT-6 执行业务任务。
- 角色：executor；taskType=requirement

## 目标与输入
用户要求先编写“团队全文搜索”和“文件关注与变更提醒”的需求文档和程序设计文档，之后实施。本 TASK 仅负责第一阶段需求与交互文档，不编写设计或代码。结合真实代码、当前知识库与 AGENTS.md 制定可直接进入设计的完整需求。当前全局搜索按 ownerId 过滤，已有站内通知与团队活动基础；这些是线索，须核实。

## 范围
写入白名单：.ai/docs/20260916-team-search-watch/requirement.md、uispec.md；.ai/runtime/results/DISPATCH-20260916-TSW-REQ-A2.json。
允许只读查看项目业务源码、测试、现行知识库、技能。禁止修改业务源码、数据库、其他文档、Loop State、TASK；不得创建或指挥子 Agent。

## 验收标准
- 中文需求覆盖目标、用户场景、边界、业务规则、异常、数据/API影响、风险、可逐项测试的验收标准。
- 团队全文搜索明确搜索范围、权限收敛、租户隔离、结果摘要及数量不泄漏、分页、权限变化与索引延迟、个人搜索兼容。
- 文件/文件夹关注明确个人/团队适用范围、关注与收藏区别、事件类型、子树/移动/删除语义、通知去重、自己操作、失权、已读跳转与订阅取消。
- 首版以现有站内通知复用为原则，不自行扩展邮件、外部推送或独立通知平台；常规实现选择自行决定并写明理由。
- uispec 贴合现有 UI，覆盖入口、加载/空/失败/无权状态及移动端行为，不做无需求视觉重构。
- 如有必须由用户裁决的范围或兼容性风险，返回最多三个 confirmationRequest；无此事项明确写无未决问题。
- 使用当前协议独立结果 JSON 返回 REQ_ANALYSIS proposal，validatedRevision=tsw-design-r1；不宣称 State 已更新或整体完成。

## 验证
核对相关代码证据；需求验收编号完整；无代码改动；结果含背景、输入、分析、决策、State Delta proposal、风险、下一步、变更影响。

