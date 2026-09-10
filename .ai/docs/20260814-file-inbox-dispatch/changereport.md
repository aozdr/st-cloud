# Change Report — 文件收件箱兜底投递（File Inbox Fallback）

## 背景

Codex Multi-Agent V2 在 DeepSeek（wire_api=responses，multi_agent_version=v2）下存在任务投递缺陷：spawn_agent / followup_task 的任务文本进入 `encrypted_content`，子代理上下文只看到空的 NEW_TASK 信封，任务被静默丢弃（本机 CLI 0.147.0-alpha.6.6 / Desktop 26.803.81509 复现，上游 openai/codex#37822）。此前"两个子代理中至少一个拿不到任务"即由此导致。

## 方案

项目侧绕行：不依赖 spawn message 投递任务文本，改为固定文件收件箱 `.ai/dispatch/inbox.md`：

- 主线程每次 spawn 前写入完整 Dispatch Envelope（UTF-8 无 BOM）；
- 子代理首轮无可见派发内容时（DeepSeek 下的常态），按 AGENTS.md 1.1 节读取收件箱 → 校验字段 → 原子归档到 `.ai/dispatch/archived/<dispatchId>.md` → 回复 DISPATCH_ACK；
- 保持 V8/V14 顺序准入：ACK（= 收件箱已归档）后才写下一个收件箱并 spawn；
- 返工/rework 一律走"新收件箱 + 新 child"，不依赖已完成 child 的原地 followup（实测不可靠）。

## 修改文件

- `AGENTS.md`：当前会话默认身份改为三选一判定；新增 1.1 文件收件箱模式；子 Agent 首轮 ACK 规则分"有可见派发 / 收件箱模式"两种。
- `.ai/knowledge/file-dispatch-runtime.md`（新增）：完整协议规范与实测记录。
- `.ai/templates/dispatch-template.md`：新增 File Inbox 兜底章节与 inbox.md 模板。
- `.ai/knowledge/parallel-dispatch-runtime-v8.md`：新增 V8.1 文件收件箱兜底。
- `.ai/agents/workflow-manager.md`：新增 V15 文件收件箱兜底硬规则。
- `.ai/knowledge/agent-dispatch-protocol.md`：新增 File Inbox 兜底恢复路径。
- `.gitignore`：忽略 `.ai/dispatch/`（运行时状态不入库）。

## 验证结果（真实执行）

1. 顺序 spawn `file_inbox_test_a` / `file_inbox_test_b`，各写一份收件箱：两个子代理均从收件箱读到各自 dispatchId（A-001 / B-001）并正确回复，inbox 均被原子归档。
2. rework 主路径：新收件箱 A-003 + 新 child，消费并正确回复。
3. 原地 followup 已完成 child（A-002 / A-004 各测一次）：child 被唤醒但复述旧结论、不查收件箱 → 判定为运行时限制，协议已规定返工不走此路径。

## 风险与注意

- 收件箱是共享单文件，依赖主线程严格顺序准入；并发写 inbox 会造成错配（协议已禁止）。
- 子代理首轮"无可见派发 → 查收件箱"依赖 AGENTS.md 注入生效（已实测生效）。
- 本方案是运行时缺陷的绕行，上游修复（openai/codex#37822）后可逐步回归 message 通道，收件箱保留为双写冗余。
