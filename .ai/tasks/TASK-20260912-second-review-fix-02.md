# TASK：Phase 2 同名检查 scope

- Task ID：`TASK-20260912-second-review-fix-02`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：本轮 `design.md`、`testcases.md`；角色：executor，taskType：implement

## 目标与 include

废弃无 scope 的同名查询，个人按 tenant+owner+personal space，团队按 tenant+space 检查。逐项覆盖 create/rename/move/copy、resolveNameConflict、check/init/simple、restore、archive extract 和 team 调用链。

仅允许修改 `st-core/src/main/java/com/stcloud/core/mapper/FileNodeMapper.java`、`service/FileService.java`、`service/impl/{FileServiceImpl,UploadServiceImpl,RecycleBinServiceImpl,ArchiveServiceImpl,NewFileServiceImpl}.java`、必要的 `service/impl/upload/UploadCommitManager.java`、直接相关的 `st-core/src/test/**`，以及本 dispatch 独立结果文件。若实际调用链证明 `st-team` 需改动，先返回 scope 扩展请求，不自行越界。

## exclude 与验收

禁止改数据库 schema、公开 HTTP 路由或响应、UI、无关服务。SCOPE-01～04 通过；同 scope 仍冲突；数据库唯一索引保持兜底。运行本阶段定向测试并记录结果，未通过不得进入 Phase 3。
