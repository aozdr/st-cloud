# TASK-20260914-code-review-round3-03

## 角色

主线程 Workflow Manager；执行桌面端传输元数据与恢复语义。

## 目标

持久化 relay 参数，暂停后继续走 relay；应用重启导致 relay JVM 状态不可恢复时安全终止本次恢复，禁止绕回直传。

## Include

- `st-desktop` transfer task schema、迁移、类型、upload-manager 和纯函数测试。
- 构建/测试所需的最小桌面端调整。

## Exclude

- 服务器 relay 持久化。
- 普通直传字段和 UI 视觉重构。

## 完成证据

- D-01 至 D-03 测试结果、SQLite 升级结果、desktop test/lint/build:main。
