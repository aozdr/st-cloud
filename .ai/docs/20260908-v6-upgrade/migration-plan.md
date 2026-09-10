# st-cloud Agent Loop V5 → V6 迁移方案

> 状态：**待用户确认**（确认前不修改任何现有规则与配置）
> 范围：Phase 0 —— 只做差异分析、文件分类、冲突识别与迁移设计
> 输入：AGENTS.md、`.ai/**` 全部 Loop 配置、`st-cloud-Agent-Loop-V6-2026-architecture.md`
> 产出：本文件。**本次未新增/修改/删除任何现有 Loop 规则文件。**

## 0. 阅读核对结果（先看这里）

你列出的阅读清单里有两个路径在仓库中**不存在**，需要先澄清：

| 清单路径 | 实际状态 | 说明 |
|----------|----------|------|
| `.ai/knowledge/agent-task-context.md` | 不存在 | 最接近的是 `.ai/knowledge/task-isolation-migration.md`；任务隔离规则也散落在 `agent-dispatch-protocol.md` 与 `workflow-manager.md` |
| `.ai/skills/` | 不存在 | 技能实际是全局安装的 `C:/Users/Administrator/.agents/skills/`，项目内只有 `.codex/agent-skills.md` + `.ai/knowledge/skill-mapping.md` 做映射 |

其他实际情况：

- `.ai/` 现有：`agents` / `decisions/ADR` / `knowledge`(25 篇) / `scripts` / `state`(24 个) / `tasks`(94 个) / `templates`(10 个) / `workflows` / `dispatch` / `docs`(61 个迭代目录)
- 运行时：`.codex/config.toml` 走 Dots provider（非 OpenAI），**未配置任何 MCP server**；已知 spawn 任务文本投递缺陷，文件收件箱是实际投递通道
- 当前"机器校验"只有 `.ai/scripts/verify-loop.ps1` 与 `compare-schema.ps1`，没有 Verification Engine、没有 Evidence、没有 Hook

---

## 一、V5 与 V6 目标架构差异

| 维度 | V5 现状 | V6 目标 | 差距 | 处理方向 |
|------|---------|---------|------|----------|
| 上下文 | 子 Agent 拿到 Dispatch + TASK，knowledge 无边界可全读 | 五层 Context + bounded Task Context | 大 | 新增 `.ai/context/` + `.ai/runtime/tasks/<id>/context.md`，Dispatch 增 `context` |
| 规格 | requirement.md/design.md 为自然语言，验收标准不可机器判定 | `acceptance.yaml` 机器可验证 | 大 | 新增 `.ai/specs/SPEC-xxx/`，与现有 docs 划清边界 |
| Task | Markdown 模板，无 `specRef/context/output` 结构 | V6 Task Schema（结构化） | 中 | 升级模板；保留 Markdown 兼容，不强制 YAML |
| Dispatch | 缺 `context/tools/output` | 补齐三字段 | 中 | 扩展 Envelope，旧信封仍可解析 |
| Skill | 能力型（java/mysql/react） | 流程型 Playbook | 中 | 现有全局技能不动；新增项目内流程层 |
| 工具 | 子 Agent 直接用 shell，无权限模型 | Tool Gateway + 按角色权限 | 大 | 先做声明式白名单 + 检测脚本；MCP 后置 |
| Hook | 无 | pre-task / pre-edit / post-edit / pre-review / verify | 大 | 脚本化，由 WM 在阶段边界调用 |
| Verification | Tester 执行测试；WM 串行跑 mvn | 独立 Verification Engine | 中 | 脚本化验证 + 结构化 Evidence |
| Evidence | 自然语言"测试通过" | command/exitCode/status/timestamp/ref | 大 | 新增 `.ai/runtime/evidence/` |
| Review | CODE_REVIEW / SECURITY_REVIEW（+ 隐式 UI/体验） | Architecture/Quality/Security/Performance/Coverage 五轴 | 中 | 保留现有标准，扩为子轴，不新增顶级 exitCriteria |
| Stale | 代码变更级联 | 增加 context/spec/evidence stale | 中 | 扩展 State `stale` 分类，不替换现有 cascade |
| 角色 | 4 类（2026-08-14 刚收敛） | 建议 13 个 | — | **不采纳**，见第八节 |
| State | `exitCriteria/artifacts/blockers/history` | `workflow/gates/tasks/evidence` | 中 | 扩展不替换，保持现有字段为事实源 |

结论：V6 的 5 层（Context/Spec/Tool/Hook/Verify+Evidence）里，**Spec 与 Verification+Evidence 价值最高、可先做**；MCP 在当前 runtime 下不具备落地条件，应后置为声明式设计。

---

## 二、现有 .ai 文件逐个分类

### 2.1 根与 Agent

| 文件 | 分类 | 说明 |
|------|------|------|
| `AGENTS.md` | 修改 | 只做增量：加 V6 入口指针与 Context/Spec/Evidence 门禁摘要。**不把 always-on 规则搬走**（它是唯一自动注入的上下文，搬走会降低遵循度） |
| `.ai/agents/workflow-manager.md` | 修改 | Observe 增 Context Refresh；Plan 增 Spec Check；Dispatch 增 context/tools/output；Evaluate 增 Hook/Verify/Evidence 门禁 |
| `.ai/decisions/ADR/**` | 保留 | 与 V6 `decisions.md` 语义一致，不迁移 |

### 2.2 `.ai/knowledge/`（25 篇）

| 文件 | 分类 | 说明 |
|------|------|------|
| `loop-state-model.md` | 修改 | 增 `spec/context/evidence/stale` 字段与 V6 State 扩展段 |
| `agent-dispatch-protocol.md` | 修改 | Envelope 增 `context/tools/output`，与 dispatch-template 同步 |
| `agent-loop-runbook.md` | 修改 | 派发检查清单增 context 打包与 evidence 校验 |
| `agent-output-standard.md` | 修改 | 输出标准增「Evidence」段 |
| `document-management.md` | 修改 | 明确 `.ai/specs/`、`.ai/docs/`、`.ai/runtime/evidence/` 三者边界 |
| `file-dispatch-runtime.md` | 修改 | 收件箱信封增 context 包；ACK 增 evidence 目录引用 |
| `loop-verification-checklist.md` | 修改 | 增 C30+（Context/Spec/Evidence/Hook 校验项） |
| `role-context.md` | 修改 | 增工具权限矩阵；tester 职责收敛为"设计/补充/失败分析" |
| `skill-mapping.md` | 修改 | 增"流程 Playbook"映射与 `tools` 映射，能力型技能保留 |
| `architecture.md` | 保留 | 作为 `context/project` 的事实源，不复制 |
| `conventions.md` | 保留 | 作为 `context/project/constraints` 的事实源 |
| `testing.md` | 保留 | 作为 Verification 命令来源 |
| `api-reference.md` / `data-model.md` / `business-domain.md` | 保留 | 作为 `context/domain` 的事实源；business-domain 按领域拆分时**引用**而非复制 |
| `frontend.md` / `ui-design-system.md` | 保留 | 作为 `context/frontend` 事实源 |
| `project-overview.md` | 保留 | 作为 `context/project` 事实源 |
| `sync-engine-v2.md` | 保留 | 领域知识事实源 |
| `natural-request-guide.md` | 保留 | 用户入口规则 |
| `task-isolation-migration.md` | 保留 | runtime 事实记录 |
| `parallel-dispatch-runtime-v7.md` / `v8.md` | 保留 | runtime 事实记录 |
| `loop-dryrun-escalation.md` / `loop-dryrun-favorites.md` | 保留（归档） | 历史演练记录，不再维护 |

### 2.3 `.ai/templates/`（10 个）

| 文件 | 分类 | 说明 |
|------|------|------|
| `requirement-template.md` / `design-template.md` | 保留 | 继续用；Spec 版在其上加"可验证验收"章节 |
| `test-case-template.md` / `code-review-template.md` | 保留 | 增 Spec 轴与 Evidence 引用 |
| `task-template.md` / `dispatch-template.md` | 修改 | 增 V6 字段（specRef/context/tools/output） |
| `architecture-review-template.md` / `ui-design-template.md` / `discovery-template.md` / `adr-template.md` | 保留 | 不动 |
| `acceptance.yaml` / `test-cases.yaml` / `task.yaml` / `state.yaml` | 新增 | 机器可读模板 |

### 2.4 `.ai/scripts/`

| 文件 | 分类 | 说明 |
|------|------|------|
| `verify-loop.ps1` | 修改 | 增 V6 静态校验（specs 目录、模板存在、State 字段、evidence 目录） |
| `compare-schema.ps1` | 保留 | 直接纳入 Verification Engine 的 schema 门禁 |
| `worktree.ps1` | 保留 | 文件系统隔离不变 |
| `cdp-*.mjs` | 保留 | 浏览器能力，纳入 Tool Gateway 白名单 |
| `verify-task.ps1` / `scope-check.ps1` / `evidence.ps1` | 新增 | Verification Engine 与 Evidence 写入 |

### 2.5 目录级

| 路径 | 分类 | 说明 |
|------|------|------|
| `.ai/state/**`（24） | 保留 | 历史不动；新任务用 V6 扩展字段 |
| `.ai/tasks/**`（94） | 保留 | **不挪目录、不改名**（挪动会打断 90+ taskRef 与 verify-loop 交叉引用） |
| `.ai/docs/**`（61） | 保留 | 继续承载执行记录 |
| `.ai/workflows/feature-development.md` | 修改 | 增 Context/Spec/Verification/Evidence 段 |
| `.ai/dispatch/**` | 保留 | 运行时投递通道，gitignore |
| `.ai/context/{project,domain,frontend}/` | 新增 | 面向 Agent 的裁剪视图，不复制 knowledge 全文 |
| `.ai/specs/` | 新增 | SPEC-xxx 契约目录 |
| `.ai/hooks/{pre-task,pre-edit,post-edit,pre-review,verify}/` | 新增 | 脚本化 Hook |
| `.ai/runtime/{evidence,tasks,events}/` | 新增 | 证据、任务上下文、事件 |
| `.ai/skills/` | 新增（薄层） | 只放项目内流程 Playbook 索引，不搬全局技能 |
| `.ai/mcp/{registry.yaml,permissions.yaml}` | 新增（Phase 6） | 声明式，暂不接真实 MCP server |

### 2.6 废弃/迁移

**无。** 本次迁移不删除、不移动任何现有文件。V6 强调增量升级，删除收益低、破坏面大。

---

## 三、当前规则之间的冲突

| # | 冲突点 | 涉及文件 | 现状 | 建议裁决 |
|---|--------|----------|------|----------|
| 1 | `fork_turns` 是否固定 | `workflow-manager.md`、`dispatch-template.md`、`task-isolation-migration.md`、`agent-dispatch-protocol.md` | 一处写"不得写死/不得同时出现 all 与 none"，一处写"执行型 child 固定 none"，一处句子残缺 | 统一为：**执行型 child 固定 `fork_turns="none"`**，runtime 不支持则 `DISPATCH_RUNTIME_UNSUPPORTED` |
| 2 | 退出标准项数不一致 | `loop-state-model.md`、`feature-development.md`、`loop-verification-checklist.md` | 中型表 8 行却写"6+1"；小型表 4 行写"3 项"；checklist 写 medium=5+1/small=3 | 校准为：中型 8 项（含 SECURITY 条件项）、小型 4 项；同步 checklist C3 |
| 3 | 谁是最后完成项 | `loop-state-model.md`、`loop-verification-checklist.md`、`verify-loop.ps1` | 模型/脚本终点是 ACCEPT；checklist C21 写"KNOWLEDGE 是最后完成项"；脚本注释写"终点仅 KNOWLEDGE" | 统一为：**ACCEPT 是最终收敛点**，KNOWLEDGE 是其前置；改 checklist 与脚本注释 |
| 4 | `skillRefs` 缺失判定 | `dispatch-template.md`、`skill-mapping.md`、`AGENTS.md` | 模板允许填 `-`，AGENTS.md 写"缺失即 DISPATCH_INVALID" | 明确：`skillRefs` 字段必须存在；无适用技能填 `-`，与"字段缺失"区分 |
| 5 | 角色数量 | `role-context.md` vs V6 文档 | V5 已收敛为 4 类；V6 建议 13 个 | **保持 4 类**，V6 的细粒度用 `taskType` + 工具权限表达 |
| 6 | Tester 是否执行验证 | `role-context.md`、`workflow-manager.md` | tester taskType=test 负责测试执行；V15.3 又规定 WM 串行执行构建/测试 | 收敛为：tester 负责用例设计与失败分析，**执行权归 Verification Engine（脚本）/主线程** |
| 7 | 文档落盘位置 | `document-management.md` vs V6 目录建议 | 现有全部在 `.ai/docs/<task-id>/`；V6 要求 `.ai/specs/SPEC-xxx/` | 划边界：specs 放契约（requirement/design/acceptance/test-cases/decisions），docs 放执行记录（impact/codereview/security/testreport/changereport/knowledge） |
| 8 | State 结构 | `loop-state-model.md` vs V6 State | 现有 `exitCriteria/artifacts/...`；V6 提 `workflow/gates/tasks/...` | **扩展不替换**：保留 exitCriteria 为门禁事实源，新增 spec/context/evidence/stale 字段 |
| 9 | Skill 定位 | `skill-mapping.md`、`.codex/agent-skills.md` vs V6 | 现为能力型技能映射 | 分阶段：能力型技能保留，新增 `.ai/skills/` 流程 Playbook；知识进 context、工具进 gateway |
| 10 | Hook 的"机器强制" | 全部 | V6 要求 Hook 机器强制；当前 runtime 不调用任何项目 Hook | 明确：Hook 只能是**脚本 + 编排器调用**的软强制，退出码非 0 阻断；不得宣称 runtime 级强制 |
| 11 | MCP 可用性 | `.codex/config.toml` vs V6 | 当前无 MCP server | MCP 后置为声明式设计，先做工具白名单与检测；不引入无法运行的基础设施 |
| 12 | 目录挪动 | V6 建议 `tasks/active|archive`、`runtime/state` | 现有 `.ai/tasks/` 94 个、`.ai/state/` 24 个被大量交叉引用 | **不挪**。保持现有路径，避免 90+ 引用断裂 |
| 13 | 文档是否入库 | `document-management.md` vs `.gitignore:65` | 规范写"文档纳入 git、不放入 .gitignore"，实际 `.ai/docs/` 被 `.gitignore` 忽略 | 明确裁决：契约类（`.ai/specs/`）应入库且不忽略；执行记录（`.ai/docs/`）是否继续本地化需用户拍板（见 P1） |

---

## 四、核心文件修改清单

| 文件 | 类型 | 改动摘要 | 阶段 |
|------|------|----------|------|
| `.ai/knowledge/loop-state-model.md` | 修改 | 增 spec/context/evidence/stale；校准项数与终点 | P1 |
| `.ai/knowledge/document-management.md` | 修改 | specs/docs/runtime 三者边界与映射 | P1 |
| `.ai/templates/acceptance.yaml` 等 4 个 | 新增 | 机器可读模板 | P1 |
| `.ai/scripts/verify-loop.ps1` | 修改 | 增 V6 静态校验项 | P1 |
| `.ai/knowledge/role-context.md` | 修改 | tester 职责分离 + 工具权限矩阵 | P2 |
| `.ai/scripts/verify-task.ps1` / `evidence.ps1` | 新增 | Verification Engine + Evidence | P2 |
| `.ai/knowledge/agent-output-standard.md` | 修改 | 增 Evidence 段 | P2 |
| `.ai/knowledge/loop-verification-checklist.md` | 修改 | 增 C30+ | P2 |
| `.ai/agents/workflow-manager.md` | 修改 | 五阶段内挂 Context/Spec/Verify/Hook | P3 |
| `.ai/knowledge/agent-dispatch-protocol.md` | 修改 | Envelope 增 context/tools/output | P3 |
| `.ai/templates/dispatch-template.md` / `task-template.md` | 修改 | 同步 V6 字段 | P3 |
| `.ai/knowledge/file-dispatch-runtime.md` | 修改 | 收件箱信封增 context 包 | P3 |
| `.ai/hooks/*.ps1` | 新增 | 4 类 Hook | P4 |
| `.ai/skills/**` | 新增 | 流程 Playbook | P5 |
| `.ai/mcp/{registry,permissions}.yaml` | 新增 | 声明式工具权限 | P6 |
| `AGENTS.md` | 修改 | 增量加 V6 门禁摘要与入口 | P6 |

---

## 五、分阶段迁移计划

原则：每阶段可独立交付、独立回滚；任一阶段失败不影响已完成任务；旧 State/Task 保持可读。

### Phase 0 分析与迁移设计（本次，已完成）

- 产出：本文件
- 动作：只读分析，零配置改动
- 出口：用户确认本方案

### Phase 1 骨架与 Spec（低风险，解锁机器可验证）

- 建目录：`.ai/specs/`、`.ai/runtime/evidence/`、`.ai/runtime/tasks/`
- 新增模板：`acceptance.yaml`、`test-cases.yaml`、`task.yaml`、`state.yaml`
- 改 `loop-state-model.md`：artifacts 增 `spec/acceptance/testCases/evidence`；State 增 `specRef/contextRef/stale/evidence`
- 改 `document-management.md`：specs 与 docs 边界
- 改 `verify-loop.ps1`：新增目录/模板/字段校验
- 出口：`verify-loop.ps1` PASS；新模板可被现有流程读取；旧任务零影响
- 回滚：删除新增目录/模板，git revert 两个 md 与脚本

### Phase 2 Verification + Evidence（最高价值）

- 新增 `.ai/scripts/verify-task.ps1`：按 acceptance.yaml 跑 build/test/lint/scope-check，写 `.ai/runtime/evidence/<TASK>/<gate>.json`（command/exitCode/status/timestamp/artifactRef）
- 新增 `.ai/scripts/evidence.ps1`：Evidence 读写与校验
- 改 `role-context.md`：tester 只做用例设计与失败分析
- 改 `workflow-manager.md` Evaluate 段：**无 Evidence 不得标 done**
- 改 `agent-output-standard.md`：输出增 Evidence 段
- 出口：一个真实任务的所有 gate 均有可复跑证据；伪造证据能被复跑识破
- 回滚：Evidence 为附加门禁，移除脚本即回到旧流程

### Phase 3 Context Engineering

- 建 `.ai/context/{project,domain,frontend}/`：从 knowledge **抽取索引与摘要**，正文引用 knowledge，不复制
- 每个 Task 生成 `.ai/runtime/tasks/<TASK>/context.md`（bounded）
- Dispatch Envelope 增 `context` 字段；`file-dispatch-runtime.md` 收件箱同步
- 出口：子 Agent 输入体积下降；缺失 context 时 `DISPATCH_INVALID`
- 回滚：`context` 字段可空，回退为直接读 knowledge

### Phase 4 Hook

- `.ai/hooks/pre-task.ps1`：Task/scope/acceptance/validation 完整性
- `.ai/hooks/pre-edit.ps1`：文件是否在 include、是否触碰 exclude、是否高危
- `.ai/hooks/post-edit.ps1`：git diff vs scope、SQL/API/依赖变更检测
- `.ai/hooks/pre-review.ps1`：Evidence 齐备性
- `.ai/hooks/verify/`：调用 Phase 2 的验证脚本
- 语义：退出码非 0 = 阻断并记事件；需要重新规划时交回 WM
- 出口：越界改动被拦截；Hook 不产生业务决策
- 回滚：Hook 脚本独立，禁用不影响主流程

### Phase 5 Task / Dispatch / State 统一 + Skill Playbook

- 定版 V6 Task Schema（`specRef/context/dependencies/acceptance/validation/output/forbidSpawn`）
- Dispatch Envelope 定版（增 `tools`）
- 新增 `.ai/skills/` 流程 Playbook：`create-rest-api`、`database-migration`、`create-integration-test`、`security-review`、`ui-review`
- 能力型全局技能**保留**，映射关系改为"Playbook 引用能力技能"
- 出口：新任务用 V6 schema；旧 Markdown Task 仍可执行
- 回滚：模板版本号回退

### Phase 6 MCP Tool Gateway + Stale V6 + AGENTS 收口

- `.ai/mcp/registry.yaml` + `permissions.yaml`：声明工具与按角色权限（默认 deny）
- 先只做"白名单 + 检测 + 记录"，不接真实 MCP server
- State `stale` 扩展为 `context/spec/evidence` 三类，接入现有 cascade
- `AGENTS.md` 增量收口（只加摘要与入口，不搬走 always-on 规则）
- 出口：`tools.allowed` 与实际调用一致；高危工具默认拒绝；design/spec 变更后下游 stale
- 回滚：声明式文件可删除

---

## 六、风险

| 风险 | 影响 | 对策 |
|------|------|------|
| Hook 没有 runtime 强制力 | 误以为"机器强制"，实际仍是纪律约束 | 明确 Hook = 脚本 + 编排器调用；退出码阻断；不宣称 runtime 级强制 |
| 把规则搬出 AGENTS.md | 唯一自动注入的上下文丢失，遵循度下降 | AGENTS.md 只做增量，保留 always-on 核心；只把**参考知识**移入 context |
| Spec 与 docs 双写 | 文档漂移、维护成本翻倍 | 明确边界：specs 放契约，docs 放执行记录；同一事实只存一处 |
| acceptance.yaml 引用不存在的测试 | 产生假证据 | 验收命令必须可执行且可复跑；不存在的命令判 FAIL |
| Context 裁剪过度 | 子 Agent 缺关键信息导致返工 | Task Context 必含 objective/scope/acceptance/相关源码；加完整性检查 |
| 新旧 schema 并存 | 编排器读错版本 | State/Task 顶部加 `schemaVersion`；旧版本走兼容分支 |
| 目录挪动打断引用 | 90+ taskRef、61 个 docs 目录失效 | 本次明确不挪 `.ai/tasks`、`.ai/state`、`.ai/docs` |
| MCP 当前不可用 | 引入无法运行的基础设施 | MCP 后置为声明式，Phase 6 前不建运行时 |
| Dots provider 投递缺陷 | Context 包无法随 spawn message 到达 | Context 包必须写入 `.ai/dispatch/inbox-<dispatchId>.md`，文件收件箱仍是主通道 |
| 验证并行竞争 | mvn/H2 target 冲突导致假失败 | 保持 V15.3：验证串行，由主线程/Verification Engine 统一执行 |
| 角色扩张 | 与 2026-08-14 的 4 类收敛冲突 | 不新增角色，用 taskType + 工具权限表达 |
| 契约类文档被 gitignore | Spec/acceptance 若放 `.ai/docs/` 下将不入库，无法跨会话追溯 | `.ai/specs/` 明确不加入 `.gitignore`；或在 `.gitignore` 中放行 `.ai/specs/` |

---

## 七、验收标准

迁移整体完成需同时满足：

- [ ] `.ai/scripts/verify-loop.ps1` PASS（含新增 V6 校验项），退出码 0
- [ ] 至少 1 个真实中型任务端到端走通 V6：Spec → Task → Dispatch(context) → Implement → Verify(Evidence) → Review → Accept
- [ ] 所有 gate 标 done 时，`.ai/runtime/evidence/<TASK>/` 下存在对应证据，含 `command/exitCode/status/timestamp/artifactRef`
- [ ] 无任何 knowledge 被全文复制进 context（抽查无重复正文）
- [ ] 现有 4 类角色、WM 唯一调度权、禁止 Agent 套娃、文件收件箱投递**均未改变**
- [ ] 旧 State/Task 无需迁移即可被编排器读取（向后兼容）
- [ ] 任一阶段可独立回滚，且回滚不影响已完成任务
- [ ] `.ai/tasks` / `.ai/state` / `.ai/docs` 路径未发生挪动
- [ ] 未删除任何现有 Skill 或 knowledge 文件

---

## 八、明确不采纳项（防止无意义重构）

| V6 建议 | 不采纳原因 | 替代 |
|---------|-----------|------|
| 13 个角色 | 与 2026-08-14 刚完成的 4 类收敛冲突，扩角色增加调度复杂度 | 4 类角色 + taskType + 工具权限 |
| `.ai/tasks/active|archive` 分目录 | 打断 94 个 Task 与 90+ taskRef 引用 | 保持扁平 |
| `.ai/runtime/state/` 取代 `.ai/state/` | 打断 24 个 State 与全部交叉引用 | 保留 `.ai/state/`，runtime 只放 evidence/tasks/events |
| 删除能力型 Skill 改名 Playbook | 全局技能是第三方资产，删改名成本高收益低 | 新增项目内 Playbook 薄层，引用能力技能 |
| 立即接入 MCP server | 当前 runtime 无 MCP 配置，无法验证 | 先声明式 registry + 权限，Phase 6 再评估 |
| Hook 作为独立决策层 | 会架空 WM 唯一调度权 | Hook 只检测/阻断/记录，重新规划交回 WM |
| 把所有 knowledge 迁成 context | 复制会产生双份事实源 | context 只放裁剪视图与索引，正文引用 knowledge |

---

## 九、待用户裁决（≤3）

| 编号 | 问题 | 影响 | 建议方案 | 用户裁决 |
|------|------|------|----------|----------|
| P1 | Spec 契约放 `.ai/specs/SPEC-xxx/`，执行记录留 `.ai/docs/<task-id>/`，还是全部并入 docs？同时确认：`.ai/docs/` 当前被 gitignore，契约类文档是否必须入库？ | 决定 document-management、`.gitignore` 与历史引用是否需要大改 | 分离存放：specs 放契约且入库（`.gitignore` 放行 `.ai/specs/`），docs 放执行记录维持本地化；task-id 与 SPEC-xxx 1:1 映射 | 待确认 |
| P2 | Task/State 是否升级为 YAML 结构化（V6 schema）？ | 影响 94 个历史 Task、24 个 State 的兼容策略 | 新增 YAML 模板 + `schemaVersion`，历史 Markdown 保持可执行，不强制迁移 | 待确认 |
| P3 | Hook 与 MCP 本期是否落地？ | 决定 Phase 4/6 是否排期 | 本期只做 Phase 1-3（Spec + Verification/Evidence + Context），Hook 用轻量脚本试点，MCP 暂缓 | 待确认 |

> 用户确认后：初始化 `.ai/state/20260908-v6-upgrade.yaml`，按阶段创建 TASK 并派发；未确认前不做任何现有规则改动。
