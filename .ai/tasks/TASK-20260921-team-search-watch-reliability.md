# TASK：关注提醒 H2 可靠性集成测试

模型仅 GPT-5.6-Luna max。按当前 design/testcases，补充真实 H2、Mapper、Spring 事务参与的可靠性测试。

写入白名单：st-team/src/test/java/com/stcloud/team/service/FileWatchReliabilityIntegrationTest.java；.ai/docs/20260921-team-search-watch/reliability-changereport.md；.ai/runtime/results/DISPATCH-20260921-TSW-RELIABILITY-A1.json。

只读生产源码及当前State；生产服务/schema由另一个Agent维护，不得修改或派生Agent。需要接口调整向主线程报告。

覆盖：事务回滚队列不落库；重叠订阅和重复事件一次投递；取消再订阅旧watchId不投递；通知插入失败完整回滚并重试；个人/跨租户/失权/本人操作；目录删除的父订阅和后代订阅；并发消费者不重复。可使用嵌套测试配置注册实际被测Bean、H2 mapper与必要边界mock，但不能把被测捕获/投递/mapper全部mock后声称验证可靠性。使用现有test schema，缺字段报告主线程。测试数据仅H2隔离。

不运行Maven/共享缓存，由主线程串行执行。每60秒汇报进度。结果包含中文背景/输入/分析/决策/State Delta proposal/风险/下一步/影响。criterion IMPLEMENTED，revision tsw-code-r1，by /root/luna_reliability_0921，不能宣称总体完成。

本次续做结果改为 .ai/runtime/results/DISPATCH-20260921-TSW-RELIABILITY-A2.json，by=/root/luna_reliability_0921b。修复当前watch-tests.log编译/测试问题。
