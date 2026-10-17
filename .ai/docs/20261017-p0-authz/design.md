# 20261017 P0 授权问题修复

## 目标

修复分模块评审中确认的 4 个 P0 授权缺陷，并为每个修复补一组可回归的测试用例。本次不执行测试（按用户要求），只保证测试源码编写完整、可编译。

## 范围

### include

| 编号 | 模块 | 修复点 | 主要文件 |
| --- | --- | --- | --- |
| P0-1 | st-preview | 预览入口补资源级鉴权：个人节点必须 owner，团队节点走 `TeamFileAccessPolicy.canView`，策略缺失时拒绝 | `PreviewServiceImpl` |
| P0-2 | st-share | 分享子树由 path 字符串前缀改为 parentId 祖先链 + 同租户/同归属范围判定 | `ShareServiceImpl` |
| P0-3 | st-core | OnlyOffice 回调凭证必须携带 `key` claim 并与请求 key、目标 nodeId 绑定 | `EditorCallbackServiceImpl` |
| P0-4 | st-team | `manage_members` 不再映射为管理员等级；成员管理走独立门禁；禁止改自己角色、禁止非 owner/管理员授予 `role=0` | `FolderPermissionService`、`TeamServiceImpl` |

附带（与 P0-4 同一入口，必须一起改否则会放宽原行为）：`removeMember` 禁止移除空间拥有者。

### exclude

- P1 未处理：所有权移交未加空间写锁、`team_member` 唯一键不含 deleted 导致移除后无法重新加入。
- 其余 P1/P2（秒传声明值、物理删除竞态、替换上传中断、搜索 ACL、审计缺失、前端 SW/上传续传、桌面端落盘等）不在本次范围。
- 不改数据库结构与对外 API；不新增 DTO 字段。

## 规则与兼容性

- **无 API/DDL 变更**。返回码沿用既有 `ResultCode`（403 / 4004）。
- **Long ID 序列化不变**：实体与内部调用仍是 `Long`，对外 JSON 继续由全局 Jackson 配置序列化为字符串；本次未改任何响应结构。
- P0-2 的祖先链判定最多上溯 64 层，异常 `parentId` 环返回拒绝而不是死循环。
- P0-3 要求回调 JWT 顶层含 `key`：OnlyOffice 回调 body 必然带 `key`，浏览器可见的 config token 顶层没有，因此天然被区分；这是本次修复的判别点。
- P0-4 后，只有 `manage_settings`/内置管理员/拥有者能进入空间设置、角色 CRUD、文件夹 ACL 等入口；`manage_members` 仍可邀请/移除成员、查看邀请链接、搜索用户。

## 验收标准

1. 他人个人文件的 nodeId 调 `/api/preview/**` 返回 403，且不读取对象存储。
2. 团队 nodeId 在策略拒绝（或策略缺失）时返回 403；策略放行时正常预览。
3. 用自己的 shareCode + 他人 nodeId 调分享下载/列目录返回 3004，且不生成预签名 URL。
4. 用浏览器 config token 调编辑器回调被拒；token 绑定 A 文件时不能重放到 B 文件。
5. 只含 `manage_members` 的自定义角色：不能改自己角色、不能改空间设置、不能授予/邀请 `role=0`、不能移除 owner；仍可邀请普通成员。
6. 空间拥有者仍可授予管理员。

## 测试计划

| 测试类 | 覆盖 |
| --- | --- |
| `st-preview/.../PreviewAccessControlIntegrationTest` | P0-1 四条边界（本人 / 他人 / 团队拒绝 / 团队放行） |
| `st-share/.../ShareSubtreeOwnershipIntegrationTest` | P0-2 下载越权、列目录越权、真实子树不误伤 |
| `st-core/.../EditorCallbackIntegrationTest`（新增 2 个用例） | P0-3 config token 无 key claim、跨文件重放 |
| `st-team/.../TeamRoleEscalationIntegrationTest` | P0-4 自我提权、设置越权、授予管理员、移除 owner、正常邀请、owner 授权 |

测试均为 H2 + 真实 Mapper 的集成测试，复用各模块既有测试基类与外置依赖 Mock。
