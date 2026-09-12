# TASK：Phase 4 UploadSession CAS 状态机

- Task ID：`TASK-20260912-second-review-fix-04`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：本轮 `design.md`、`testcases.md`；角色：executor，taskType：implement

## 目标与 include

UploadSession 成为 merge/abort 权威状态；所有状态转换使用带预期状态的条件更新并检查 affectedRows。仅 CAS 获胜者执行 S3 complete/abort 与 cleanup；MERGING 时 abort 返回 CONFLICT。合并成功的 DB finalize 与 MERGING→COMPLETED 在同一短事务内。覆盖 direct/relay、过期、失败重试。允许修改 `st-core/src/main/java/com/stcloud/core/mapper/UploadSessionMapper.java`、`service/impl/UploadServiceImpl.java`、`service/impl/upload/{UploadManager,UploadCommitManager,UploadStorageManager,RelayBufferManager}.java`、直接相关的 `st-core/src/test/**`，以及本 dispatch 独立结果文件。中转超时与错误清理必须使用同一 CAS 门禁，不能直接中止 S3。

## exclude 与验收

禁止 S3 网络调用进入 DB 事务、schema/API/UI 变化。STATE-01～04 使用真实多线程与 CountDownLatch，通过后方可进入 Phase 5；补偿失败须可检索日志。
