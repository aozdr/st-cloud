# 测试报告 — 双子代理投递问题端到端验证（文件收件箱兜底）

## 目标

在已落地的文件收件箱兜底机制（AGENTS.md 1.1 + `.ai/dispatch/inbox.md`）上，完成"两个子代理"问题的剩余验证：双只读 TASK 顺序注入与并行执行、收件箱为空时的失败恢复、运行中子代理的 followup 追加载荷。结果落盘，供后续回顾。

## 环境

- Codex CLI 0.147.0-alpha.6.6 / Desktop 26.803.81509
- provider: DeepSeek（deepseek-v4-flash，wire_api=responses，multi_agent_version=v2）——即缺陷复现环境
- 执行时间：2026-08-14 10:31–10:42（UTC+8）
- 子代理均使用 `fork_turns="none"`，spawn message 照旧被运行时丢弃，任务经收件箱投递

## 场景矩阵

| 用例 | 操作 | 实际结果 | 结论 |
|------|------|----------|------|
| TC1 双任务投递与并行 | 写 A 收件箱 → spawn A → 归档确认 → 写 B 收件箱 → spawn B | A/B 各自从收件箱读到 `file-inbox-e2e-A-001` / `-B-001` 并原子归档；A 10:31:45–10:33:01（含 40s 停顿），B 10:32:08–10:32:41，执行窗口重叠约 53s | 通过 |
| TC2 越界防护 | 子代理严格按 scope 执行 | 两份 changereport 引用的文件均在 scope.include 白名单；未创建子 Agent；未修改业务代码 | 通过 |
| TC3 收件箱为空恢复 | inbox 不存在时 spawn 探针；重写收件箱后派新 child | 探针返回 `DISPATCH_MISSING / reason: inbox empty`（无待命式文案）；恢复信封 `file-inbox-recover-003` 被消费归档并回复 RECOVER_ACK | 通过 |
| TC4 运行中 followup | C 含 30s 停顿；归档后写追加载荷 C-002 并 followup_task(C) | C 未消费 C-002（inbox 原样保留，State Delta 无确认节，会话日志 02:35:03Z 可见消息照旧进 encrypted_content）；新 child D 消费 C-002 并返回"followup 载荷确认"节 | 原地 followup 失败（运行时限制）；新子代理兜底通过 |
| TC5 报告落盘 | 本文件 | testreport.md 生成 | 通过 |

## 证据

- 子代理会话日志（原始证据，留存于 `E:\AI-Work\sessions\2026\08\14\`）：
  - `rollout-2026-08-14T10-31-45-...jsonl`（A：归档、40s 停顿、DISPATCH_ACK）
  - `rollout-2026-08-14T10-32-08-...jsonl`（B：归档、DISPATCH_ACK）
  - `rollout-2026-08-14T10-34-38-...jsonl`（C：初始信封消费 + followup 投递记录 02:35:03Z，载荷进 encrypted_content）
- 子代理产出：`.ai/docs/20260814-file-inbox-dispatch/changereport-verify-a.md`、`-b.md`、`-c.md`（c 末尾含 D 追加的 followup 载荷确认节）
- TASK 夹具：`.ai/tasks/TASK-FILE-INBOX-VERIFY-A/B/C.md`
- 收件箱归档信封：`.ai/dispatch/archived/`（瞬时运行状态，测试后已清理；如需复核可依会话日志重放）

## 结论

1. 文件收件箱兜底在双任务场景稳定工作：两个子代理均能从收件箱拿到各自任务、并行执行、产出白名单合规的报告，无串扰。
2. 失败路径可恢复：收件箱为空时子代理返回标准 `DISPATCH_MISSING`，重写收件箱后新子代理正常消费并 ACK。
3. 运行时限制确认（与知识库可靠性分级一致）：**无论已完成还是运行中，原地 followup_task 的载荷都无法到达子代理**——消息照旧被 `encrypted_content` 丢弃，且子代理被唤醒后不会重查收件箱。可靠做法是"新收件箱 + 新子代理"重派。

## 遗留风险

- 收件箱是共享单文件，依赖主线程严格顺序准入；并发写收件箱会造成错配（协议已禁止）。
- 本轮为只读演练验证；真实业务任务仍需主线程按 Dispatch 验收标准 Evaluate，且返工一律走新子代理重派。

---

# V2 多文件认领隔离验证（2026-08-14 追加）

## 背景

V1 单收件箱存在两个限制：① 所有子代理看同一个 `inbox.md`，隔离靠"顺序准入"而非文件隔离；② 文件写入与 spawn 必须串行等待。用户提出"派发分开两个文件、两个子代理不要看同一个文件"。身份探针实测确认子代理上下文无自身身份字段（agent_path / task_name 不可见），无法"按名读文件"，因此 V2 采用"一任务一文件 + 原子认领"。

## 协议变更

- 收件箱文件：`.ai/dispatch/inbox-<dispatchId>.md`（每任务一个，可预先全部写齐）
- 子代理消费：列出 `inbox-*.md` 候选 → 按文件名排序取第一个 → `Move-Item` 原子认领到 `archived/`（同名）→ 失败换下一个候选 → 读取认领到的文件 → 校验 → ACK
- 主线程：按 dispatchId 归集结果，不依赖子代理名称；文件预写后即可连续 spawn
- 同步更新：AGENTS.md 1.1、file-dispatch-runtime.md（V2）、dispatch-template.md、parallel-dispatch-runtime-v8.md（V8.2）、workflow-manager.md（V15.1）

## 验证结果（真实执行）

| 检查项 | 实际结果 | 结论 |
|--------|----------|------|
| 预写两个独立文件 | `inbox-fileiso-A-001.md` / `inbox-fileiso-B-001.md` 同时存在 | 通过 |
| 连续 spawn 两个 child（不等待 ACK） | `file_iso_a`、`file_iso_b` 均正常启动 | 通过 |
| 各认领不同文件 | A 认领 `inbox-fileiso-A-001.md`；B 认领 `inbox-fileiso-B-001.md`；`archived/` 下两个文件并存，无候选残留 | 通过 |
| ACK 携带各自 dispatchId | A：`fileiso-A-001`；B：`fileiso-B-001` | 通过 |
| 内容隔离（未读对方文件） | 会话日志：A 仅执行"列举文件名 → 认领 A-001 → 读取 A-001"，未读取 B 文件内容 | 通过 |

子代理回复原文：

- A：`ISO_ACK / dispatchId: fileiso-A-001 / claimed: inbox-fileiso-A-001.md`
- B：`ISO_ACK / dispatchId: fileiso-B-001 / claimed: inbox-fileiso-B-001.md`

证据：子代理会话日志 `E:\AI-Work\sessions\2026\08\14\rollout-2026-08-14T10-47-48-*.jsonl`（A）、`rollout-2026-08-14T10-47-51-*.jsonl`（B）。

## 结论

1. V2 实现了文件级任务隔离：两个子代理**内容上永远不会读取同一个文件**（认领即移动，先到先得；A 的日志证明其只读取自己认领的文件）。
2. 不再需要"等 ACK 才写下一个收件箱"，文件预写 + 连续 spawn 可行。
3. 已知边界：子代理在认领前会**列出候选文件名**（目录列举），但不会读取其它文件内容；文件名的唯一信息是 dispatchId，无任务内容泄露。
4. 原地 followup 限制不变（已完成/运行中均不重查收件箱），返工仍走"新文件 + 新子代理"。

---

# 并行编码隔离验证（2026-08-14 追加）

## 目标

验证两个 executor 子代理能否**同时编写代码**且互不干扰、不影响正式项目：在 gitignored 隔离区 `.ai/lab/` 下并行编写两个独立 Java 模块并各自编译冒烟。

## 环境与隔离手段

- 隔离区：`.ai/lab/module-a/`、`.ai/lab/module-b/`（`.gitignore` 新增 `.ai/lab/`）
- 投递：V2 多文件认领（预写 `inbox-code-A-001.md` / `inbox-code-B-001.md`，连续 spawn）
- 任务：TASK-CODE-LAB-A（Calculator）、TASK-CODE-LAB-B（TextStats），scope 白名单仅允许各自 module 目录 + 收件箱 + TASK + role-context，禁止触碰任何 `st-*` 业务代码

## 验证结果（真实执行）

| 检查项 | 实际结果 | 结论 |
|--------|----------|------|
| 同时启动 | A 03:11:03Z / B 03:11:06Z 连续 spawn | 通过 |
| 并行重叠 | A 03:11:03–03:11:59，B 03:11:06–03:12:15，重叠约 53s | 通过 |
| 各自认领信封 | `archived/inbox-code-A-001.md`、`inbox-code-B-001.md` 均存在 | 通过 |
| 写入路径隔离 | 会话日志：A 的 apply_patch 仅含 `lab/module-a`，B 仅含 `lab/module-b` | 通过 |
| 编译冒烟（主线程复跑） | `javac -encoding UTF-8` 两者 EXIT=0；A 输出 MODULE_A_OK，B 输出 MODULE_B_OK | 通过 |
| 项目零影响 | 仅写 `.ai/lab/**` 与 `.ai/dispatch/**`（均 gitignored），未触碰 `st-*` 与 `.ai/` 白名单外文件 | 通过 |

## 产物

- `.ai/lab/module-a/ModuleA.java`（Calculator：四则运算 + 除零保护，中文注释）
- `.ai/lab/module-b/ModuleB.java`（TextStats：wordCount/charCount/lineCount，中文注释）

## 结论

并行编码可行且隔离有效：多文件认领保证任务互不可见，scope 白名单保证写入互不越界，gitignored lab 区保证项目零影响。子代理还自主处理了 Windows 下 `javac` 的 GBK/UTF-8 编码差异（改用 `-encoding UTF-8` 编译），说明执行者在真实编码任务中具备独立排障能力。
