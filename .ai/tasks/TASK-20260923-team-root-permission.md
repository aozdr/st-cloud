# TASK-20260923-team-root-permission

## 目标

修复团队空间“空间根目录权限”保存失败。根目录以节点 ID `0` 表示；管理员可读取、覆盖本空间根规则，规则对本空间正常节点及其后代生效，不影响其他空间。

## 范围

- 根目录 ID `0` 的权限读写校验。
- 权限规则查询、覆盖删除按空间隔离；正常节点权限解析包含本空间根规则。
- 受影响的权限集成测试和 fresh 核权测试。

## 验收标准

1. 管理员在空间根目录保存规则并重新打开可读回，非管理员不可写。
2. 根规则对本空间节点生效；损坏的父链不得借根规则放权。
3. 同租户不同空间的根规则读写互不影响，跨空间普通节点继续拒绝。
4. 受影响测试和构建通过，无数据库表结构变更。

## 写入范围

- `st-team/src/main/java/com/stcloud/team/service/impl/TeamServiceImpl.java`
- `st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java`
- `st-team/src/test/java/com/stcloud/team/service/TeamServicePermissionIntegrationTest.java`
- `st-team/src/test/java/com/stcloud/team/service/FolderPermissionFreshTest.java`
- `.ai/docs/20260923-team-root-permission/**`
- `.ai/state/20260923-team-root-permission.yaml`
- `.ai/knowledge/api-reference.md`
- `.ai/knowledge/business-domain.md`
- `.ai/tasks/TASK-20260923-team-root-permission-review.md`
- `.ai/tasks/TASK-20260923-team-root-permission-security.md`
- `.ai/runtime/dispatches/20260923-team-root-permission-*.json`
- `.ai/runtime/results/DISPATCH-20260923-root-permission-*.json`

## 排除范围

- 数据库迁移、角色模型重设计、其他空间文件操作、生产部署及业务数据清理。
