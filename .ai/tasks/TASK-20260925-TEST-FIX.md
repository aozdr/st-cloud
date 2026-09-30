# 测试结果修复

目标：消除 TC04-30 本地恢复文件丢失竞争，修复 st-api 测试误连开发服务导致的启动失败。
规模：medium；主线程独立完成（用户明确要求不要开子线程）。
授权原话：“不要开子线程，你自己完成”；当前请求：“针对测试结果修复”。本任务设计、实现、测试、评审和验收均由主线程完成，评审标为自检。
include：st-desktop/src/sync/sync-recovery.ts、对应回归测试与 package.json；st-api 的 ReindexIntegrationTest；本任务文档和 State。
exclude：其他业务实现、数据库结构、生产配置、历史测试执行结果。
完成标准：真实文件竞争回归通过；目录与异常恢复覆盖；桌面类型检查及已有测试通过；默认 Maven 测试不连接真实重建服务且通过。
设计与用例：.ai/docs/20260925-test-fix/design.md。
