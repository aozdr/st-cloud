# 变更报告：文件跨空间操作与元数据增强

> 迭代：20260829-file-ops
> 状态：后端与前端均实现并通过编译/构建；跨空间前端入口、单文件转存、团队元数据已补齐

## 一、后端（已实现，mvn compile 全绿）

### 1. 数据模型
- 新增 `docker/mysql/init/40_file_meta.sql`：`file_meta` / `file_tag` / `file_node_tag` 三张表。
- 同步 `st-core/src/test/resources/schema.sql`（H2 版建表）。

### 2. 核心服务（st-core）
- 新增实体 `FileMeta` / `FileTag` / `FileNodeTag` 及对应 Mapper。
- 新增 `FileTransferService` + `FileTransferServiceImpl`：跨空间复制/移动原语（`copyToSpace` / `moveToSpace` / `copyNodeToSpace`），按目标空间做三层配额记账、对象引用 acquire、目录树递归、事件发布。仅做归属校验，团队授权由编排层完成。
- 新增 `FileMetaService` + `FileMetaServiceImpl`：元数据读写、标签增删（幂等），个人/团队空间归属校验。

### 3. 编排层（st-api）
- `FileTransferController`：`POST /api/file/transfer`（move/copy，src/target 团队空间走 TeamService 授权）。
- `FileMetaController`：`GET/PUT /api/file/{nodeId}/meta`、`GET/POST /api/file/{nodeId}/tags`、`DELETE /api/file/{nodeId}/tags/{tagId}`（spaceId 可选，支持团队）。
- `ShareSaveController`：`POST /api/share/access/save/{shareCode}`（转存：分享校验 view 权限 → 目标空间写权限 → 复制到目标空间）。
- 新增 DTO：`TransferRequest` / `SaveShareRequest`。

## 二、前端（已实现，npm run build 全绿）

- **转存**：`ShareAccessPage` 文件夹视图新增「保存到我的网盘」按钮，调 `/share/access/save/{shareCode}`，成功后跳转个人文件。
- **元数据**：新增 `FileMetaPanel` 组件，接入 `FileDetailPanel`，支持标题/描述编辑与标签增删；接口走 `/file/{id}/meta`、`/file/{id}/tags`。
- **排序增强**：`SortBy` 扩展 `type`，`useFileBrowser` 增加类型排序比较（后缀归类 + 文件夹优先），`FileTableView` 类型列可点击排序，`FileToolbar` 排序下拉增加「类型」，偏好持久化到 localStorage/URL。
- **跨空间移动/复制入口**：`MoveDialog` 增加「目标空间」下拉（个人空间 + 参与团队空间），选择不同空间时加载对应目录树，确认走 `POST /file/transfer`；`FileSource` 新增 `spaceId` 标记源空间。
- **单文件分享转存**：`ShareAccessPage` 单文件视图新增「保存到我的网盘」按钮。
- **团队元数据**：`FileMetaPanel`/`FileDetailPanel` 支持 `spaceId`，团队文件详情（`TeamSpacePage`）传入空间 ID 走 `?spaceId=` 接口。

## 三、验证

- `mvn -q -DskipTests -pl st-api -am compile` → 通过。
- `mvn test -pl st-core -am` → **149 tests, 0 failures, 0 errors**（st-common + st-core；含 `SchemaConsistencyTest` 3 用例，确认新增 `file_meta`/`file_tag`/`file_node_tag` 三层一致）。
- `cd st-web && npm run build`（tsc + vite）→ 通过。

## 四、遗留（可选，需运行时联调）

- **拖拽手动排序（本地持久化）**：因与现有「拖拽到文件夹移动/上传」的 HTML5 DnD 交互存在冲突，强行接入可能破坏既有拖拽行为，待真实运行环境联调后再启用。用户已确认仅本地持久化。

## 五、风险与说明

- 跨空间移动采用「就地改 spaceId/path/owner + 源释放/目标扣减配额」，不复制物理对象，对象引用计数不变，符合去重模型。
- 团队空间授权在编排层用 `TeamService.checkPermission(spaceId, 1)`（编辑者及以上）；文件夹级细粒度权限覆盖暂未接入，后续可改用 `requirePermissions(spaceId, nodeId, "upload")` 强化。
- 数据库变更需按项目流程执行：H2 测试 → `compare-schema.ps1` → MySQL 执行 40 号脚本 → 更新 `schema_version`。

## 六、运行期缺陷修复（2026-08-29）

### 缺陷1：跨空间移动/复制「目标空间」下拉空白

- 根因：`GET /team/spaces` 返回 `Result<IPage<TeamSpaceVO>>`（分页对象 `{records:[...]}`），且字段为 `spaceName`；前端原先用 `s.name` 且未取 `records`，导致选项名全为 `undefined`。
- 修复：`FileBrowserDialogs` 改为取 `records` 并映射 `s.spaceName || s.name`，请求传 `page:1,size:100`。
- 附带：`MoveDialog` 错误提示改为展示后端真实原因（原为笼统「操作失败」），便于暴露权限/同名/配额问题。

### 缺陷2：分享提取码进入提示 Network Error，后端无日志

- 根因：此前一轮 CORS 安全升级把 `SecurityConfig.corsConfigurationSource` 由「允许所有来源」改为「白名单 + 空则拒绝」，Web（非代理）/桌面端（`app://web`）访问分享接口时被拦截，浏览器显示 Network Error，后端无业务日志。
- 修复：**移除该轮 CORS 安全升级**——`SecurityConfig` 恢复 `setAllowedOriginPatterns(List.of("*"))`，删除 `cors.allowed-origins` 相关配置（application.yml、application-dev.yml）与 `@Slf4j/@Value/allowedOrigins` 字段；跨域回到允许所有来源。
- 说明：若生产仍需要来源白名单，可在部署层（网关/反向代理）收敛允许的来源，不再由应用层强制白名单。

## 七、Codex 直改补充（2026-08-29）

### A. CORS 残留清理

- 移除 `README.md` 与 `docker/.env.example` 中已失效的 `STCLOUD_CORS_ORIGINS` 引用（配置不再存在）。
- 同步修正 `.ai/knowledge/conventions.md` / `project-overview.md` / `architecture.md` 中对旧 `stcloud.cors.allowed-origins` 的描述，统一为「SecurityConfig 允许所有来源」。

### B. 分享保存到云盘（新功能）

- 后端：`st-share` 新增 `POST /api/share/save`（需登录）。请求 `SaveShareRequest`，响应 `SaveShareVO`。
  - 使用 `validateShareAccess` 做匿名分享访问校验；要求分享具备下载权限（保存即下载复制语义）。
  - 仅从分享根节点向下遍历 `parentId` 子链保存，绝不越界保存分享外文件。
  - 目标文件夹必须是当前用户个人云盘目录；按 MD5 去重复用物理对象（`fileObjectService.acquire`），同步配额扣减与索引/同步事件。
  - 端点位于 `/api/share/save` 而非 `/api/share/access/**`，避免落入公开白名单，强制登录。
- 前端：
  - 新增 `SaveShareDialog`（文件夹树选择目标目录）。
  - `ShareAccessPage` 单文件/文件夹视图新增「保存到云盘」按钮；未登录时 toast 提醒并跳转 `/login?redirect=<分享页&save=1>`，登录成功后回到分享页自动弹出保存窗口。
  - `Login` 支持 `redirect` 参数，登录后跳回原页面。
- **下载权限门禁**：仅当分享允许下载（`allowDownload=1` 且权限集含 `download`）时才可保存；前端按钮同样按可下载隐藏。已新增集成测试 `viewOnlyShareSaveToDriveRejected` 验证仅查看分享保存被拒。
- 验证：`mvn -o -pl st-api -am compile` 通过；`cd st-web && npm run build`（tsc + vite）通过；`ShareServiceImplSecurityIntegrationTest` 通过（含新增保存权限用例）。既有缺陷：`createShareGenerates4CharSafeShareCode` 仍期望 4 位分享码（当前默认 12 位，防枚举改造后的测试漂移，非本次改动引入，未处理）。

### C. 保存对话框出现「管理员文件」修复（2026-08-29 追加）

- 根因：`FileServiceImpl.getFolderTree()`（即 `/file/tree`，供保存/移动/归档对话框使用）沿用 `canAccessTenant()` 判断，当用户 `dataScope>=2`（租户级/管理员）时不过滤 `ownerId`，把同租户下所有用户的文件夹（含管理员）返回给当前用户，导致保存对话框显示「管理员的一串文件」。
- 修复：`getFolderTree()` 改为**始终按当前用户 ownerId + 个人空间（spaceId 为空或 0）过滤**，不再受 `dataScope` 影响。保存到云盘/移动/归档的目标目录必须落在当前用户自己的云盘内，与后端 `saveShare` 的属主校验保持一致。
- 说明：主文件列表（`listDirectory`）仍按 `dataScope` 决定是否显示全租户文件（租户级用户可管理全部文件，属既有设计）；若某普通用户不应看到全租户文件，需复核其角色/`dataScope` 配置。

### D. 个人文件强制属主（2026-08-29 追加，单一租户/无租户切换）

- 决策：当前无租户切换功能、实际单一租户，因此「我的文件/全部文件」应**无条件只返回当前用户本人的文件**，不应被 `dataScope>=2`（租户级）放行看到其他用户（含管理员）的个人文件。
- 改动（`st-core`）：个人文件查询/操作统一改为强制属主判定，团队文件（`spaceId>0`）仍由团队鉴权前置校验：
  - `listDirectory` / `searchFiles`：个人列表查询恒加 `ownerId = userId`（移除 `canAccessTenant` 旁路）。
  - `getFolderTree`：个人目录树恒加 `ownerId = userId` + 个人空间（`spaceId` 空/0）过滤（前述 C）。
  - `getNodeByIdAndOwner` / `getFolderSize` / `setHidden`：个人文件（`spaceId` 空/0）仅属主可访问/操作，团队文件放行（由团队鉴权）。
  - `DownloadServiceImpl` / `UploadServiceImpl` / `ArchiveServiceImpl` / `NewFileServiceImpl` / `RecycleBinServiceImpl`：个人文件仅属主可下载/上传/解压/新建/回收，团队文件放行。
- 测试：`FileServicePermissionIntegrationTest` 改为断言「租户级 `dataScope=2` 也不能访问他人个人文件」并已通过；`mvn -o -pl st-core -am test` 全绿。
- 遗留提示：`st-share` 的 `createShare` 仍保留「属主或租户级可分享他人文件」的旁路；若需彻底隔离，可后续将其改为仅属主可分享（团队分享另走团队授权）。

### E. 角色权限分配不生效 + 去掉 dataScope 复杂度（2026-08-29 追加）

- 现象：角色→权限分配后重进弹窗勾选为空；前端上传/下载等按钮不出现；权限页存在「本人/租户/全部」数据范围。
- 根因：
  1. `sys_role` / `sys_role_permission` / `sys_user_role` 受租户拦截器过滤，写入与读取时 `tenant_id` 不一致时分配结果读不回来；且该“跨租户/跨所有者”复杂度在当前单租户、无租户切换场景下是无意义的。
  2. `dataScope`（本人/租户/全部）驱动 `canAccessTenant()`，导致跨用户文件访问与配置复杂度。
- 修复：
  - `MyBatisPlusConfig` 将 `sys_role`、`sys_role_permission`、`sys_user_role` 加入租户拦截忽略表（单租户 RBAC 全局生效，权限分配/回显一致）。
  - `UserContext.canAccessTenant()` 恒返回 `false`：单租户下不再按数据范围放行跨用户/跨租户数据访问。
  - `RoleServiceImpl` 创建/更新角色强制 `data_scope=1`（本人）。
  - 前端 `RoleManagePanel` 移除「数据范围」列与下拉；`RoleMultiSelect` 移除角色条目的数据范围徽标。
- 验证：`mvn -o -pl st-api -am compile` 通过；`cd st-web && npm run build`（tsc + vite）通过。
- 说明：`sys_permission` 本就全局（租户拦截已忽略）；角色/用户关联、角色-权限关联现不再按租户过滤，符合“权限配置只影响当前租户环境、不要跨租户管理”。

### F. snowflake ID 精度丢失（Number(id)）修复（2026-08-29 追加）

- 现象：给用户角色分配后重进为空、上传/下载按钮仍不出现；`sys_user_role.role_id` 写入的是在 `sys_role` 中不存在的 ID。
- 根因：数据库为 BIGINT(snowflake)，后端已通过全局 Jackson 配置把 Long 序列化为字符串；但前端多处用 `Number(id)` 把字符串转成 JS number，而 snowflake 远超 JS 安全整数（2^53），导致传给后端的 id 丢失精度、指向不存在的记录。
- 修复：
  - 后端 DTO：`AssignRolesRequest.roleIds` / `AssignPermissionsRequest.permissionIds` / `CreateUserRequest.roleIds` 改为 `List<String>`；`FolderPermissionRequest.PermissionRule.subjectId` 与 `FolderPermissionVO.subjectId` 改为 `String`。
  - 后端 Service：`RoleServiceImpl.assignRolesToUser` / `assignPermissions`、`UserManageServiceImpl.createUser`、`TeamServiceImpl.setFolderPermissions`/`getFolderPermissions` 均显式 `Long.valueOf(String)` 转换或 `String.valueOf(Long)` 反序列化。
  - 前端：删除 `Number(id)`（`UserManageTab` 角色分配、`AdminDialogs` 新建用户、`RoleManagePanel` 权限分配、`FolderPermissionDialog` subjectId），id 一律作为字符串发送。
- 验证：`mvn -o -pl st-api -am compile`、`mvn -o -pl st-admin,st-team -am test-compile` 通过；`cd st-web && npm run build`（tsc + vite）通过。

### G. 文件夹右键无「下载」修复（2026-08-29 追加）

- 现象：文件夹右键菜单无「下载」入口；即使有 `file:download` 权限也看不到。
- 根因：`ContextMenu` 下载项只对文件（`nodeType===1`）渲染；且单文件夹若直接走单文件下载接口会失败（后端不支持），需走 ZIP 打包。
- 修复：
  - `ContextMenu`：下载项改为 `has('file:download')` 即显示（文件/文件夹通用）。
  - `useFileDownload`：单个文件夹下载改走 `source.downloadZip` 打包（`${name}.zip`），多选文件夹保持原有打包逻辑。
  - 后端 `/file/download/zip` 已要求 `hasAuthority('file:download')`，权限链路一致。
- 验证：`cd st-web && npm run build`（tsc + vite）通过。

### H. Toast 弹窗替换（打包下载“进行中/完成”共存）修复（2026-08-29 追加）

- 现象：ZIP 打包下载时，“正在打包下载”与“打包完成”两条 toast 共存，未覆盖。
- 根因：`ToastProvider.showToast` 总是往栈里追加，不支持同组替换。
- 修复：Toast 增加可选 `key`，相同 key 的新 toast 会顶替旧 toast；`useFileDownload` 的打包加载/完成提示共用一个 `'zip-download'` key。
- 验证：`cd st-web && npm run build`（tsc + vite）通过。

### I. 角色管理与用户权限分配 UI/UX 优化（ui-ux-pro-max，2026-08-29 追加）

- 目标：角色管理、用户权限分配当前界面难用/难看；按 ui-ux-pro-max（性能/可访问性/架构）重构。
- 新增组件：
  - `RoleCheckboxList`：角色多选（搜索 + 全选 + 勾选列表），替换原难用的自定义下拉 `RoleMultiSelect`（已删除）；角色 id 全程字符串，不 `Number()`。
  - `PermissionSelector`：权限按模块分组选择，支持搜索、模块全选、折叠/展开、全部选择；权限 id 为字符串。
- 重构：
  - `RoleManagePanel`：角色卡片列表（名称/编码/权限数/状态/描述徽章）+ 名称搜索 + 空状态；编辑弹窗改为 `max-w-3xl` 布局，表单标签对齐，权限区使用 `PermissionSelector`。子项 `RoleRow` 用 `React.memo`，处理器用 `useCallback`，派生数据用 `useMemo`。
  - `AdminDialogs`：`RoleAssignDialog` / `CreateUserDialog` 改用 `RoleCheckboxList`，表单加 `aria-label`/`htmlFor`、必填标记与错误提示；`CreateUserDialog` 用 `memo`。
  - `UserManageTab`：用户表角色列展示全部角色徽章，新增空状态。
- 验证：`cd st-web && npm run build`（tsc + vite）、`npx eslint <改动文件>` 均通过。

### J. 权限分配改为树状父子联动（参考 Ant Design Tree / element-ui el-tree，2026-08-29 追加）

- 背景：上一版 `PermissionSelector` 是“模块分组 + 平铺勾选”，父节点只能全选、无法表达半选，用户反馈不好用；按主流 Admin 框架（Ant Design `Tree checkable` / element-ui `el-tree` show-checkbox）改用树形父子联动。
- 新增组件：`st-web/src/components/ui/PermissionTree.tsx`
  - 模块 = 父节点，权限 = 子节点。
  - 原生 checkbox 半选：父节点用 `indeterminate` 表达“子权限部分选中”。
  - 父子联动：勾父节点→全选子权限；子权限部分选中→父节点半选；再次点击父节点→反选该模块全部权限。
  - 支持权限名称/编码搜索、模块折叠/展开、全部展开/折叠、顶部全选（含半选）。
  - 每模块显示 `已选/总数`；树列表加 `max-h-[42vh]` 内部滚动；checkbox/按钮加键盘焦点环（可访问性）。
  - 权限 id 全程字符串，不 `Number()`。
- 接入：`RoleManagePanel` 的权限区由 `PermissionSelector` 切换为 `PermissionTree`；删除不再使用的 `PermissionSelector.tsx`。
- 布局：新建/编辑角色弹窗改为「头部固定 + 紧凑表单 + 权限树为主 + 底部操作按钮固定且不透明」；表单（编码/名称两列、描述与状态同行）与权限树共用一个 `flex-1 overflow-y-auto` body，整窗单滚动区、顺畅不卡顿；底部按钮条 `bg-surface` 实背景 + 顶部描边/阴影遮盖滚动内容，按钮外区域不再透明；权限树恢复自然流，不在内部嵌套滚动。
- 验证：`cd st-web && npx eslint src/components/ui/PermissionTree.tsx src/components/admin/RoleManagePanel.tsx` 通过；`npm run build`（tsc + vite）通过。
