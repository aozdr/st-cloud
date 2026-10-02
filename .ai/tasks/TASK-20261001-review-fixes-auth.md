# TASK-20261001-review-fixes-auth

唯一任务：按 review-fixes-design-r1 修复浏览器多标签认证同步/续期，保留当前桌面认证方案。主线程已完成 DESIGN/TESTCASES。
include：st-web/src/auth-session.ts；st-desktop/src/auth-test-harness.cjs；st-web/src/store/auth-tabs.test.mjs；.ai/docs/20261001-review-fixes/auth-changes.md；.ai/runtime/results/DISPATCH-review-fixes-auth-01.json。
exclude：其它产品文件、State、旧文档证据、Git index、数据库、部署；禁止派生 Agent。
验收：顺序和并发标签页续期不误登出；退出传播和陈旧结果隔离；无 Web Locks 的 HTTP 环境也不能在成功落盘前被拒绝者清空；临时故障/桌面兼容。
验证：新增共享存储、真实 HTTP/CAS 语义和两个独立 VM/标签页的测试；运行认证25项、启动8项和本轮测试。无需运行 Maven/全量同步/构建。结果写中文分析与真实命令证据，by 使用运行时 canonical child identity，IMPLEMENTED 仅 proposal。
