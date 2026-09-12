# TASK：Phase 7 回归、文案与证据

- Task ID：`TASK-20260912-second-review-fix-07`
- State：`.ai/state/20260912-second-review-fix.yaml`
- 输入：本轮 `design.md`、`testcases.md` 与 Phase 1～6 独立结果；角色：tester，taskType：test

## 目标与 include

六个 Phase 均通过后，统一修正 simpleUpload 的 100MB 错误文案；串行运行计划第 9 节全部门禁，核对无 schema drift，编写 `testreport.md` 和 `changereport.md` 的真实命令、通过数与失败数。允许修改 `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java` 中文案一处、本轮 `.ai/docs/20260912-second-review-fix/{testreport,changereport}.md`，以及本 dispatch 独立结果文件。

## exclude 与验收

禁止扩大功能、更改 schema/API/UI、删除测试或清理现有改动。门禁：`mvn -pl st-core -am test`、`mvn -pl st-team -am test`（无测试则 compile）、Web build、Desktop typecheck/build、SchemaConsistencyTest、`.ai/scripts/compare-schema.ps1`、`git diff --check`。失败须原样记录并交主线程定向修复，不虚报通过。
