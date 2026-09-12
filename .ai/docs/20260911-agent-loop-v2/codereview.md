# Agent Loop V2 Code Review

固定点：`HEAD b97faa5854596b5e9d9a4af64f30517d209db501`。审查范围为当前工作树中的 `AGENTS.md` 与 `.ai/**` 变更。

## Standards

初审结论：`FAIL`。

- P1：`loopctl evaluate` 未完整处理 `fail/blocked`，可能错误标记为 done。
- P1：证据引用未校验真实存在，dispatch attempt 未在 Evaluate 后结束。
- P1：`verify-loop` 因旧 TASK 的 `codereview.md` 悬空引用退出 1。
- P1：含中文的 Loop V2 PowerShell 文件缺 UTF-8 BOM，不兼容 Windows PowerShell 5.1。
- 判断项：`loop-state-model.md` 重复 DAG 说明，且一处图示容易误读 ACCEPT/KNOWLEDGE 顺序。

修复状态：前四项已修复并进入复审；文档去重项已改为引用 canonical 定义，等待复审确认。

## Spec

初审结论：`FAIL`。

- P0：任意非 `skip` outcome 都可能进入 done。
- P0：Evaluate 未绑定 `dispatchLedger` 当前 attempt，伪造或旧 dispatchId 可推进 State。
- P1：State/Dispatch 未真正执行 JSON Schema 的结构约束。
- P1：缺 blocker repair、Dispatch 生命周期恢复、Phase 6 迁移与 CI 实现。
- P2：若只按三个实现 TASK 的 include 看，部分现行知识和旧 State 迁移未被白名单覆盖；但这些文件属于用户已确认的总设计范围，后续用补充 TASK 明确。

修复状态：P0/P1 已实现并新增回归测试；Phase 6 已补充统一门禁、CI workflow、可 dry-run 迁移器并迁移全部 running/incomplete V1 State，等待复审。

## 复审

第二轮仍为 `FAIL`，新增发现已修复：

- `complete` 现已拒绝未结束 dispatch，并校验 done artifact 的真实 ref。
- hook 已覆盖删除状态，并拒绝暂存区与工作树在控制文件上的版本漂移。
- migration 已支持 ShouldProcess、全量预检、先验证后写与失败回滚。
- TASK 计划产出使用 `[planned-output]` 显式标记，不再与 cross-ref 的已存在引用语义冲突。
- proposal 的证据已强制等于当前 attempt 的 resultRef，并新增反例。
- Phase 6 新增迁移、hook 绕过、CI 自触发与旧协议活跃入口测试。

最终复审结论：`PASS`，validated revision 为 `loop-v2-r3-20260912`。

- Standards：无 P0/P1；最后一项 checklist 口径已同步为完整 `run-loop-gate.ps1`。
- Spec：proposal/result/ledger 绑定、Phase 6 迁移与 CI 行为、统一门禁均符合确认设计。
- 两路 reviewer 均为只读独立审查，未与实现角色重合。
