# 继续执行 Code Review 整改用例

目标：扩展 P0 缩略图资源与格式用例的隔离执行证据；发现实际失败时保留现场并修复。
规模：medium；用户要求主线程独立完成。
授权原话：“不要开子线程，你自己完成”；本轮请求：“继续执行”。评审及测试结果标注主线程自检，不声称独立评审。
include：st-core/src/test/java/com/stcloud/core/service/ThumbnailRendererTest.java、st-desktop/src/sync/sync-cursor.test.cjs、st-desktop/src/sync-engine.ts、st-desktop/package.json、st-share/src/test/java/com/stcloud/share/ShareServiceImplNoTransactionIntegrationTest.java、.ai/docs/20260925-test-continue/、本 TASK/State。
exclude：未涉及的生产模块、数据库、共享开发数据、历史执行记录。
完成标准：至少覆盖源字节上下限、伪装格式、并发许可与故障恢复；定向 Java 测试通过；新增用例状态按实际覆盖程度登记。
设计与测试用例：.ai/docs/20260925-test-continue/design.md、testcases.md。
