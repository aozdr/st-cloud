# 星云盘 Agent Loop 预设

这是一个 DSH agent preset bundle（`package.json` + `cordis.patch.yml`，外加本说明），把星云盘仓库的 Agent Loop 角色固化成三个可选择的 DSH 预设：

| 预设 id | 显示名 | 角色与边界 |
|---|---|---|
| `st-loop` | 星云盘 Loop 主线程 | workflow-manager：分级、TASK、实现、验证、派发独立评审、验收 |
| `st-loop-exec` | 星云盘 Loop 执行 | executor：只在 TASK scope 内实现/调查，不写 State、不定义 Goal、不互派、不向用户确认 |
| `st-loop-review` | 星云盘 Loop 评审 | reviewer：独立审查当前 revision，只读代码与测试，输出 findings/verdict/证据 |

## 设计原则

- **不复制门禁**：分级与 DAG 的唯一事实源仍是 `.ai/loop/exit-criteria.yaml`，执行步骤见 `.ai/knowledge/agent-loop-runbook.md`。
- **AGENTS.md 自动加载**：三个预设都挂 `@deepseek-ai/dsh-agent-instructions`，会话启动即读取仓库根 `AGENTS.md`；persona 只补充角色、门禁顺序、派发与证据纪律。
- **技能可发现**：挂 `skill-filesystem` + `tool-skill`，主线程会加载 `st-cloud-loop` 技能。
- **角色真正分离**：主线程的 `subagent` 工具配置了 `agentPreset: st-loop-exec` 与 `allowedAgentPresets: [st-loop-exec, st-loop-review]`；派发评审时显式传 `agent_preset: "st-loop-review"`，子 Agent 使用独立预设，而不是同一人换说法。
- **工具按角色裁剪**：执行/评审子 Agent 不挂 `subagent`、`ask_user_question`、Goal 工具；评审预设关闭 `tool-fs` 的 `edit`，只保留 `write` 用于写自己的结果文件。

## 安装与更新

当前 profile 已安装（`node_modules/@st-cloud/dsh-loop-agent` 是指向本目录的 junction）。重装或更新：

```text
plugin_manager install_bundle target = <本目录的绝对路径>
```

也可以从 Web UI 的 Plugins 面板安装本地 bundle。注意：Web 预设编辑器保存的修改会写入 profile patch，并以 `preset-st-loop*` 行 id 整段覆盖 `config`；若还要继续用本 bundle 维护，请把修改同步回 `cordis.patch.yml` 再重装。

## 验证

1. `plugin_manager list_plugins` 中 `preset-st-loop`、`preset-st-loop-exec`、`preset-st-loop-review` 三行 `enabled: true`、`fiberPhase: active`。
2. `cordis_inspect_query`（Host `Config.listConfigs`，name=`@deepseek-ai/dsh-agent-preset`）能看到七条预设声明（四条内置 + 三条本 bundle）。
3. 新建会话时在预设选择器中选择「星云盘 Loop 主线程」，确认 persona 与工具集生效；large 任务派发评审时确认子 Agent 使用「星云盘 Loop 评审」。

## 边界

- 预设只负责角色、工具与提示，**不替代** `loopctl.ps1` / `verify-loop.ps1` 的状态机与门禁校验。
- 评审预设的只读是工具层约束（关 `edit`、保留 `write`），`pwsh` 仍可修改文件；需要硬隔离时给评审会话选择只读权限预设。
- 当前主线程预设未挂 `tool-web`、`plan-mode`、`tool-workflow`、`tool-schedule`；Loop 的派发使用原生 `subagent`，如需这些能力按需增补。
