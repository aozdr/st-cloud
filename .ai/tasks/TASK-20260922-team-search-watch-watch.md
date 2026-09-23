# TASK-20260922-team-search-watch-watch

模型：原编码子任务由 GPT-5.6 Terra high 完成；当前主线程 GPT-6 按用户最新授权继续集成修复。目标：修复真实团队新建文件夹因内存 node 缺 tenantId 而被关注捕获拒绝的回归，并保证该内存租户在先发布的全文索引事件中也已可信补齐。检查可信认证租户上下文与数据库快照，不能跳过捕获或弱化租户校验。覆盖个人/团队新建、无订阅正常创建、有订阅正确捕获、跨租户拒绝以及索引 Outbox 租户字段。新通知列表有实时节点核权时，限制分页 size 1～100 并验证越界不查库。仅必要改动。

输入：当前 design.md/testcases.md/requirement.md；最小 State .ai/state/20260922-team-search-watch.yaml。

include：st-core/**, st-team/**, st-sync/**, .ai/docs/20260922-team-search-watch/watch-changereport.md, .ai/runtime/results/DISPATCH-20260922-TSW-WATCH-A3.json
exclude：st-common/**, st-search/**, st-web/**, docker/**, .ai/state/**, st-team/src/main/java/com/stcloud/team/service/TeamFileAccessPolicyImpl.java, st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java, st-team/src/test/**/TeamFileAccessPolicyTest*, st-team/src/test/**/FolderPermissionFreshTest*

保留已有实现；禁止修改 State、其他 TASK/文档、Git 提交和业务数据。不得创建子 agent。核心权限/事务逻辑中文注释，不在写事务内调用外网。
数据库SQL43已在本地开发库迁移并登记20260921.1，禁止重复执行或修改已应用SQL。
后端不运行Maven；主线程串行验证。前端可运行build/lint。必须向主线程说明文件稳定可验证，修复后返回真实独立结果，不把未执行检查称为通过。
结果：.ai/runtime/results/DISPATCH-20260922-TSW-WATCH-A3.json；by=/root/terra_watch_a3；revision=tsw-code-r2；criterionProposal仅建议。
历史日志只作故障定位：.ai/docs/20260921-team-search-watch/search-tests-r2.log、backend-runtime.log、testreport.md，不沿用历史通过结论。
