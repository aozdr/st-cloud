# TASK-20260922-team-search-watch-search

模型：GPT-5.6 Terra high。目标：保留7个新增团队搜索边界测试，修复TeamSearchServiceTest约448/452/455/468/469的参数编译错误，严格search十三参数顺序。检查过期/跨用户游标、首批100无权后有权、连续分页、预算与参数和缺密钥用例，不能弱化断言或修改接口掩盖问题。

输入：当前 design.md/testcases.md/requirement.md；最小 State .ai/state/20260922-team-search-watch.yaml。

include：st-search/**, st-common/src/main/java/com/stcloud/common/access/TeamFileAccessPolicy.java, st-team/src/main/java/com/stcloud/team/service/TeamFileAccessPolicyImpl.java, st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java, st-team/src/test/**/TeamFileAccessPolicyTest*, st-team/src/test/**/FolderPermissionFreshTest*, .ai/runtime/results/DISPATCH-20260922-TSW-SEARCH-A3.json
[planned-output] .ai/docs/20260922-team-search-watch/search-changereport.md
exclude：st-web/**, docker/**, .ai/state/**

保留已有实现；禁止修改State、TASK、其他文档、Git提交和业务数据。不得创建子Agent。核心权限/事务逻辑中文注释，不在写事务内调用外网。
数据库SQL43已在本地开发库迁移并登记20260921.1，禁止重复执行或修改已应用SQL。
后端不运行Maven；主线程串行验证。前端可运行build/lint。必须向主线程说明文件稳定可验证，修复后返回真实独立结果，不把未执行检查称为通过。
结果：.ai/runtime/results/DISPATCH-20260922-TSW-SEARCH-A3.json；by=/root/terra_search_a3；revision=tsw-code-r2；criterionProposal仅建议。
历史日志只作故障定位：.ai/docs/20260921-team-search-watch/search-tests-r2.log、backend-runtime.log、testreport.md，不沿用历史通过结论。
