# 迭代文档补记（share-expiry）

> 记录日期：2026-09-10（事后核验，非当轮产出）

本迭代在 2026-08-14 全库 spec 审查（`.ai/docs/20260814-project-code-review/spec.md` 第 2.1 节）中被判定存在"文档承诺未实现"漂移。
截至 2026-09-10 复核，功能缺口已全部关闭：

| 原缺口 | 当前状态 | 证据 |
|--------|---------|------|
| 创建/更新分享无未来时间校验 | 已实现 | `ShareServiceImpl` 校验 `expireAt` 必须晚于当前时间 |
| 无 `clearExpireAt` 清除过期 | 已实现 | `ShareServiceImpl` 按 `clearExpireAt` 优先级处理 |
| 管理页无"已过期"展示 | 已实现 | `ShareManagePage.tsx` 过期徽标 |
| 前端提交 UTC 时间 | 已实现 | `ShareDialog.tsx` 改为本地时间格式 |
| `ShareAccessVO.isExpired` 死代码 | 已清理 | 字段已移除 |
| 无 st-share 测试、H2 缺 `file_share` | 已补齐 | `st-share/src/test`、`st-core/src/test/resources/schema.sql` |

本目录缺少当轮的 `codereview.md` / `security.md` / `testreport.md`。**不补造历史记录**：
如需正式评审留痕，应在功能当前状态上重新执行一轮评审与测试，而不是回填当时的结论。
