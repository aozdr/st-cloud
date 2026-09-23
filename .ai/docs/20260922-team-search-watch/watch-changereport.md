# 文件关注捕获与通知可靠性变更说明

## 本次修复

团队新建目录经 MyBatis 租户自动填充写入 `file_node` 后，内存 `FileNode` 可能仍没有 `tenantId`。同步事件现在只使用已认证的 `UserContext.tenantId` 补齐该内存快照；随后捕获监听器在同一文件写事务中按 `(tenant_id, id)` 读取数据库快照并再次比对租户。没有可信认证租户或数据库快照不一致时抛异常并回滚，未使用默认租户或跳过关注捕获。

## 覆盖入口与边界

- `FileServiceImpl.createFolder` 和 `createTeamFolder` 都在节点插入后调用 `ReliableEventPublisher.publishSyncChange`，因此使用同一可信快照路径。
- 个人与团队新建均允许在没有订阅时正常提交；团队存在有效订阅时产生一条候选投递；跨租户节点在写 Outbox 前拒绝。
- 通知 target 使用 `(tenant_id, user_id, id)` 查询归属。撤销成员资格后，读取历史 FILE_CHANGE 通知会清空 `refId`、`nodeId`、`parentId`、`spaceId` 与正文，并返回不可用状态。

## 验证证据

- 静态检查：`git diff --check -- st-core st-team st-sync` 退出码 0。
- 已补充 H2 集成测试：`newPersonalAndTeamNodes_useTrustedTenantSnapshotForWatchCapture`、`teamMemberPermission_isRecheckedAtDeliveryTime`、`notificationTarget_rejectsAnotherUsersNotificationId`。
- `syncCapture_rejectsNodeFromAnotherTenantBeforeWritingOutbox` 仅检查禁止发布的业务事件类型，避免测试上下文的无关 Spring 事件造成误报；仍断言 Outbox 为零。
- `FileWatchCaptureListenerTest` 的节点构造辅助方法将 `spaceId` 改为可空 `Long`，覆盖个人节点 `spaceId=null` 的测试输入。
- st-team H2 测试 schema 补齐 `event_log` 表和普通状态索引，使团队关注可靠性集成测试能使用真实 Outbox 发布器，不以 mock 绕过事务写入。
- 按本 TASK 约束未运行 Maven；主线程负责串行执行 Maven/H2/schema 验证。
