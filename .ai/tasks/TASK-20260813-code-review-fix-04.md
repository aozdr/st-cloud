# TASK：TASK-04 RelayUploadIntegrationTest + 全量回归

> 开发前置产物。编码输入只接受本文件。
> 关联 State: `.ai/state/20260813-code-review-fix.yaml`

## 元信息
- Task ID: `TASK-20260813-code-review-fix-04`
- 关联文档: `.ai/docs/20260813-code-review-fix/testcases.md`
- 归属 Agent: tester

## 目标
补齐 Code Review P1：RelayUploadIntegrationTest 缺失。

## 修改范围
- `st-core/src/test/java/com/stcloud/core/service/impl/RelayUploadIntegrationTest.java`（新增）：覆盖 testcases.md TC-001~012（模式判定 / relayChunkSize / pacing / 分片阈值 / 末片 / 权限 / 失败 abort / 超时清理 / 重复 seq 幂等 / 客户端自限速中转）。
- 补充 simpleUpload 限速用例（TC-013，mock StorageService 验证 pacing 生效）。
- 沿用现有 AbstractIntegrationTest 与 S3/mock 抽象，不引入真实基础设施。

## 禁止修改范围
- 不改业务代码（发现缺陷反馈给对应 TASK 修复）
- 不引入真实 S3/RocketMQ 依赖

## 验收标准
- [ ] 新增用例全绿（含 TC-012 客户端自限速、TC-013 simpleUpload 限速）
- [ ] `mvn test -pl st-core -am` 全绿（含 UploadStateMachine / Concurrent / 事件 / 权限回归）
- [ ] st-sync / st-search / st-team / st-common 模块测试不回归

## 输出要求
- 产出 `.ai/docs/20260813-code-review-fix/testreport.md`
