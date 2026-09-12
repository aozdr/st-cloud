# TASK：Phase 6 Archive ZIP 输入上限

- Task ID：`TASK-20260912-second-review-fix-06`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：本轮 `design.md`、`testcases.md`；角色：executor，taskType：implement

## 目标与 include

`ArchiveSafetyProperties` 新增默认 1GB 的 `maxArchiveInputSize`，支持 `stcloud.archive.max-archive-input-size`；ZIP 下载使用有界流复制，超限即 `FILE_TOO_LARGE` 并清理临时文件。允许修改 `st-core/src/main/java/com/stcloud/core/config/ArchiveSafetyProperties.java`、`service/impl/ArchiveServiceImpl.java`、必要的现有配置文件和直接相关的 `st-core/src/test/**`，以及本 dispatch 独立结果文件。

## exclude 与验收

禁止 schema/API/UI 变化和 Archive 无关重构。ARCHIVE-01～02 通过：超过 1MB 的 2MB 输入未完整落盘，临时文件删除，不进入 summarizeArchive。阶段测试通过后进入 Phase 7。
