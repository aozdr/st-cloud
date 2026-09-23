# TASK-20260923-team-search-watch-security-review

独立审查 `tsw-code-r7` 的搜索、关注和通知安全边界。只读代码与当前需求设计，检查可信租户/主体、团队与目录 ACL、陈旧索引、签名游标、异步投递及通知失权脱敏，逐项给出源文件依据。不得修改产品代码、State 或其他评审文档。

写入白名单：`.ai/docs/20260923-team-search-watch/security.md` 与 `.ai/runtime/results/DISPATCH-20260923-TSW-SEC-A1.json`。结论必须绑定 `tsw-code-r7`；未解决高风险问题不得建议 `SECURITY_REVIEW` 通过。
