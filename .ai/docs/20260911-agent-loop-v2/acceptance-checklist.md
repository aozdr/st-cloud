# Agent Loop V2 验收清单

当前验收 revision：`loop-v2-r3-20260912`。

## Goal 完成标准

1. **移除旧兼容路径：满足。** 当前 Dispatch 只支持 Codex Direct Message；第三方模型与 File Inbox 仅存在于 `.ai/archive/protocols/` 等审计资料。证据见 `design.md`、`changereport.md` 和 `migration-report.md`。
2. **State 严格门禁：满足。** JSON Schema、DAG、证据文件、当前 attempt 与当前 revision 均由 `loopctl.ps1` 校验；统一门禁及反例测试通过。证据见 `testreport.md`。
3. **关键证据绑定 revision：满足。** Code Review、Security Review、Test Pass 均绑定 `loop-v2-r3-20260912`，并已由主线程 Evaluate。证据见 `codereview.md`、`security.md`、`testreport.md` 及对应 runtime result。
4. **Worktree 生命周期可验证：满足。** create/list/complete/abort/reconcile 的 ledger、scope、路径、分支和 SHA 校验已实现，8 组测试通过且 reconcile issues 为 0。证据见 `testreport.md`。
5. **协议唯一事实源：满足。** `AGENTS.md` 只保留入口与硬约束，Dispatch、State、退出标准分别指向唯一事实源，历史协议禁止加载执行。证据见 `knowledge.md`。

## 迁移与影响

- 6 个 `running/incomplete` V1 State 已迁移到 V2，原件保存在 `.ai/archive/state-v1/`。
- 未修改业务代码、数据库结构或业务 API。
- 迁移恢复方式与保守 pending 策略见 `migration-report.md`。

## 用户验收

状态：用户已确认。

- 确认人：`user`
- 确认时间：`2026-09-12T00:37:29.0253604Z`
- 确认内容：接受迁移报告与本验收清单。
- 验收 revision：`loop-v2-r3-20260912`
