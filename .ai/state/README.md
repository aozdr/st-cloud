# Loop State 持久化目录

本目录存放 Agent Loop 的任务态文件（State），每个任务一份 YAML：

- 文件名：`<task-id>.yaml`（`task-id` 建议 `YYYYMMDD-<slug>`，如 `20260809-share-permission`）
- 内容：「Loop State 结构」定义的完整 YAML，顶部含 `taskId` 字段
- 生命周期：初始化时创建，每轮 Evaluate 覆写落盘，收敛后保留作审计记录

> 编排器每轮 Observe 第一步读取对应 State 文件作为事实源，禁止凭记忆推导 State。详见 `.ai/knowledge/loop-state-model.md` 的「State 持久化与加载」章节。

> 此目录由编排器维护，不要手工编辑活跃任务的 State 文件。