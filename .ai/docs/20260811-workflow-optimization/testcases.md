# 测试用例：AI 开发流程优化

## 测试范围
verify-loop.ps1 静态校验 / 交叉引用 / 走查演练 / 业务代码不受影响。

## 用例清单
| ID | 用例 | 步骤 | 预期 |
|----|------|------|------|
| TC1 | 静态校验 | 运行 .ai/scripts/verify-loop.ps1 | 全部 PASS，退出码 0 |
| TC2 | 交叉引用 | 检查所有 .ai/… 引用指向真实文件 | 无悬空引用 |
| TC3 | 新文件存在 | 检查 tasks/、decisions/ADR/、两个模板、skill-mapping | 全部存在 |
| TC4 | 走查演练 | 模拟中型任务：WM 生成 TASK → 工程师读 Task → 输出实施计划 → Change Report | 链路可读、无断链 |
| TC5 | 业务代码隔离 | git status 检查 | 无 st-web / st-backend 业务文件被改动 |