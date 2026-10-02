# Loop State

small 默认不持久化。medium/large 每任务一份 JSON-compatible YAML，文件名 <task-id>.yaml；主线程创建一次，按变化记录 Evaluate/修订/证据。

schemaVersion=2，默认新流程 definitionVersion=3。V2 自动选择冻结定义继续校验，历史原文件保留，不静默升级。有效当前任务直接续作，不重建完成项。

结构见 `.ai/schema/loop-state.schema.json`；操作见 `.ai/knowledge/agent-loop-runbook.md`。不手工把门禁标为 done。
