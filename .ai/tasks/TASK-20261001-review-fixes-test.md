# TASK-20261001-review-fixes-test

目标：独立核对当前修订 review-fixes-code-r4 的 T01–T12 回归证据并重跑认证/类型检查。源代码只读；写入白名单 .ai/docs/20261001-review-fixes/testreport.md、.ai/runtime/results/DISPATCH-review-fixes-test-01.json。禁止修改产品、测试、State、数据库、Git index、历史证据和部署；禁止子派发，禁止运行 Maven/争用共享构建缓存。

输入：定版 design.md、testcases.md、changereport.md、source-manifest-r4.json、core-changes.md、auth-changes.md、r3/codereview.md、r3/security.md 和四套当前 surefire XML。核对每个输入来源和当前源码 SHA256。重新执行 Web 跨页/启动回归和桌面认证回归，以及两端 tsc。后端由主线程集成运行，独立检查其本轮 XML 时间、用例名、计数、无失败/跳过及相关源码 SHA256；清楚说明此项是独立核验证据，不声称自己执行 Maven。没有本轮 MySQL 专库环境凭据，不用旧 MySQL 报告冒充本轮。

完成：中文背景、输入、分析、决策、State Delta proposal、风险、下一步、变更影响；测试按 T01–T12 逐项说明，真实失败须 fail/blocked；结果 proposal id=TEST_PASS，by canonical child identity，evidenceRef=.ai/runtime/results/DISPATCH-review-fixes-test-01.json，validatedRevision=review-fixes-code-r4。不得判定整个 Goal 完成。



