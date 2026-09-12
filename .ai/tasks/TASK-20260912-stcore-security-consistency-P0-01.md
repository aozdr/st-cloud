# TASK-20260912-stcore-security-consistency-P0-01

## 任务类型

P0-01 个人/团队资源授权边界实现。

## 背景

Review 发现 `FileServiceImpl.getNodeByIdAndOwner` 对 `space_id>0` 的团队节点直接放行，导致 generic personal API 的最终服务层防线缺失。团队操作必须通过显式 `spaceId` + 团队 ACL 入口；个人 API 必须拒绝团队节点。下载、版本、回收站及通用文件控制器的调用链不能绕过该边界。

## 前置条件

- 技术设计已由用户确认：`.ai/docs/20260912-stcore-security-consistency/design.md`。
- 测试门禁已完成：`.ai/docs/20260912-stcore-security-consistency/testcases.md`。

## 允许写入

- `st-core/src/main/java/com/stcloud/core/service/impl/FileServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/DownloadServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/VersionServiceImpl.java`
- `st-core/src/main/java/com/stcloud/core/service/impl/RecycleBinServiceImpl.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/FileServicePermissionIntegrationTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/DownloadServiceImplTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/VersionServiceImplTest.java`
- `st-core/src/test/java/com/stcloud/core/service/impl/RecycleBinServiceImplTest.java`
- `.ai/runtime/results/DISPATCH-20260912-P0-01-01.json`

允许在上述测试路径不存在时选择现有同职责测试类；不得为了测试大范围改造测试装配。

## 禁止写入

- `st-core/src/main/java` 中除上述文件外的文件。
- `st-team`、`st-web`、`st-desktop`、数据库 SQL、Loop State、共享 changereport。
- 不得删除或重写用户已有测试，仅添加/最小修改相关用例。

## 实现要求

1. `getNodeByIdAndOwner` 语义改为严格 personal-only：节点必须是当前用户 owner 且 `space_id IS NULL OR space_id<=0`，团队节点统一拒绝。
2. 个人 detail/rename/move/copy/delete/text-content/download/stream/ZIP/version/recycle 等调用链必须最终经过 personal-only 校验。
3. 不改变显式团队方法 `validateTeamNode/getTeamNodeById` 的入口语义；不得在 st-core 引入 st-team 依赖。
4. 下载服务不得保留“团队由外层控制所以 generic 服务放行”的绕过逻辑；generic ZIP 的每个根节点也必须个人-only。
5. 个人节点 owner 不匹配、团队节点、缺失节点分别保持既有错误语义或明确的 `FORBIDDEN/FILE_NOT_FOUND`，并补充中文安全边界注释。
6. 测试至少覆盖个人 owner 成功、他人个人节点失败、同租户管理员/dataScope 仍失败、团队节点在 generic detail/rename/move/copy/delete/version/download/recycle 失败，以及显式团队读取不被误伤（可在已有 Team 测试中作为后续任务证据）。

## 验收标准

- generic personal API 无法读写任何团队节点；相关负向测试失败时应在修复前复现并在修复后通过。
- team 节点不因 owner 恰好等于当前用户而被 generic 入口放行。
- 个人 API 的路径、版本、下载和回收站调用均不再依赖隐含的外层 team 权限。
- 只修改允许路径，独立结果文件包含事实、证据、`criterionProposal.id=IMPLEMENTED`，不得修改 Loop State。

## 验证

- `mvn -pl st-core -am -DskipTests compile`
- 相关权限/服务测试；若全模块测试受环境或既有失败影响，记录精确失败证据，不伪报通过。
- `git diff --check` 与 `git diff --name-only` 核对范围。

## 结果契约

结果写入 `.ai/runtime/results/DISPATCH-20260912-P0-01-01.json`，包含 `dispatchId`、`taskId`、`status`、`artifactRefs`、`criterionProposal`、`blockerProposals`。
