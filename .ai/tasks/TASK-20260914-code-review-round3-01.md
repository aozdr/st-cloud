# TASK-20260914-code-review-round3-01

## 角色

主线程 Workflow Manager；执行 P0 规范对象候选清理及数据库 schema。

## 目标

修复四条规范对象失败清理路径的删除竞态，增加持久化候选、宽限期回收和删除前三重安全检查。

## Include

- `st-core` 孤儿候选实体、mapper、service、定时任务。
- `UploadServiceImpl`、`ArchiveServiceImpl`、`TextFileServiceImpl`、`EditorCallbackServiceImpl` 调用改造。
- `docker/mysql/init/42_file_orphan_candidate.sql` 与 H2 schema。
- 对应单元/集成测试和中文核心逻辑注释。

## Exclude

- 普通下载和非规范临时 merge 对象清理。
- 生产迁移、历史对象批量扫描。

## 完成证据

- P0-01 至 P0-05 测试结果。
- H2 schema consistency 与 MySQL schema compare 结果。
