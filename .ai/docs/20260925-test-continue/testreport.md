# 本轮测试报告

定向 ThumbnailRendererTest：8/8，零失败、错误、跳过，证据 thumbnail-context-final.log 和 Surefire 报告。定向 ShareServiceImplNoTransactionIntegrationTest：5/5，五流及 URL+四流竞争均只授予一次，源流/签名失败与额度抢尽无多计数。桌面 npm test：既有 19 项、同步专项 17 项全部通过；npx tsc --noEmit 和 npm run build:main 退出码 0。

全量 mvn -q test -DskipITs：10 模块 495 项，失败、错误、跳过均为 0；原始日志 maven-full.log，模块计数 java-counts.json。

游标回归先证实数字大游标在原代码中被接受，修复后 5 项通过。首次 Spring 测试因测试夹具重复注册 S3 Bean 失败，修正夹具后复测通过；未将第一次失败隐藏为通过。

分享定向测试首轮因旧夹具固定分享码在两个非事务用例间重复而失败，改为唯一分享码后 2/2 通过。

136 条的逐项合并状态见 results.md：PASS 11、FAIL 0、PARTIAL 33、NOT RUN 92。P0 数据库迁移前后对比的首轮证据继续沿用，不重复执行。
