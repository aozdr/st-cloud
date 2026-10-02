# 当前测试结果（主线程集成验证）

review-fixes-code-r4，workflow-manager。用户要求跳过额外派发与重复测试；不声称由新tester执行。

主线程实际执行 node --test --test-reporter=spec st-web/src/store/auth-tabs.test.mjs st-web/src/store/auth-startup.test.mjs st-desktop/src/auth-rotation.test.cjs：退出0，60/60（多页27、启动8、desktop25），失败0/跳过0。独立 r4 Code Review 重跑多页27/27，并做真实HTTP/Store/API写失败及正常拒绝对照。

本轮后端 Maven 离线指定 TeamRecycleBinIntegrationTest 21、SchemaConsistencyTest 3、TenantRecycleScanIntegrationTest 1、RecycleBinPurgeTenantTest 1：退出0/BUILD SUCCESS，26/26，失败0/跳过0。证据 core-test-evidence.json 与当前四套 surefire XML；Core三文件从该运行到当前SHA未变，故不重复运行。

Web tsc --noEmit --incremental false -p tsconfig.app.json 与 desktop tsc --noEmit --incremental false 均退出0。当前六SHA见 source-manifest-r4.json。

T01–T04/T08–T12 由认证回归覆盖跨页排队、事件/初始化、旧会话、永久拒绝、离线/存储失败及重载；T05–T06 H2覆盖墓碑清理、引用/事件、幂等、权限和正常节点；T07构建/schema/typecheck通过。MySQL专库凭据为空，未运行；外部删除事件受Mock控制，未作S3/UI实测。
