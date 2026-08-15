# TASK：TASK-02 relay-finalize 权限 + 失败 abort + 超时定时清理

> 开发前置产物。编码输入只接受本文件。
> 关联 State: `.ai/state/20260813-code-review-fix.yaml`

## 元信息
- Task ID: `TASK-20260813-code-review-fix-02`
- 关联文档: `.ai/docs/20260813-code-review-fix/design.md` / `testcases.md`
- 归属 Agent: backend-engineer

## 目标
修复 Code Review P1：relay-finalize 无权限校验；失败不 abort S3；超时清理无定时任务且不 abort multipart。

## 修改范围
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`：`relayFinalize` 增加 owner/租户管理员权限校验（与 relayChunk 一致）；catch RuntimeException 时 abort S3 multipart + cleanup 临时文件后重抛。
- `st-core/src/main/java/com/stcloud/core/service/impl/upload/RelayBufferManager.java`：`createSession` 增加 storagePath/s3UploadId 参数；新增定时清理（`@Scheduled` 或独立调度组件，默认 60s 扫描），超时会话 abort S3 multipart + 删临时文件；`@PreDestroy destroy` 对未完成会话尽力 abort。
- `st-core/src/main/java/com/stcloud/core/config/UploadRelayConfig.java`：补充调度间隔配置项（默认 60s）。
- 确认 `@EnableScheduling` 已启用；若未启用，在配置类开启。

## 禁止修改范围
- 不改 UploadManager.claimMerging/handleMergeFailure 既有语义
- 不改事件 Outbox / 下载 / 分享链路
- 不改数据库结构

## 验收标准
- [ ] 非 owner/非租户管理员调用 relay-finalize 返回 PERMISSION_DENIED
- [ ] relay-finalize 失败后 S3 multipart 被 abort、临时文件被删除
- [ ] 超时会话（默认 10 分钟未活跃）被定时任务清理并 abort S3

## 测试要求
- TASK-04 覆盖权限与失败 abort 用例；`mvn compile -pl st-core,st-common -am` 通过
