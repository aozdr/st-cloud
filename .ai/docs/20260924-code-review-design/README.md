# Code Review 需求与程序设计

日期：2026-09-24；修订：design-v1；代码基线：912996e1。

设计阶段由主线程独立撰写和自检。随后用户要求先按文档编码、暂不编写新测试用例，CR-01 至 CR-07 的代码修改已进入工作区；现有测试及构建按影响范围执行。2026-09-24 已对本机开发库 `127.0.0.1:3306/stcloud` 执行 `44_team_role_bigint.sql`、`45_user_security_version.sql`，登记 `schema_version=20260924.1`，再次运行 `compare-schema.ps1` 退出码为 0。其他环境仍需按部署流程迁移。

优先阅读 [需求总册](requirement.md) 和 [程序设计总册](design.md)。补充材料：[影响分析](impact.md)、[架构设计自检](architecture-review.md)、[UI 规格汇总](uispec.md)、[文档核对记录](document-check.md)。

| 任务 | 需求 | 程序设计 | UI |
|---|---|---|---|
| CR-01 令牌撤权 | [需求](CR-01/requirement.md) | [设计](CR-01/design.md) | 复用重新登录 |
| CR-02 团队角色 | [需求](CR-02/requirement.md) | [设计](CR-02/design.md) | [规格](CR-02/uispec.md) |
| CR-03 复制权限 | [需求](CR-03/requirement.md) | [设计](CR-03/design.md) | 无新增 |
| CR-04 同步移动 | [需求](CR-04/requirement.md) | [设计](CR-04/design.md) | 复用同步状态 |
| CR-05 分享限额 | [需求](CR-05/requirement.md) | [设计](CR-05/design.md) | 计数语义一致 |
| CR-06 搜索游标 | [需求](CR-06/requirement.md) | [设计](CR-06/design.md) | 复用重新搜索 |
| CR-07 缩略图 | [需求](CR-07/requirement.md) | [设计](CR-07/design.md) | [规格](CR-07/uispec.md) |

以上文档记录需求和设计决策。代码验证情况以当前工作区的构建与测试结果为准，设计文档本身不构成上线证明。

2026-09-24 根据用户后续指令补齐 [测试用例总册](../20260924-code-review-testcases/testcases.md)，覆盖七项整改、数据库/字符串 ID 和各轮 Review 回归。该册所有用例均标为 NOT RUN，本轮仅编写文档，不执行测试任务。
