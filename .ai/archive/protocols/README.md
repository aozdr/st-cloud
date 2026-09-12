# 历史协议归档

本目录保存已废弃的 Agent Loop/Dispatch 运行时协议，仅用于审计和历史追溯。

硬规则：

- 本目录内容不是当前指令，不得被 Workflow Manager、子 Agent、模板或运行脚本加载；
- 文内命令、路径、兼容策略和交叉引用均保持历史原貌，不代表当前能力；
- 当前 Dispatch 协议只读 `.ai/knowledge/agent-dispatch-protocol.md`；
- 当前 State、Dispatch 和退出标准结构分别以 `.ai/schema/loop-state.schema.json`、`.ai/schema/dispatch.schema.json`、`.ai/loop/exit-criteria.yaml` 为准。

| 归档文件 | 原路径 | 归档原因 |
|---|---|---|
| `file-dispatch-runtime.md` | `.ai/knowledge/file-dispatch-runtime.md` | 文件投递和兼容路径已移除 |
| `parallel-dispatch-runtime-v7.md` | `.ai/knowledge/parallel-dispatch-runtime-v7.md` | 并行派发旧版 |
| `parallel-dispatch-runtime-v8.md` | `.ai/knowledge/parallel-dispatch-runtime-v8.md` | 顺序准入/文件确认旧版 |
| `task-isolation-migration.md` | `.ai/knowledge/task-isolation-migration.md` | 旧隔离迁移说明 |
