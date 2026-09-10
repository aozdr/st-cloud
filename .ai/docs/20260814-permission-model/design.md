# 权限模型重设计（角色权限组合 + 文件夹增强 + 分享权限上限）

> 状态：待确认。2026-08-14 由用户需求整理，确认后实施。

## 一、背景与目标

现有 `file_share.permission` 为单值枚举（0-查看 1-下载 2-上传 3-编辑），团队文件夹权限为单值（-1/0/1/2），未体现"角色=权限组合"与"文件夹权限增强"语义。目标：

1. **角色 = 权限点组合**：创建角色，角色内含权限点集合（如"上传者"= 查看+上传）。
2. **团队文件夹权限增强**：可对"全体（管理员除外）"或"单独用户"设置权限；用户有效权限 = 空间角色权限 ∪ 文件夹规则权限（并集增强）。
   - 例：上传者角色 {查看,上传} + 管理员给文件夹 A 配置用户规则 {下载} → 该用户在 A 及子文件 = {查看,上传,下载}；未配置的文件夹仍为 {查看,上传}。
3. **分享权限不超过当前用户权限**：分享出去的权限 ⊆ 分享者对该文件的有效权限（如 {查看,上传} 的分享者不能分享出下载权限）。

## 二、权限点体系（原子权限）

沿用现有 JSON 键（`team_role.permissions` 已用）：

```json
view / upload / download / delete / rename / move / share / manage_members / manage_settings
```

- 隐含关系：`upload` 隐含 `view`；`download` 隐含 `view`（权限计算时自动补全）。
- `share`：发起分享所需权限点；分享内容权限 ⊆ 分享者有效权限。
- `manage_members` / `manage_settings`：空间级管理，仅管理员/管理角色持有。

## 三、角色模型

| 类型 | 说明 | 权限点示例 |
|------|------|-----------|
| 内置管理员（role=0） | 空间拥有者/管理员 | 全部 true |
| 内置编辑者（role=1） | 现有预设 | view/upload/download/delete/rename/move/share true |
| 内置查看者（role=2） | 现有预设（**download 现为 true，待确认**） | view true（download 待定） |
| 自定义角色（roleId >= 100） | `team_role.permissions` JSON（已支持） | 任意组合，如"上传者"= view+upload |

- `team_member.role` 扩展为可存自定义角色 ID（0/1/2 或 >=100），权限集从 `team_role.permissions` 读取。

## 四、文件夹权限 = 增强（并集）

### 4.1 数据模型（team_folder_permission）

- `subject_type` 扩展支持 `all`（全体，管理员除外），保留 `member`（单用户）、`role`（角色）。
- `permission` 单值 TINYINT 改为 **`permissions` JSON 列**（权限点集合）；原 `permission` 列保留用于迁移映射。

### 4.2 有效权限计算（FolderPermissionService 改造）

```text
用户对节点有效权限集 =
    空间角色权限集(roleId → team_role.permissions)
    ∪ 从节点向上遍历至空间根收集的文件夹规则权限集
      （all 规则：非管理员全体生效；member 规则：命中该用户；role 规则：命中其角色）
管理员（manage_settings=true 或 roleId=0）：直接全部权限，不受文件夹规则限制
```

- 原"最近规则覆盖 / member 优先 role"改为"向上收集所有匹配规则取**并集**"（增强语义）。
- 规则可挂在任意节点（文件夹或文件），对该节点及其子树生效（子树经父链收集）。
- 显式"无权限"（-1）语义待确认：保留为拒绝特例，或仅允许增强（推荐仅增强，简化心智）。

### 4.3 验证用户例子

- 上传者角色 = {view, upload}；文件夹 A 配置 member 规则 {download} → A 及子文件 = {view, upload, download} ✓
- 文件夹未配置 → {view, upload} ✓
- 管理员不受规则限制 ✓

## 五、分享权限上限

### 5.1 数据模型（file_share）

- 新增 `permissions` JSON 列（分享允许的权限点集合）。
- `allow_download` 保留：由权限集含 `download` 推导/联动（含 download → 1，否则 0），兼容前端开关。
- `permission` 单值保留：迁移映射（0-查看→{view}；1-下载→{view,download}；2/3 视语义）。

### 5.2 创建/更新分享校验

```text
分享权限集 = 请求权限集 ⊆ 用户对该文件的有效权限集（否则拒绝："分享权限不能超过你的权限"）
发起分享需具备 share 权限点
```

- 示例：用户有效权限 {view, upload}，请求分享含 download → 拒绝或自动裁剪为 {view, upload}（推荐拒绝 + 明确提示）。
- 下载/流式：分享权限集含 `download` 才允许（getDownloadUrl / streamShareFile 统一判断）。

## 六、代码改造点

1. `FolderPermissionService`：`resolvePermission` → `resolvePermissions`（返回 Set<String>）；`computePermission` 改并集收集；支持 `all` 主体。
2. `TeamService`：新增 `requirePermissions(spaceId, nodeId, Set<String>)` 或改造 `checkPermission`；团队文件操作（上传/下载/删除/重命名/移动）按权限点校验。
3. `ShareServiceImpl`：创建/更新分享做"权限 ⊆ 自身有效权限 + share 权限点"校验；下载/流式按分享权限集判断。
4. 前端：文件夹权限配置（全体/成员/角色 + 权限点勾选）、分享权限选择（禁用超出自身权限项）、角色管理（权限点勾选，`team_role.permissions` 已支持）。

## 七、数据库迁移（确认后实施）

- `34_team_folder_permission_permissions.sql`：`team_folder_permission` 加 `permissions` JSON 列（幂等守卫）；历史 `permission` 单值映射（-1→{"view":false}？0→全部？1→{view,upload,download}，2→{view}，按确认后语义）。
- `35_file_share_permissions.sql`：`file_share` 加 `permissions` JSON 列；历史 `permission` 映射。
- H2 schema 同步（st-core/st-share/st-team 测试）；`compare-schema.ps1` PASS；`schema_version` 登记新版本。

## 八、验证方式

- 集成测试：角色权限组合解析；文件夹增强并集（含用户例子）；管理员例外；`all` 规则；子树继承；分享上限（{view,upload} 分享者不能带 download）；分享 download → allow_download 联动。
- 迁移后 `compare-schema.ps1` + 全量 `mvn test` + 前端 tsc。

## 九、待确认决策点

1. **权限点键集合**：✅ 沿用现有 9 个。
2. **内置查看者**：✅ 仅可在线查看（view=true，download=false；防下载对抗后续再处理）。
3. **文件夹规则**：✅ 仅增强（并集），不做显式禁止。
4. **分享前置**：✅ 需要 `share` 权限点才能发起分享。

## 十、确认后的实现要点

- 预设查看者权限 = `{"view":true}`（download=false）。
- `team_folder_permission`：`subject_type` 支持 `all`（全体非管理员）；新增 `permissions` JSON 列；规则只增强。
- 分享创建/更新：`share` 权限点前置 + 分享权限 ⊆ 用户对文件有效权限；`allow_download` 与权限集含 `download` 联动。
