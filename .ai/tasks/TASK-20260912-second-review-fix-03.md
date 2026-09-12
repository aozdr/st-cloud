# TASK：Phase 3 Upload Init 数据库事务

- Task ID：`TASK-20260912-second-review-fix-03`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：本轮 `design.md`、`testcases.md`；角色：executor，taskType：implement

## 目标与 include

在独立 `UploadInitCommitManager` Bean 的单个 `@Transactional` 方法内完成 replacement FOR UPDATE、权限和类型二次校验、version snapshot、node/session/chunk 写入。S3 init 与 DB 失败后的 best-effort abort 留在事务外。仅允许修改 `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`、`service/impl/upload/UploadInitCommitManager.java` 及其必要 command/result 类型、直接相关的 `st-core/src/test/**`，以及本 dispatch 独立结果文件。

## exclude 与验收

禁止 `REQUIRES_NEW`、事务内 S3/大文件 IO、schema/API/UI 变更和其他 Phase 修改。INIT-TX-01～03 通过：新建和替换任一 chunk 批次失败全回滚，S3 init 后 DB 失败调用 abort 并记录补偿失败。运行本阶段定向测试并记录结果，未通过不得进入 Phase 4。
