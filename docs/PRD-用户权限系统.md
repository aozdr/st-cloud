# 用户权限系统 PRD

> 版本：v1.0 · 日期：2026-08-04 · 关联：`docs/PRD-云盘系统-v2.0.md` Epic 8（安全与访问控制）
> 起因：代码审查发现 `sys_user.is_admin` 魔法值与已有 RBAC 并存，形成双轨权限来源；本 PRD 将权限统一收敛到「角色 + 权限码」，彻底移除 `isAdmin` 魔法值。

## 1. Executive Summary
星云盘已具备 RBAC 基础（`sys_role` / `sys_permission` / `sys_user_role` / `sys_role_permission` 四张表，21 个权限码，内置 `admin` / `user` 角色，见 `docker/mysql/init/04_rbac_tables.sql`），但同时在 `sys_user` 上保留了 `is_admin` 布尔标志，作为「超管旁路」绕过 RBAC 直接放行。本 PRD 在不引入新权限范式的前提下，**移除 `is_admin` 魔法值，将管理员身份统一收敛到 `admin` 角色与权限码**，并用 `@PreAuthorize` / `UserContext.hasPermission()` 替代散落的 `UserContext.isAdmin()` 判断，使权限模型单一、可审计、可扩展。

## 2. Problem Statement
- **谁有问题**：平台开发者、运维管理员、安全审计。
- **问题**：
  1. **双轨权限来源**：`sys_user.is_admin`（`02_create_tables.sql:39`）与 RBAC `admin` 角色（`04_rbac_tables.sql`）并存。`AuthService.loadUserPermissions`（`AuthService.java:212-213`）中 `is_admin==1` 直接授予全部权限，使 `admin` 角色的权限分配形同虚设，两者易漂移。
  2. **魔法值旁路散落**：`UserContext.isAdmin()`（`UserContext.java:41-47`）被 6 处服务用作「超管放行」；其中 `DownloadServiceImpl:58,80`、`FileServiceImpl:400`、`UploadServiceImpl:229` 用它越过所有权校验读取/操作他人文件——等价于「管理员可无凭证访问任意文件」，属粗粒度越权，且无对应审计权限码。
  3. **Primitive Obsession**：`is_admin` 为 `Integer 0/1`，「是否管理员」本是角色属性，不应是用户表上的标志位。
  4. **前端按布尔渲染**：`AdminPage.tsx:849` 用 `user.isAdmin === 1`、`types/index.ts` 多处依赖 `isAdmin`，新增「审计员」「租户管理员」等角色需改前端代码，无法由权限码驱动。
- **为何痛**：权限语义模糊、越权风险、扩展困难、审计无法按权限码追溯。

## 3. Target Users & Personas
- **系统管理员（System Admin）**：持有 `admin` 角色，管理全平台用户/角色/审计/统计。
- **租户管理员（Tenant Admin）**（增量，待定）：仅管理本租户用户与配额（见 Open Questions）。
- **普通用户（User）**：持有 `user` 角色，仅操作自有/被授权资源。
- **开发者（Developer）**：通过权限码与 `@PreAuthorize` 声明式鉴权，不再写 `isAdmin` 判断。

## 4. Strategic Context
- 业务目标：私有化企业网盘，强调「数据安全合规」（v2.0 PRD），权限模型须单一可审计。
- 现状基础：`@EnableMethodSecurity` 已开启（`SecurityConfig.java`），`JwtAuthenticationFilter` 已将每个权限码注册为 `GrantedAuthority`，原生支持 `@PreAuthorize("hasAuthority('xxx')")`——基础设施就绪，仅缺统一采用。
- 为何现在：刚移除 2FA（`09_remove_two_factor.sql`），权限代码处于活跃改动期，适合一并收敛 `is_admin`。

## 5. Solution Overview
**核心：管理员 = 持有 `admin` 角色；访问控制 = 权限码校验，不再有布尔旁路。**

1. **数据层**：新增 `10_drop_is_admin.sql`——先将 `is_admin=1` 的用户补配 `admin` 角色（幂等 `INSERT IGNORE`），再以 `information_schema` 条件判断 `DROP COLUMN is_admin`（沿用 `09` 的幂等模式）。
2. **实体/DTO**：`SysUser` 移除 `isAdmin` 字段；`LoginResponse` 移除 `isAdmin`，前端改用 `roles` / `permissions`。
3. **JWT**：移除 `admin` 布尔 claim；`generateToken` 不再接收 `isAdmin` 参数，仅保留 `roles` / `permissions`；删除 `JwtUtils.isAdmin(token)`。过滤器兼容旧 token（`admin` claim 缺失即视为非管理员，`roles` 含 `admin` 则自然具备权限）。
4. **鉴权**：
   - 接口级：管理类接口改用 `@PreAuthorize("hasAuthority('admin:user:manage')")` 等，按 `04_rbac_tables.sql` 既有权限码。
   - 数据级：替换 `!owner && !isAdmin()` 模式为显式权限码（新增 `file:admin:read_all` / `file:admin:manage_all`，或复用既有 `admin:*`）；提供 `UserContext.hasPermission(code)` / `hasRole(code)` 编程式入口（`UserContext.java:47` 已有雏形）。
5. **前端**：管理入口与路由按 `permissions.includes('admin:user:manage')` 或 `roles.includes('admin')` 渲染；`App.tsx` 路由守卫同理。
6. **权限刷新**：明确权限变更后通过 refresh token 重发（现有 `refreshToken` 流程已重载权限），文档化「角色变更需刷新 token」。

## 6. Success Metrics
- 主指标：仓库内 `is_admin` / `isAdmin` / `getIsAdmin` 引用数 **现状约 15 处 → 目标 0**（Java + SQL + 前端）。
- `UserContext.isAdmin()` 调用点 **6 → 0**。
- 管理类接口由 `@PreAuthorize` 或权限码保护率 **100%**。
- 存量 `is_admin=1` 用户 **100%** 迁移为 `admin` 角色，登录无感知。
- 回归：普通用户越权访问他人文件返回 403；管理员按权限码可访问。

## 7. User Stories & Requirements
- **Epic 假设**：若将管理员身份统一为 `admin` 角色并以权限码鉴权，则权限模型单一可审计，且可平滑扩展新角色，无需修改鉴权框架。
- **US1 · 移除 is_admin 标志**  
  AC：`sys_user` 无 `is_admin` 列；`SysUser` / `LoginResponse` 无 `isAdmin` 字段；存量管理员已分配 `admin` 角色；迁移脚本幂等可重复执行。
- **US2 · 接口级声明式鉴权**  
  AC：所有 `admin:*` 接口标注 `@PreAuthorize(hasAuthority(...))`；无 `UserContext.isAdmin()` 调用；无权限访问返回 403。
- **US3 · 数据级访问控制**  
  AC：`Download/File/UploadServiceImpl` 的越权判断改为权限码（如 `file:admin:read_all`）；普通用户仅访问自有/共享资源；管理员需持有对应权限码方可越权，且有审计日志。
- **US4 · 前端权限驱动**  
  AC：管理页/路由按 `permissions` 或 `roles` 渲染；移除 `isAdmin` 字段；`AdminPage.tsx` 不再出现 `isAdmin === 1`。
- **US5 · JWT 精简**  
  AC：token 不含 `admin` claim；旧 token 在过渡期内仍可用（兼容缺失 claim）；`refreshToken` 重发后携带最新角色/权限。
- **约束**：不改变现有权限码语义；不引入新鉴权框架；保持多租户隔离。

## 8. Out of Scope
- 2FA（已下线）。
- ABAC / 属性级权限。
- 跨租户组织树、父子租户层级。
- 前端按钮级权限组件库（可后续迭代）。
- OAuth / SSO 集成。

## 9. Dependencies & Risks
- **依赖**：`04_rbac_tables.sql` 既有角色/权限数据；`@EnableMethodSecurity`；`refreshToken` 流程。
- **风险**：
  - **数据迁移顺序**：必须先补配 `admin` 角色，再删 `is_admin` 列，否则管理员失权 → 迁移脚本内事务化。
  - **旧 token 兼容**：`admin` claim 缺失时过滤器不得 NPE，应回退到 `roles` 判定 → 加测试。
  - **越权回归**：`isAdmin` 旁路移除后，漏配权限码会导致管理员被拒或普通用户越权 → 针对 6 个调用点建测试矩阵。
  - **多租户 admin 角色**：`admin` 角色绑定默认租户（`tenant_id=1`），受租户拦截器过滤；跨租户系统级角色需改 IGNORE_TABLES，见第10节决策。

## 10. Open Questions -> Decisions
> 以下开放问题已在 v1.0 实现中决策，记录于此供后续迭代参考。

- **Q1 · 系统超管 vs 租户管理员**：内置 `admin` / `user` 角色**保留 `tenant_id=1`（默认租户）**，不改为系统级。原因：`MyBatisPlusConfig` 租户拦截器在 SAAS 模式下对 `sys_role` 自动注入 `tenant_id = 当前租户` 过滤（`sys_role` 不在 `IGNORE_TABLES`），内置角色若置 `tenant_id=0` 则租户用户查不到角色、登录失败。跨租户系统级角色需将 `sys_role`/`sys_user_role`/`sys_role_permission` 加入 `IGNORE_TABLES`，作为未来增量。「租户管理员」（仅管本租户）同样留待未来。
- **Q2 · 数据级 `data_scope`**：`sys_role` 新增 `data_scope` 字段（`1=本人 / 2=租户 / 3=全部`）。`admin=3`、`user=1`。`UserContext.canAccessAll()`（`dataScope >= 3`）替代 `Download/File/UploadServiceImpl` 中散落的 `hasRole("admin")` 越权旁路；`dataScope=2`（租户级）已在文件层落地：`UserContext.canAccessTenant()`（`dataScope >= 2`）驱动个人文件列表/搜索/目录树/回收站/ZIP下载的 `ownerId` 条件过滤（`eq(!canAccessTenant(), ...)`），访问校验（`getNodeByIdAndOwner`、下载、替换上传）改用 `canAccessTenant()`，`resolveByPath` 在 `canAccessTenant()` 时跳过 `ownerId` 过滤。租户隔离由 `TenantLineInnerInterceptor` 保障。
- **Q3 · 权限缓存**：维持 **JWT 内嵌权限/数据范围**（登录与 refresh token 重发即携带最新值）。角色/权限变更后需 refresh token 刷新；Redis 实时权限查询作为未来优化，当前不引入。
- **Q4 · `ROLE_ADMIN` authority**：保留。`JwtAuthenticationFilter` 仍将每个角色码注册为 `ROLE_<CODE>` authority，`hasRole('admin')` 在 Spring Security 层面可用；业务层数据越权改用 `canAccessAll()`，二者职责分离。
- **Q5 · 权限码补齐与控制器对齐**：新增 `file:copy`（文件复制）、`admin:storage:manage`（存储管理）两个权限码（`12_add_permissions.sql`，幂等迁移），权限码总数 17 -> 20。`SpeedLimitController` 统一使用 `transfer:speed:limit`（修正原 `admin:ratelimit:manage` 错误码）；`CloudCapacityController` 由 `hasRole('ADMIN')` 改为 `hasAuthority('admin:storage:manage')`。`FileController` 补齐 `/copy`、`/{nodeId}/stream`、`/download/zip`、`/upload/*`、`/folder` 的 `@PreAuthorize`；`RecycleBinController` 的 `/restore`、`/empty` 补 `file:delete`；`TeamController` 文件操作补全局 `file:*` 权限码（保留团队级 `checkPermission` 作二级校验）。前端存储管理 Tab 与 Sidebar `canAccessAdmin` 同步对齐 `admin:storage:manage`。
- **Q6 · 孤儿权限码清理**：`13_remove_ratelimit_orphan.sql`（幂等迁移）删除历史遗留的 `admin:ratelimit:manage`——该码原由 `05_rate_limit_tables.sql` 种入，但 `SpeedLimitController` 实际使用 `transfer:speed:limit`（已由 `04_rbac_tables.sql` 种子化），`admin:ratelimit:manage` 无控制器引用，属孤儿码。`05_rate_limit_tables.sql` 同步移除该码 INSERT，改由 `04` 统一种子化，权限码总数稳定为 20。

- **Q7 · 文件预览权限**：新增 `file:preview`（文件预览）权限码（`14_add_preview_permission.sql`，幂等迁移），权限码总数 20 -> 21。`FileController` 的 `/{nodeId}/stream` 端点 `@PreAuthorize` 改为 `hasAuthority('file:preview') or hasAuthority('file:download') or hasRole('ADMIN')`，使仅有预览权限的用户可在线查看但不能下载。前端 `ContextMenu` 预览项、`FileBrowser` 双击/回车预览均按 `file:preview` 门控。
