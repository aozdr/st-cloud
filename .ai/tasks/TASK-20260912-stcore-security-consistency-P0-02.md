# TASK-20260912-stcore-security-consistency-P0-02

## 任务类型

P0-02 个人/团队父子树 scope 一致性。

## 前置条件

- `.ai/docs/20260912-stcore-security-consistency/design.md` 已确认。
- `.ai/docs/20260912-stcore-security-consistency/testcases.md` 已完成。
- P0-01 已集成并通过其边界测试。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/service/FileService.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/UploadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/NewFileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/controller/FileController.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServiceFlowIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/UploadStateMachineIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/RelayUploadIntegrationTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P0-02-01.json`

## 禁止写入

- `st-team`、`st-web`、`st-desktop`、数据库 SQL、Loop State、共享报告。
- 不修改 P0-01 之外的授权语义，不做 FileServiceImpl 拆分。

## 实现要求

1. 增加并复用 `validatePersonalParent` 与 `validateTeamParent`；根目录允许 `0/null`，非根父目录必须是 folder、NORMAL 且与目标 scope 一致。
2. `createFolder/copy/move`、新建空白文件和个人上传只能进入个人父目录；团队服务调用只能进入相同 `spaceId` 父目录，不能依赖通用 `validateAndGetParentPath` 猜 scope。
3. 团队节点 child 必须保留同一 spaceId；个人节点 child 必须 owner 为当前用户且 spaceId 为空/0。
4. 对 `parentId` 缺失、团队父目录、他人个人父目录、跨团队父目录、文件父目录和回收目录补负向测试。
5. 校验失败必须在任何数据库写入、配额变更或外部对象操作前发生；保留既有事务边界。

## 验收标准

- 任意 child 与 parent personal/team scope 一致。
- generic personal create/copy/move/upload/new-file 无法把节点挂入团队或他人目录。
- team create/copy/move/upload/new-file 无法跨 spaceId。
- 根目录行为明确且测试覆盖；异常数据不会被静默改写。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 运行相关集成测试并记录失败证据。
- `git diff --check`、`git diff --name-only`。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P0-02-01.json`，包含 `criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。
