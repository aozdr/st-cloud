# 安全审查记录 — 20260814-permission-ui（前端权限 UI 安全复检）

> 归属：SECURITY_REVIEW（权限相关，条件项启用）｜Reviewer：reviewer（taskType=security）｜日期：2026-08-14
> 派发：dispatchId=sec-permui-001，taskId=TASK-SEC-PERM-UI
> 技能：code-review（降级单 Agent 两轴）、review-agent（缺陷优先审查）、java-spring-boot（后端安全上下文）
> 基线：design.md / codereview.md / changereport.md；代码为当前工作区实际状态（未提交）

## 一、背景

前端权限 UI 迭代（文件夹权限 all/member/role + 9 权限点、分享权限点选择禁用超权、角色管理校验、后端 effective-permissions 接口）涉及文件权限、分享访问控制与下载链路，属安全条件项，需在 CODE_REVIEW 之后独立复检四项安全焦点，输出逐项结论与问题清单。

## 二、输入

- 派发信封：inbox-sec-permui-001.md（claimedFile）
- TASK：`.ai/tasks/TASK-SEC-PERM-UI.md`；State：`.ai/state/20260814-permission-ui.yaml`（SECURITY_REVIEW pending）
- 设计基线：`.ai/docs/20260814-permission-ui/design.md`、`.ai/docs/20260814-permission-model/design.md`
- 代码基线（实际代码，非 diff）：ShareController / ShareServiceImpl、TeamController / TeamServiceImpl / FolderPermissionService、FolderPermissionRequest / FolderPermissionVO、SecurityConfig、FileServiceImpl.validateAccessible、UserContext、迁移脚本 33/34/35
- 前端：FolderPermissionDialog / RoleManageDialog / ShareDialog、`st-web/src/lib/permissions.ts`、`types/index.ts`
- 测试基线：ShareServiceImplPermissionLimitIntegrationTest、ShareServiceImplSecurityIntegrationTest、TeamServicePermissionIntegrationTest

## 三、逐项安全结论

### 1. effective-permissions 接口越权 — **PASS**

核对点：他人文件 / 未登录 / 非成员不得返回有效权限。

- 接口位于需认证区（SecurityConfig 仅 `/api/share/access/**` permitAll，其余 `anyRequest().authenticated()`），无 `@PreAuthorize` 与同类只读接口（listShares）一致。
- 未登录（UserContext.getUserId()==null）→ 空集（防御性兜底）✓
- 文件不存在 / status != 0 → 空集 ✓
- 个人文件：仅 owner 返回 {view, download}；非 owner → 空集 ✓（不存在/无权两种响应不可区分，不泄露文件存在性）
- 团队文件：`resolveMyPermissions`（成员校验 + 角色 ∪ 文件夹规则）；非成员 BusinessException 捕获后返回空集，不抛出 ✓
- 空集/空权限 → 空集 ✓
- 结论：无越权；不泄露他人数据。

次要口径（非越权）：租户管理员（dataScope>=2）对他人个人文件返回空集，但 createShare 允许其分享（见问题 P3-2）。

### 2. 分享权限上限 — **PASS**

核对点：分享权限 ⊆ 有效权限 + share 前置；个人文件上限 {view,download} 与接口一致。

- createShare：个人文件仅本人（或租户管理员）可分享，上限 `PERSONAL_EFFECTIVE_PERMS={view,download}`（与 effective-permissions 接口口径一致，SP1 已修复）；团队文件先 `requirePermissions(spaceId, nodeId, "share")`（share 前置），再 `resolveMyPermissions` 得有效权限集。
- 创建/更新均执行 `myPerms.containsAll(sharePerms)` 超权拒绝（"分享权限不能超过你的权限"），服务端为最终闸门。
- updateShare 走 `resolveMyEffectivePerms` 同口径复检；仅改 allowDownload/status 等字段时不改权限集，安全。
- allowDownload 联动：权限集含 download → 1，与显式值取交集（两者都允许才 1）。
- 回归测试覆盖：`personalFileOverPermissionRejected`（个人文件 upload 超权拒绝，SP1 回归）、`teamFileOverPermissionRejected`、`teamShareRequiresSharePermissionPoint`、`getDownloadUrlRejectedWhenPermissionsLackDownload`。

### 3. 文件夹权限配置接口（setFolderPermissions）权限校验 — **BLOCK**

核对点：仅管理员/管理角色可配置；all 主体不可越权。

- 仅管理员/管理角色可配置：**PASS**。`setFolderPermissions` / `getFolderPermissions` 均先 `checkPermission(spaceId, 0)`（roleId==0 或角色权限集含 manage_settings），普通成员/查看者被拒。
- 节点归属校验：**FAIL（P1）**。两个接口均未校验 `folderNodeId` 属于 `spaceId`；读路径（FolderPermissionService.resolvePermissions）按 folderNodeId 查规则且不校验规则 spaceId，`matches` 亦不校验 `rule.spaceId`。任意空间管理员（可自建空间获得）可跨空间写入/覆盖其他团队文件夹的权限规则（详情见问题 P1）。
- all 主体越权限制：**FAIL（P2）**。服务端对 subjectType 无白名单、对 all 规则权限集无上限校验（manage_members/manage_settings 按设计仅管理员/管理角色持有），"all 主体不可越权"未落实（详情见问题 P2）。

### 4. 下载/流式三重闸门 — **PASS（含 P3 残余）**

核对点：allowDownload + permission + permissions 含 download。

- getDownloadUrl：`allowDownload != 0`（L332）→ `permission != 0`（L336）→ `shareAllowsDownload`（permissions 含 download；旧数据回退 permission>=1）（L340）——三重闸门齐备 ✓
- streamShareFile：`allowDownload != 0`（L424）→ `shareAllowsDownload`（L428）——功能上覆盖 permission==0（回退判定不成立即拒绝），但无 getDownloadUrl 同款显式 `permission==0` 双保险，两处闸门不对称（P3-1）。
- 权限集含 download 才允许下载；创建/更新时 permission 与 permissions 联动一致，新数据无旁路。
- 子文件下载/流式均校验 path 落在分享根边界内（isWithinShare，S-03），无同名前缀越权。
- 残余：`shareAllowsDownload` 旧值回退 `permission >= 1` 与 `legacyPermissionSet`（permission=2 → {view,upload}，不含 download）不一致，permissions 为 NULL 的遗留行可能被误判可下载（P3-1）。

## 四、问题清单

### P1（必须修复）

**[P1] 文件夹权限配置接口缺少 folderNodeId→spaceId 归属校验，任意空间管理员可跨空间写入/覆盖他人团队文件夹 ACL**

- 位置：`st-team/.../service/impl/TeamServiceImpl.java:628`（setFolderPermissions）、`:599`（getFolderPermissions）；配合 `st-team/.../service/FolderPermissionService.java:233`（按 folderNodeId 查规则，无 spaceId 过滤）与 `:261`（matches 不校验 rule.spaceId）
- 场景：攻击者自建空间成为管理员（team:create 为普通用户默认能力）后，`PUT /api/team/{ownSpace}/folder/{victimNodeId}/permissions` 提交 all/member/role 规则即可写入受害者团队的文件夹节点。读路径按 folderNodeId 收集规则且不校验规则 spaceId，受害者团队成员在该文件夹的有效权限被提升（并集只增强，如 view-only 成员获得 upload/download/delete/share）；`setPermissions` 先删后建会清空目标文件夹原有规则，造成跨团队 ACL 篡改/破坏。
- 证据：同文件其他资源接口（updateRole:862、updateMemberRole:277、setExternalMember:887）均有 `spaceId` 一致性校验，唯独文件夹权限两接口缺失。
- 建议：两接口先 `fileNodeMapper.selectById(folderNodeId)`，校验节点存在、`status==0` 且 `spaceId` 与路径参数一致；FolderPermissionService 读路径查询补 spaceId 过滤（或 matches 校验 rule.spaceId==spaceId）；补充跨空间注入回归测试。

### P2（建议同轮修复）

**[P2] "all 主体不可越权"未在服务端强制：subjectType 无白名单、all 规则权限集无上限校验**

- 位置：`TeamServiceImpl.java:630-640`；`st-team/.../dto/FolderPermissionRequest.java`（PermissionRule 仅 @NotNull rules，subjectType/permissions 无校验）；前端 `FolderPermissionDialog.tsx`（all 主体可勾选全部 9 点）
- 场景：管理员可将 all 规则提交 manage_members/manage_settings（设计为仅管理员/管理角色持有的空间级权限）及 delete/share 等权限点，全体成员在该文件夹的有效权限被提升；非法 subjectType 字符串（非 all/member/role）直接入库，永不命中但污染数据。前端无对应上限禁用，与后端同为放行。
- 建议：服务端校验 subjectType ∈ {all, member, role}（否则 400）；按设计定版 all 规则权限上限（建议排除 manage_members/manage_settings；delete/share 是否排除按产品语义确认）；校验 permissions JSON 键 ∈ 9 权限点白名单、格式合法；前端同步禁用超限权限点。

### P3（低风险，建议修复）

**[P3-1] shareAllowsDownload 旧值回退 permission>=1 与 legacyPermissionSet 映射不一致；streamShareFile 缺显式 permission==0 双保险**

- 位置：`st-share/.../service/impl/ShareServiceImpl.java:730`（shareAllowsDownload）、`:419-430`（streamShareFile）、`:658`（legacyPermissionSet case 2 → {view,upload}）
- 场景：permissions 为 NULL 的遗留行（旧客户端直写或手工数据）中 permission=2（映射为 {view,upload}，不含 download）会被回退判定为可下载；stream 路径仅 2 道显式闸门，与 getDownloadUrl 的显式 permission==0 检查不对称。实际暴露面小：35 号迁移已回填 permissions，新代码创建/更新必写 permissions。
- 建议：回退条件对齐 legacyPermissionSet（permission==1 || permission==3），或要求 permissions 非空才放行；streamShareFile 补充显式 permission==0 检查与 getDownloadUrl 对称。

**[P3-2] 租户管理员（canAccessTenant）可经 createShare 分享他人个人文件，但 effective-permissions 对其返回空集（能力不一致）**

- 位置：`ShareServiceImpl.java:92`（createShare canAccessTenant 分支）、`:166-200`（effectivePermissions 非 owner → 空集）
- 场景：dataScope>=2 的租户管理员可 API 直发创建 {view,download} 分享，但前端 effective-permissions 全部禁用，UI/API 能力不一致（codereview SP2 残余）。非越权——租户管理员本身具备数据范围权限。
- 建议：收口 createShare 个人文件分支仅 owner（与接口一致），或 effectivePermissions 对可分享管理员返回最小权限集，并在 design.md 定版。

### Minor（非安全阻断，随 TEST_PASS 处理）

- ShareDialog 有效权限加载后仅覆盖 view/download 不裁剪其余 key（`ShareDialog.tsx:137`）；组件每次打开重挂载，实际残留风险低，若复用实例切换 fileNodeId 会出现"禁用但勾选"项被提交 → 后端 403 兜底（SP3）。
- effective-permissions 加载失败静默置空集、9 项全禁用、无提示（SP4，UX 问题）。

## 五、测试覆盖缺口

1. effective-permissions 接口无自动化测试（owner / 非 owner / 未登录 / 团队非成员 / 文件不存在各分支）。
2. setFolderPermissions 无授权测试：非管理员拒绝、跨空间 folderNodeId 拒绝、all 规则权限上限拒绝、非法 subjectType 拒绝。

## 六、总体结论

```
总体评分：BLOCK（P1 必须修复后复检；P2 建议同轮修复；P3 低风险）
安全焦点结论：effective-permissions 越权 PASS；分享权限上限 PASS；setFolderPermissions BLOCK；下载/流式三重闸门 PASS（P3 残余）
```

四项中两项无越权/超权漏洞（接口空集语义、分享上限 containsAll 兜底均完备），一项存在跨空间 ACL 注入（P1），一项设计约束（all 主体不可越权）未落实（P2）。P1 修复前不得标记 SECURITY_REVIEW done；修复后需复检 P1/P2 及补充测试。

## 七、State Delta

- 新增 artifact：`.ai/docs/20260814-permission-ui/security.md`（本文档）
- exitCriteria：SECURITY_REVIEW = pending（结论 BLOCK，由主线程 Evaluate 判定）
- blockers：新增 1 项（P1 跨空间文件夹 ACL 注入）
- 风险：P1 为租户隔离缺陷，修复涉及 TeamServiceImpl + FolderPermissionService + 测试，属中型改动，需按流程定版后实施
- 下一步：主线程 Evaluate → 对 P1/P2 派发 rework（executor/implement，scope 限定 st-team 文件夹权限路径 + 测试），修复后复检 SECURITY_REVIEW
- 变更影响：本次为只读审查，无代码变更；仅新增审查文档，不影响其它模块；P1 修复将级联影响 SECURITY_REVIEW/TEST_PASS/ACCEPT 的达标判定
