# TASK：TASK-006 测试体系补充

> 依据《st-cloud-Codex-execution-tasks.md》TASK-006。优先级 P2。

## 元信息
- Task ID: `TASK-006`
- 关联 State: `.ai/state/20260811-codex-tasks-execution.yaml`
- 归属 Agent: tester

## 目标
补充文件/并发/权限三类测试，提升覆盖率（当前全项目仅 7 个测试类）。

## 修改范围
- 文件测试：秒传、分片上传、断点续传、文件移动、删除恢复
- 并发测试：多用户同时上传、同文件同时上传、容量竞争
- 权限测试：用户权限、团队权限、分享权限
- 落位于 st-core / st-search / st-sync / st-team 的 src/test

## 禁止修改范围
- 不改业务代码（纯测试补充；发现缺陷时按对应 TASK 修复）
- 不引入外部基础设施依赖（测试用 H2/Testcontainers 与现有 AbstractIntegrationTest 一致）

## 验收标准
- 三类用例全部通过
- 现有测试全绿
- 关键路径（上传/删除/权限）覆盖率明显提升

## 测试要求
- 复用 `.ai/docs/20260811-codex-tasks-execution/testcases.md` 用例清单

## 输出要求
- 完成后产出 `.ai/docs/<task-id>/changereport-t006.md` 与测试报告