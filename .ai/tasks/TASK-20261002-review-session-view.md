# TASK-20261002-review-session-view

目标：解决 review 的同步恢复跨会话请求和瀑布流偏好丢失。
规模：large（涉及客户端账号隔离，独立 reviewer 验证）。
include：st-desktop/src/api-client.ts、st-desktop/src/sync-manager.ts、st-desktop/src/sync-engine.ts、新增同步会话测试、st-web/src/components/file/FileBrowser.tsx，以及本任务文档和独立评审结果。
测试范围补充：st-desktop/src/sync/sync-review.test.cjs（更新认证 mock 并覆盖启动对账切换会话）；新增 FileBrowser 视图回归。
exclude：后端、数据库、部署、已有其他业务变更。
验收：切换账号/退出/换服后旧恢复不得请求新凭据或修改同步配置；正常轮换不中断；纯图片首次加载保留瀑布流，混合目录仍安全回退。
方案与测试见 .ai/docs/20261002-review-session-view/design.md。
