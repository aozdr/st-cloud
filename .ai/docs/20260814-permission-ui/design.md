# 前端权限 UI 设计（对齐权限模型重设计）

> 迭代：20260814-permission-ui｜规模：medium｜前置：权限模型后端（DB/BE1/BE2）已完成

## 一、目标

1. 文件夹权限配置 UI 对齐新模型：主体支持 `all`（全体，管理员除外）/ `member`（单用户）/ `role`（角色），权限从单值下拉改为 **9 权限点勾选**（view/upload/download/delete/rename/move/share/manage_members/manage_settings），保存 `permissions` JSON。
2. 分享创建/编辑：展示"可分享权限点"并**禁用超出当前用户权限的项**；`allow_download` 与 download 权限点联动。
3. 角色管理：9 权限点勾选完整对齐（现状已基本支持，做校验与微调）。
4. 后端补充：提供"当前用户对该文件有效权限"接口，供分享权限选择禁用超权项。

## 二、现状（勘察结论）

| 组件 | 现状 | 差距 |
|------|------|------|
| FolderPermissionDialog | 已调用 GET/PUT 文件夹权限接口；单值权限下拉；主体仅 member/role | 缺 all 主体、缺 9 权限点勾选、payload 未带 permissions |
| RoleManageDialog | 已支持 9 权限点勾选 + permissions JSON | 基本对齐，需校验默认值/编辑加载 |
| ShareDialog | 已有 allowDownload 开关 | 缺分享权限点选择（禁用超权） |
| 后端契约 | FolderPermissionRequest.PermissionRule 已含 subjectType(all/role/member) + permissions JSON；TeamRoleRequest 已含 permissions | 缺"当前用户对文件有效权限"接口 |

## 三、方案

### 3.1 FolderPermissionDialog（改造）

- 主体选择：`all`（全体成员，管理员除外）/ `member`（搜索用户）/ `role`（角色下拉，含自定义角色）。
- 权限：9 权限点勾选（Checkbox 组），映射 `permissions` JSON；勾选 upload/download 自动补 view（与后端隐含规则一致，前端提示）。
- 规则列表：展示主体 + 权限点标签；新增规则带 `permissions`；保存 `PUT /team/{spaceId}/folder/{nodeId}/permissions` 的 `rules[].permissions`。
- 删除/全量覆盖沿用现有 setPermissions 语义（先删后建）。

### 3.2 ShareDialog（新增分享权限点区）

- 创建前调用新接口 `GET /share/effective-permissions?fileNodeId=` 获取当前用户对该文件的有效权限集。
- 展示可分享权限点勾选；**不在此权限集内的项禁用**（如用户无 download → 下载勾选禁用）。
- 勾选权限点 → 请求 `permissions` JSON；`allowDownload` 与 download 联动（含 download → allowDownload=1）。
- 后端校验保持（分享权限 ⊆ 有效权限，超权拒绝）作为兜底。

### 3.3 RoleManageDialog（校验/微调）

- 确认 9 权限点齐全、新建默认（view+download 或全 false）、编辑回填、保存 `permissions` JSON；无需大改。

### 3.4 后端补充接口

- `GET /api/share/effective-permissions?fileNodeId=`：个人文件返回 {view,download}（本人）；团队文件经 `teamService.resolveMyPermissions(spaceId, nodeId)` 返回；未登录/无权返回空集。
- 落 ShareController（st-share），skillRefs 用 java-spring-boot。

### 3.5 类型

- `types/index.ts`：`FolderPermissionItem.subjectType` 加 `'all'`；`permissions?: Record<string, boolean>`；`ShareCreateRequest.permissions?`。

## 四、验收点（前端）

1. 文件夹权限：可配置 all/成员/角色 + 权限点；保存后刷新回显。
2. 分享：超权项禁用；download 与 allowDownload 联动；后端超权拒绝兜底。
3. 角色：9 权限点可勾选、保存回显。
4. `npx tsc --noEmit`、`npm run build` 通过。
