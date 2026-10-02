# TASK：简化 Agent Loop

- Task ID: TASK-20261001-loop-efficiency
- State: `.ai/state/20261001-loop-efficiency.yaml`
- 规模：medium（可逆的流程工具模块变更；不改业务、数据库或外部服务）
- 输入：用户要求优化低效 Loop，并基于 OpenAI 官方 agents 建议。
- 设计：`.ai/docs/20261001-loop-efficiency/design.md`
- 用例：`.ai/docs/20261001-loop-efficiency/testcases.md`

## 目标与验收

1. small 直接处理；medium 主线程完成；large 保留独立代码/安全评审，门禁与文档数量减少。
2. 测试可在实现完成后立即执行；最终验收仍等待所有必要证据。
3. State、脚本、入口、模板和指南一致；旧 definitionVersion=2 的 State 保持原规则、原文件不变。
4. 新路径有正反例，旧状态机回归通过；无真实耗时基准时不宣称性能百分比。

## include / exclude

- include：AGENTS.md；`.ai/loop/`；`.ai/schema/loop-state.schema.json`；Loop 校验、迁移脚本与测试；相关 agents/knowledge/workflows/templates；本任务产物。
- exclude：所有业务源码、数据库、部署配置、其他任务的 State/结果/文档、Git index；不执行提交、发布或数据迁移。

## 验证

Loop 新路径正反例、原 loop-v2 测试、静态检查、git diff --check。Worktree 工具不变，不重复执行其完整集成套件。
