# TASK-20260914-code-review-round3-02

## 角色

主线程 Workflow Manager；执行 Relay 序号状态机。

## 目标

让 relay 序号在完整请求成功后才提交，并覆盖失败、跳号、重复提交。

## Include

- `RelayBufferManager` 序号字段、决策和失败恢复。
- `UploadServiceImpl` relay chunk 错误路径。
- st-core relay 单元/集成测试。

## Exclude

- relay 持久化到数据库。
- 普通直传协议变更。

## 完成证据

- R-01 至 R-04 真实测试结果。
