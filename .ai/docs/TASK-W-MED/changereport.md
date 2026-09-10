# Change Report — TASK-W-MED（W3 状态裸数字枚举化）

## 背景

全量 Code Review W3（Primitive Obsession）：分享/团队空间/邀请/角色/用户/租户/同步根/分片/文件对象/事件 Outbox 状态以裸数字散落于 9 个文件，无枚举语义，易错写且无法自解释。任务：为上述状态域建立枚举并替换裸数字，行为等价。

## 输入

- TASK：`.ai/tasks/TASK-W-MED.md`（dispatchId w-med-001）
- W3 清单：`.ai/docs/20260814-project-code-review/standards.md`
- 数字映射基准：各实体注释与既有代码（`FileShare.status` 0-已取消/1-有效；`TeamSpace.status` 0-禁用/1-正常；`TeamInvite.status` 0-已撤销/1-有效；`TeamRole.status` 0-停用/1-启用；`SyncRoot.status` 0-启用/1-暂停；`FileChunk.status` 0-待上传/1-已上传/2-已合并；`FileObject.status` 0-正常/1-已删除；`event_log.status` 0-待投递/1-已投递/2-投递失败）

## 分析

按「枚举 code 与原数字一致、不改变任何状态语义/数据库结构」执行。新枚举统一采用既有 `UploadStatus` 模式（`@Getter` + `code/desc` + `fromCode`，中文注释）。st-admin 已依赖 st-auth，故直接复用 `st-auth` 的 `UserStatus`，避免重复定义。

## 决策与改动

### 新增枚举（10 个）

| 模块 | 枚举 | 值 |
|------|------|-----|
| st-share | `ShareStatus` | CANCELLED(0)/ACTIVE(1) |
| st-team | `TeamSpaceStatus` | DISABLED(0)/NORMAL(1) |
| st-team | `InviteStatus` | REVOKED(0)/ACTIVE(1) |
| st-team | `RoleStatus` | DISABLED(0)/ENABLED(1) |
| st-auth | `UserStatus` | DISABLED(0)/NORMAL(1) |
| st-auth | `TenantStatus` | DISABLED(0)/NORMAL(1) |
| st-sync | `SyncRootStatus` | ACTIVE(0)/PAUSED(1) |
| st-core | `FileChunkStatus` | PENDING(0)/UPLOADED(1)/MERGED(2) |
| st-core | `FileObjectStatus` | NORMAL(0)/DELETED(1) |
| st-core | `EventOutboxStatus` | PENDING(0)/SENT(1)/FAILED(2) |

### 裸数字替换

- `ShareServiceImpl`：createShare `setStatus(1)`→ACTIVE、cancelShare `setStatus(0)`→CANCELLED、validateShareAccess `getStatus()==0`→CANCELLED
- `TeamServiceImpl`：空间 `setStatus(1)`/`getStatus()!=1`→TeamSpaceStatus.NORMAL；邀请 `setStatus(1/0)`/`getStatus()==0`→InviteStatus.ACTIVE/REVOKED；角色 `setStatus(1)`、预设角色/新建角色 `toRoleVO(...,1,...)`→RoleStatus.ENABLED
- `AuthService`：租户 `setStatus(1)`/`getStatus()!=1`→TenantStatus.NORMAL；用户 `setStatus(1)`/`getStatus()!=1`（登录、refresh）→UserStatus.NORMAL
- `SyncServiceImpl`：createRoot `setStatus(0)`→SyncRootStatus.ACTIVE；togglePause 切换 0/1→ACTIVE/PAUSED
- `UserManageServiceImpl`：`request.getStatus()==0`→UserStatus.DISABLED；`setStatus(1)`→UserStatus.NORMAL（复用 st-auth 枚举）
- `UploadChunkManager`：`setStatus(0)`→PENDING；`lt(...,2)/set(...,2)`→MERGED
- `FileObjectServiceImpl`：`setStatus(0)`→FileObjectStatus.NORMAL
- `ReliableEventPublisher`：`setStatus(0)`→EventOutboxStatus.PENDING
- `EventRelay`：`getStatus()==1`→EventOutboxStatus.SENT
- `FileController`：`setStatus(500)`→`HttpStatus.INTERNAL_SERVER_ERROR.value()`

### setRefCount(0/1) 语义常量化

- `UploadServiceImpl`（新增 `REF_COUNT_INITIAL=1`）：新建/秒传/分片初始化 3 处
- `ArchiveServiceImpl`（新增 `REF_COUNT_NONE=0`）：解压文件夹/文件 2 处
- `FileServiceImpl`（新增 `REF_COUNT_NONE=0` / `REF_COUNT_INITIAL=1`）：个人/团队创建文件夹与复制 4 处

## State Delta

- 新增 artifacts：10 个枚举类 + `.ai/docs/TASK-W-MED/changereport.md`
- 修改文件：`ShareServiceImpl`、`TeamServiceImpl`、`AuthService`、`SyncServiceImpl`、`UserManageServiceImpl`、`UploadChunkManager`、`FileObjectServiceImpl`、`ReliableEventPublisher`、`EventRelay`、`UploadServiceImpl`、`ArchiveServiceImpl`、`FileServiceImpl`、`FileController`
- exitCriteria 支持项：W3 枚举替换完成；`mvn -q -pl st-core,st-share,st-team,st-auth,st-sync,st-admin -am test` EXIT=0（全绿）

## 验证

- 编译 + 测试：上述 mvn 命令 EXIT=0（含 st-auth/st-share/st-team/st-admin 新增集成测试、st-core 全量测试）
- rg 复核：ShareStatus/TeamSpaceStatus/InviteStatus/RoleStatus/UserStatus/TenantStatus/SyncRootStatus/FileChunkStatus/FileObjectStatus/EventOutboxStatus 各域裸数字均已替换；`setRefCount(0/1)` 与 `setStatus(500)` 无残留
- 枚举 code 与数据库/实体注释一致；未改任何状态值、未动 SQL/表结构

## 风险

- 行为等价替换，理论上无语义变化；`toRoleVO` 预设角色 status 参数由字面量 1 改为 `RoleStatus.ENABLED.getCode()`，运行期值相同
- 遗留（不在本 TASK 定版清单）：`ShareServiceImpl` 中 `FileNode.status/nodeType` 裸数字属 H6 域（既有 NodeStatus/NodeType 枚举替换），需主线程另行派发

## 下一步

主线程 Evaluate：抽查枚举映射，合并 changereport，进入 Code Review 环节；H6（NodeStatus/NodeType/UploadStatus 残留裸数字）可并行派发独立 TASK。

## 变更影响

- 仅 Java 常量引用替换，接口契约、数据库、DTO、前端无变化
- st-admin 新增对 `st-auth` enums 包依赖（模块本身已依赖 st-auth，pom 无需改动）
