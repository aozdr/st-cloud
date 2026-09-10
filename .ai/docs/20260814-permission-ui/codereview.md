# 代码 Review 记录 — 20260814-permission-ui（前端权限 UI）

> 归属：CODE_REVIEW（dependsOn: IMPLEMENTED）｜Reviewer：reviewer（taskType=review）｜日期：2026-08-14
> 派发：dispatchId=review-permui-001，taskId=TASK-REVIEW-PERM-UI
> 技能：code-review（两轴：Standards + Spec）；因信封 forbidSpawn=true，并行子代理流程降级为单 Agent 串行执行两轴
> 基线说明：工作区无按迭代分离的提交（git log 无本迭代 commit），按 TASK 审查范围 + changereport 声明改动对照当前代码审查

# 一、Review 概览

## 基本信息

```
功能名称：前端权限 UI 对齐权限模型（文件夹权限 all/member/role + 9 权限点、分享超权禁用、角色权限点校验、后端 effective-permissions 接口）
Review 范围：
  - st-web/src/components/team/FolderPermissionDialog.tsx
  - st-web/src/components/team/RoleManageDialog.tsx
  - st-web/src/components/share/ShareDialog.tsx
  - st-web/src/types/index.ts（CreateShareRequest / FolderPermissionItem）
  - st-share：ShareController / ShareService / ShareServiceImpl（GET /api/share/effective-permissions + 分享权限集校验）
涉及模块：st-web 团队/分享 UI、st-share 分享服务、st-team 权限服务（契约核对）
修改文件：见 .ai/docs/20260814-permission-ui/changereport.md（TASK-FE-ROLE / TASK-FE-PERM-DIALOG / TASK-FE-SHARE-PERM）
对照文档：.ai/docs/20260814-permission-ui/design.md、.ai/docs/20260814-permission-model/design.md、changereport.md
```

## 两轴结论摘要

- **Standards（标准符合度）**：共 5 项发现（Major 2 / Minor 2 / Suggestion 1）。最重问题：ShareDialog 用 `as unknown as` 双重断言掩盖前端类型与后端 String JSON 契约不符；9 权限点常量在 3 个组件重复维护。
- **Spec（需求/设计符合度）**：共 5 项发现（Major 1 / Minor 3 / Suggestion 1）。最重问题：个人文件"有效权限"在 effective-permissions 接口（{view,download}）与 createShare 上限（全量）两处定义不一致。
- 安全轴：无越权/超权漏洞，后端超权兜底完备（详见第五节）。

# 二、代码结构检查

通过：
- 组件职责单一：FolderPermissionDialog（文件夹规则配置）/ RoleManageDialog（角色管理）/ ShareDialog（分享创建）各司其职，未发现职责越界。
- 后端接口分层清晰：ShareController 仅参数接收，业务在 ShareServiceImpl；权限点计算复用 st-team 的 FolderPermissionService/TeamService，无重复实现。
- 复用已有能力：前端 api 封装（Result 解包）、Toast、cn 工具均正确复用。

问题：
- 权限点常量与映射在 3 个组件重复维护（详见问题 S2），属 Fowler Duplicated Code + Shotgun Surgery。
- ShareDialog 对类型契约做了"本地断言"绕过（详见问题 S1），类型层失真。

建议：
- 权限点常量/映射收敛到 `st-web/src/lib/permission.ts`（已有权限工具模块）。
- 类型定义与线上契约（String JSON / Long subjectId）对齐，消除强转。

# 三、后端代码检查

## Controller 层

- 只负责参数接收：是。`effectivePermissions(Long fileNodeId)` 直接委托 Service，无业务逻辑。
- 认证/授权：接口位于需认证区（SecurityConfig 仅 `/api/share/access/**` permitAll，其余 anyRequest().authenticated()）；未加 `@PreAuthorize` 与同类只读接口（listShares）一致，无越权。

## Service 层

- 业务逻辑合理：是。`effectivePermissions` 个人文件仅 owner 返回 {view,download}、非 owner/未登录/文件不存在返回空集；团队文件走 `resolveMyPermissions`（成员校验 + 角色 ∪ 文件夹规则并集），非成员 BusinessException 捕获后返回空集，不抛出。
- 事务问题：`effectivePermissions` 为只读，无需事务；`createShare`/`updateShare` 有 `@Transactional`。
- 分享权限上限兜底：`createShare`/`updateShare` 均执行 `myPerms.containsAll(sharePerms)` 超权拒绝（"分享权限不能超过你的权限"），服务端为最终闸门，前端禁用仅为 UX。

## 数据访问层

- SQL 合理：MyBatis-Plus LambdaQueryWrapper 参数化，无拼接注入。
- 慢查询风险：无。effective-permissions 单节点查询；权限并集父链遍历（最多 20 层）有 60s TTL 缓存兜底。

# 四、前端代码检查

- 组件职责/状态管理：合理。ShareDialog 用 `cancelled` 标志防卸载后 setState；FolderPermissionDialog 编辑态与新增态分离。
- 重复实现：PERMISSION_KEYS 三处重复（FolderPermissionDialog:17 / RoleManageDialog:10 / ShareDialog:15），且键序不一致（Folder 为 view/upload/download，另两处为 view/download/upload），展示顺序不统一。
- 防御式解析：FolderPermissionDialog `parsePermissions` 兼容字符串 JSON 与对象，RoleManageDialog `openEdit` 对 null/空串/坏 JSON 兜底，符合"后端返回字符串 JSON"现状。
- 隐含规则联动：FolderPermissionDialog 勾选 upload/download 自动补 view、取消 view 联动取消 upload/download；RoleManageDialog 勾选时自动补 view（保存时归一化）；ShareDialog 勾选时自动补 view、view 被依赖时不可取消。行为与后端 normalizePermissions 一致。
- UI 细节：规则列表 `key={idx}` 用数组索引（问题 S4）；ShareDialog 有效权限加载失败无提示（问题 SP4）。

# 五、安全检查

- effective-permissions 越权：**通过**。个人文件仅本人（owner）返回 {view,download}，非本人/未登录/文件不存在 → 空集；团队文件仅空间成员可得自身权限集，非成员 → 空集。接口不泄露他人数据。
- 分享超权兜底：**通过**。服务端 `myPerms.containsAll(sharePerms)` 双重校验（createShare + updateShare），前端禁用只是第一层。
- 下载/流式闸门：**通过**。getDownloadUrl 与 streamShareFile 均有三重判断：`allowDownload != 0` + 旧 `permission >= 1` + 新 `permissions` 集含 `download`（shareAllowsDownload），堵住绕过下载 URL 的流式链路。
- 参数/注入：**通过**。全链路参数化查询；分享码 SecureRandom 32 字符集（排除 0/O/1/I）+ 唯一索引冲突重试。
- 残余风险：个人文件分享权限上限（后端全量 vs 接口 {view,download}）不一致本身不构成越权（公共访问侧仅消费 download/流式，其余权限点为惰性元数据），但属设计边界漂移（见 SP1）。

# 六、性能检查

- 查询次数：ShareDialog 每次打开仅 1 次 `/share/effective-permissions`；FolderPermissionDialog 2 次（规则 + 角色）。
- 权限计算：团队文件有效权限走父链并集计算，60s TTL 缓存；规则变更 `invalidateSpace` 失效，设计合理。
- 前端渲染：权限点列表规模固定（9 项），无性能问题。

# 七、测试检查

- TESTCASES 已 done（前端验收点已列）；主线程已串行验证 `npx tsc --noEmit` 与 `mvn compile`。
- 建议后续测试执行覆盖（供 TEST_PASS 核对）：
  1. 团队 view-only 成员分享：仅 view 可勾选、提交 `{"view":true}` 成功、allowDownload=0；
  2. 个人文件 owner 分享：仅 view/download 可勾选；download 勾选 → allowDownload=1；
  3. 非 owner/非成员打开分享：全部禁用，提交被后端拒绝；
  4. 超权提交（API 直发含 download 的 permissions，实际无 download）→ 403 兜底；
  5. 角色管理：勾选 upload 自动补 view 并保存回显。

# 八、问题清单

## Standards（标准符合度）

| 编号 | 问题 | 等级 | 位置 | 建议 |
|------|------|------|------|------|
| S1 | `permissions` 线上契约为 String JSON，但 `CreateShareRequest.permissions` 声明为 `Record<string, boolean>`，ShareDialog 用 `JSON.stringify(...) as unknown as Record<string, boolean>` 双重断言掩盖类型失真 | Major | ShareDialog.tsx:91；types/index.ts:208 | 将类型改为 `permissions?: string`，ShareDialog 直接 `JSON.stringify(sharePerms)`，删除 `as unknown as` |
| S2 | 9 权限点常量（key+label）在 3 个组件重复定义且键序不一致，新增/调整权限点需改 3 个文件，存在漂移风险 | Major | FolderPermissionDialog.tsx:17；RoleManageDialog.tsx:10；ShareDialog.tsx:15 | 收敛到 `st-web/src/lib/permission.ts` 导出统一 `PERMISSION_KEYS` 与旧值映射 |
| S3 | `FolderPermissionItem.subjectId: string`（后端 Long→number）、`permissions?: Record<string, boolean>`（后端 String JSON）与实际契约不符 | Minor | types/index.ts:595,597 | 类型对齐线上契约（subjectId 可 number；permissions 为 string 或兼容联合类型） |
| S4 | 规则列表 `key={idx}` 用数组索引，删除时手工调整 editingIdx，重排/删除场景易错位 | Minor | FolderPermissionDialog.tsx:221 | 改用稳定标识（subjectType+subjectId 或后端 id）作 key |
| S5 | `window.confirm` 与仓库全局 ConfirmDialog/ConfirmProvider 约定不一致（既有代码，非本次改动） | Suggestion | RoleManageDialog.tsx:59 | 后续统一替换为 ConfirmProvider |

## Spec（需求/设计符合度）

| 编号 | 问题 | 等级 | 位置 | 建议 |
|------|------|------|------|------|
| SP1 | 个人文件"有效权限"定义不一致：effective-permissions 对 owner 返回 {view,download}（design.md 3.4），而 createShare/updateShare 上限为 PERSONAL_FULL_PERMS 全量 → UI 只能创建 view/download 分享，API 却可提交更宽权限集，"分享权限 ⊆ 有效权限"（permission-model design 5.2）在两处口径不同 | Major | ShareServiceImpl effectivePermissions / createShare（PERSONAL_FULL_PERMS） | 后端个人文件上限与接口统一（建议改为 PERSONAL_EFFECTIVE_PERMS，或接口对 owner 返回全量），并在 design.md 定版 |
| SP2 | 租户管理员（canAccessTenant）可经 createShare 分享他人个人文件，但 effective-permissions 对非 owner 返回空集 → UI 全部禁用、无法创建，能力不一致 | Minor | ShareServiceImpl effectivePermissions / createShare | 收口 createShare 个人文件分支仅 owner（与接口一致），或接口对可分享管理员返回最小权限集 |
| SP3 | 有效权限加载后 `setSharePerms(prev => ({...prev, view, download}))` 只覆盖不裁剪；当前每次打开重新挂载无残留，但若复用实例切换 fileNodeId 会出现"禁用但勾选"项被提交、后端 403 | Minor | ShareDialog.tsx:137 | 按 effective 集合重建 sharePerms（裁剪不在集合内的 key） |
| SP4 | effective-permissions 加载失败（catch 置空集）无用户提示，9 项静默禁用，提交时才报"请至少勾选一项" | Minor | ShareDialog.tsx:130-142 | catch 分支 showToast 提示失败原因 |
| SP5 | 取消 view 时 RoleManageDialog 不同步取消 upload/download（仅保存归一化补回），与 FolderPermissionDialog 联动交互不一致 | Suggestion | RoleManageDialog.tsx:63 | 统一两处取消联动语义 |

# 九、Review 结论

```
总体评分：PASS（无 Critical / 无功能或安全阻断项）
是否建议合并：建议合并，但 S1/S2/SP1 三项 Major 建议在 TEST_PASS 前处理或排入后续 rework
必须修改项：无（BLOCK 级）
优化建议：
  - S1：types/index.ts 与 ShareDialog 的类型契约对齐（删除 as unknown as）
  - S2：权限点常量收敛到 lib/permission.ts
  - SP1：个人文件分享权限上限与 effective-permissions 口径统一
```

## 对照验收点核对（TASK-REVIEW-PERM-UI）

1. all 主体：FolderPermissionDialog 支持 all（subjectId=0）+「管理员除外」提示 ✓
2. 9 权限点：3 组件均齐全；key 与后端 FolderPermissionService 9 权限点一致 ✓（键序展示不一致见 S2）
3. 超权禁用：ShareDialog 按 effectivePerms 禁用 + 后端 containsAll 兜底 ✓
4. allowDownload 联动：download 勾选 → allowDownload=1，后端与权限集取交集 ✓
5. 后端接口分支：个人（owner→{view,download}）、团队（resolveMyPermissions）、未授权（空集）✓（口径不一致见 SP1）
6. 旧值映射：前端 legacyToPermissions 与 34 号迁移脚本逐项一致（-1→{view:false}、0→全 true、1→内容操作、2→{view}）✓

# 附：State Delta

- 新增 artifact：`.ai/docs/20260814-permission-ui/codereview.md`（本文档）
- exitCriteria：CODE_REVIEW = pending（结论 PASS，由主线程 Evaluate 勾选）
- blockers：无新增
- 风险：S1/S2/SP1 为代码质量与设计口径项，不影响当前功能正确性与安全性
- 下一步：主线程 Evaluate；建议将 S1/S2/SP1 排入 rework 或随 TEST_PASS 阶段处理；随后进入 SECURITY_REVIEW
- 变更影响：无代码变更（review 只读）；仅新增审查文档，不影响其它模块/exitCriteria
