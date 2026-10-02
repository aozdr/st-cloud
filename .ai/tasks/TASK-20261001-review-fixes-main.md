# TASK-20261001-review-fixes-main

目标：修复本次 review 的两项 P2：浏览器多标签续期误登出，已删除团队空间回收文件无法清理。
规模 medium：两个独立局部 Bug，未改变认证/文件生命周期契约，不做跨模块重构。设计 review-fixes-design-r1。

include：st-web/src/auth-session.ts；st-desktop/src/auth-test-harness.cjs；st-web/src/store/auth-tabs.test.mjs；st-core/src/main/java/com/stcloud/core/mapper/TeamStorageMapper.java；st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java；st-core/src/test/java/com/stcloud/core/service/impl/TeamRecycleBinIntegrationTest.java；.ai/docs/20261001-review-fixes/**；.ai/state/20261001-review-fixes.yaml；本轮 TASK/Dispatch 结果；.ai/knowledge/review-fixes-auth-recycle.md。
exclude：其他产品源码、数据库迁移、开发/生产数据、旧 State/证据、Git index、部署/重启服务。

输入：本轮 requirement.md、design.md、testcases.md，当前源码及已有用户改动。
验收：A1 多标签共享完整最新令牌对并协调并发刷新；A2 陈旧拒绝不清理新会话，退出传播，离线/桌面兼容；A3 已软删除空间过期回收成功，用户恢复/删除权限不扩大；A4 对应回归、类型检查、独立审查和证据通过。
