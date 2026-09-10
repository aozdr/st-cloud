# Change Report — 20260814-permission-ui（前端权限 UI）

## TASK-FE-ROLE（角色管理权限点校验/微调）

### 背景

RoleManageDialog 已支持 9 权限点勾选，需按迭代设计对齐后端隐含规则（upload/download 隐含 view），并校验新建默认、编辑回填与保存提交。

### 修改文件

- `st-web/src/components/team/RoleManageDialog.tsx`（仅此一个文件）

### 修改内容

1. `togglePerm`：勾选 upload/download 时自动补 view（与后端隐含规则一致）。
2. `handleSave`：保存前归一化，upload/download 存在时强制 view=true，保证 permissions JSON 满足隐含规则。
3. `openEdit`：`role.permissions` 防御式解析（null/空串/非对象 → {}），编辑回填更稳健。
4. 权限点列表下新增提示文案「勾选上传文件或下载文件将自动包含查看文件权限」。

### 与验收标准对照

- 9 权限点齐全：view/download/upload/delete/rename/move/share/manage_members/manage_settings 全部存在 ✓
- upload/download 隐含 view：勾选自动补 + 保存归一化 ✓
- 新建默认值：view+download ✓
- 编辑回填：JSON.parse 防御式解析 ✓
- 保存提交 permissions JSON：stringify 未变形，仅按隐含规则归一化 ✓
- 未改动：types/index.ts、其它前端文件、后端代码；未创建子 Agent ✓

### 测试结果

- 静态自检通过（逻辑走查）；tsc 由主线程统一执行（validation 指定）。

### 风险

- 无行为回归；保存归一化仅补充 view 键，不影响其它权限点。

## TASK-FE-PERM-DIALOG（文件夹权限配置 UI 对齐新权限模型）

### 背景

FolderPermissionDialog 原为单值权限下拉（-1/0/1/2）+ member/role 主体，需对齐权限模型重设计：主体支持 all（全体，管理员除外）/member/role（含自定义角色），权限改为 9 权限点勾选，保存 `rules[].permissions` JSON，回显优先使用 permissions 集合。

### 修改文件

- `st-web/src/components/team/FolderPermissionDialog.tsx`（仅此一个文件）

### 修改内容

1. 主体选择：新增 `all`（全体成员，管理员除外，subjectId 传 0 兼容 DB NOT NULL）/ `member`（搜索用户，沿用）/ `role`（内置 0/1/2 + 自定义角色下拉，自定义角色来自 `/team/{spaceId}/roles`，过滤停用）。
2. 权限：单值下拉改为 9 权限点 Checkbox 组（view/upload/download/delete/rename/move/share/manage_members/manage_settings）；勾选 upload/download 自动补 view，取消 view 联动取消 upload/download，并展示提示文案。
3. 规则列表：展示主体徽标 + 权限点标签 chips；行内编辑按钮（Pencil）展开权限点编辑，删除沿用 Trash2；新增同主体规则时自动更新已有规则（去重）。
4. 保存：`PUT /team/{spaceId}/folder/{nodeId}/permissions` 的 `rules[]` 携带 `subjectType` / `subjectId`（all=0）/ `permissions`（JSON 字符串），省略 permission 由后端推导旧等级（兼容 permission NOT NULL）。
5. 回显：`parsePermissions` 防御式解析（后端返回字符串 JSON，兼容对象形态），优先用 permissions 集合展示；旧单值规则回退映射（-1→{view:false}、0→全 true、1→内容操作 true/管理 false、2→{view:true}，与 DB 迁移 34 号脚本一致）。
6. 自定义角色规则名称回退：后端 VO 仅映射内置角色名，前端对 role 且 subjectId 非 0/1/2 的规则用角色列表补名。

### 与验收标准对照

- 主体支持 all/member/role：✓（all 有「管理员除外」提示；role 含内置+自定义）
- 9 权限点勾选：✓（新增与行内编辑共用 PERMISSION_KEYS）
- 保存 payload 带 permissions：✓（全量覆盖，先删后建语义沿用）
- upload/download 勾选自动补 view：✓（勾选联动 + 提示）
- 回显优先 permissions：✓（parsePermissions + chips 展示）
- 未改动：types/index.ts、其它前端文件、后端代码；未创建子 Agent ✓

### 测试结果

- 静态自检通过：`npx tsc --noEmit -p st-web/tsconfig.app.json` exit 0；tsc/build 最终由主线程统一验证（validation 指定）。

### 风险

- 旧单值规则保存时统一转换为 permissions JSON（与 34 号迁移语义一致），历史 permission 列由后端推导保留，无行为回归。
- all 主体 subjectId 固定传 0，与后端「all 仅看 subjectType」语义一致。

## TASK-FE-SHARE-PERM（分享权限点选择 + 后端 effective-permissions 接口）

### 背景

ShareDialog 原仅有 allowDownload 开关，无法按当前用户对文件的有效权限限制可分享权限点。本次新增后端有效权限接口，前端改为 9 权限点勾选并禁用超权项，勾选结果写入请求 permissions，download 与 allowDownload 联动。

### 修改文件

- `st-share/src/main/java/com/stcloud/share/controller/ShareController.java`（新增接口）
- `st-share/src/main/java/com/stcloud/share/service/ShareService.java`（接口方法）
- `st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`（实现）
- `st-web/src/components/share/ShareDialog.tsx`（前端改造）

### 修改内容

1. 后端新增 `GET /api/share/effective-permissions?fileNodeId=`：个人文件仅本人返回 `{view:true,download:true}`，非本人/文件不存在/未登录返回空集；团队文件经 `teamService.resolveMyPermissions(spaceId, nodeId)` 返回并集，非成员（BusinessException）或无权限返回空集，不抛出。
2. ShareDialog 创建分享前调用该接口加载有效权限集，9 权限点（view/download/upload/delete/rename/move/share/manage_members/manage_settings）勾选展示，不在有效权限集内的项禁用（后端超权校验兜底）。
3. 勾选结果写入请求 `permissions`（JSON 字符串，与后端 String 契约/角色管理提交格式一致；types 仍为 Record，仅本地类型断言）；`permission` 按旧单值映射推导；`allowDownload` 与 download 联动（勾选 download → 1，否则 0）。
4. 勾选 upload/download 自动补 view、view 被依赖时不可取消；未勾选任何权限点时前端拦截提示。
5. 移除原「允许下载」Switch（由 download 权限点统一控制），结果展示沿用 allowDownload 标签。

### 与验收标准对照

- 后端个人/团队分支正确：个人 owner → view+download、非本人/未登录 → 空集；团队 → resolveMyPermissions 并集、非成员 → 空集 ✓
- 前端权限点勾选 + 超权禁用：有效权限集外的 Checkbox disabled ✓
- allowDownload 联动：download 勾选 → allowDownload=1；仅查看 → 0 ✓
- 未改动：types/index.ts、st-team/st-core 主代码、其它前端文件；未创建子 Agent ✓

### 测试结果

- 静态自检通过（代码走查 + 依赖核对）；tsc/mvn 由主线程统一串行验证（validation 指定）。

### 风险

- 前端 permissions 类型为 Record 而线上契约为 JSON 字符串：已用 JSON.stringify + 本地断言发送，与后端现有解析一致；若后续统一改对象契约需同步后端 DTO。
- 团队文件有效权限接口未做 share 前置，仅返回文件有效权限并集；能否分享仍由 createShare 的 share 前置与上限校验兜底。

## TASK-FIX-PERM-UI-MAJOR（Code Review Major 修复：S1/S2/SP1）

### 背景

Code Review（codereview.md）提出 3 项 Major：S1 类型失真（`as unknown as` 双重断言掩盖 String JSON 契约）、S2 权限点常量三处重复维护、SP1 个人文件分享权限上限口径不一致（createShare 全量 vs effective-permissions {view,download}）。本次按 TASK-FIX-PERM-UI-MAJOR 定版修复。

### 修改文件

- `st-web/src/lib/permissions.ts`（新增，权限点常量与旧值映射单源）
- `st-web/src/components/share/ShareDialog.tsx`
- `st-web/src/components/team/FolderPermissionDialog.tsx`
- `st-web/src/components/team/RoleManageDialog.tsx`
- `st-web/src/types/index.ts`（仅 CreateShareRequest.permissions 类型）
- `st-share/src/main/java/com/stcloud/share/service/impl/ShareServiceImpl.java`
- `st-share/src/test/java/com/stcloud/share/ShareServiceImplPermissionLimitIntegrationTest.java`

### 修改内容

1. **S1**：`CreateShareRequest.permissions` 类型由 `Record<string, boolean>` 改为 `string`（与后端 String JSON 契约一致）；ShareDialog 提交直接 `JSON.stringify(sharePerms)`，删除 `as unknown as` 双重断言；三组件提交处统一为 JSON 字符串。
2. **S2**：新增 `st-web/src/lib/permissions.ts`，导出 `PERMISSION_KEYS`（9 权限点，统一键序 view/upload/download/delete/rename/move/share/manage_members/manage_settings）、`legacyPermissionFromPerms`、`legacyToPermissions`（含中文注释）；FolderPermissionDialog / RoleManageDialog / ShareDialog 删除本地重复定义并统一引用，权限点常量单源。
3. **SP1**：后端个人文件分享权限上限由全量改为 `PERSONAL_EFFECTIVE_PERMS`（{view, download}），createShare 与 updateShare（resolveMyEffectivePerms）两处口径与 effective-permissions 接口一致；个人分享请求权限集 ⊆ {view,download}，超权（upload/delete 等）由服务端 SHARE_ACCESS_DENIED 拒绝；团队文件逻辑不变。

### 与验收标准对照

- 无 `as unknown as` 断言：✓（rg 全仓核验无残留）
- 权限点常量单源：✓（PERMISSION_KEYS 仅定义于 lib/permissions.ts）
- 个人分享上限 {view,download} 与接口一致：✓（createShare/updateShare 均使用 PERSONAL_EFFECTIVE_PERMS）

### 测试结果

- 静态自检通过（断言/常量/口径核验）；新增回归用例 `personalFileOverPermissionRejected`（个人文件 upload 超权被拒）；`getDownloadUrlRejectedWhenPermissionsLackDownload` 改为个人文件合法权限集（仅 view）验证下载链路拒绝。
- tsc + mvn compile 由主线程统一串行验证（validation 指定），本线程未执行构建。

### 风险

- 个人文件历史宽权限分享（permission=2/3 或含 upload/delete 的 permissions）创建/更新将被服务端拒绝——与 SP1 定版一致，属预期行为收敛；服务端为最终闸门，无越权风险。

## TASK-FIX-SEC-PERMUI-ACL（Security BLOCK P1/P2 修复：跨空间 ACL 注入 + all 越权）

### 背景

SECURITY_REVIEW（security.md）判定 BLOCK：P1 文件夹权限配置接口缺少 folderNodeId→spaceId 归属校验，任意空间管理员可跨空间写入/覆盖他人团队文件夹 ACL（攻击者自建空间成为管理员后对受害者文件夹节点提交规则）；P2 服务端未强制 all 主体权限上限（manage_members/manage_settings 越权下放）、subjectType 无白名单。本 TASK 按定版修复清单执行，scope 仅 st-team/**（写）+ security.md/design.md（读）。

### 修改文件

- `st-team/src/main/java/com/stcloud/team/service/impl/TeamServiceImpl.java`
- `st-team/src/main/java/com/stcloud/team/service/FolderPermissionService.java`
- `st-team/src/test/java/com/stcloud/team/service/TeamServicePermissionIntegrationTest.java`
- `st-team/src/test/java/com/stcloud/team/service/FolderPermissionServiceTest.java`
- `st-team/src/test/java/com/stcloud/team/service/FolderPermissionServiceRuleTest.java`

### 修改内容

1. **P1 节点归属校验（写 + 读双路径）**：
   - TeamServiceImpl 新增 `requireFolderNodeInSpace`：节点必须存在、`status==0`（isNormal）且 `spaceId` 与路径参数一致，否则抛 `TEAM_PERMISSION_DENIED("节点不属于该空间")`；`getFolderPermissions` / `setFolderPermissions` 在 `checkPermission(spaceId, 0)` 后调用（对齐同文件 updateMemberRole/updateRole/setExternalMember 的 spaceId 一致性校验模式）。
   - FolderPermissionService.matches 增加 `spaceId.equals(rule.getSpaceId())` 过滤：跨空间注入的规则一律不参与有效权限并集（读路径兜底，space_id 列 NOT NULL 无兼容风险）。
2. **P2 subjectType 白名单 + all 上限**：`setFolderPermissions` 新增 `validatePermissionRules`：subjectType ∈ {all, member, role}（非法 → BAD_REQUEST，防污染数据）；all 主体权限集禁止包含 manage_members / manage_settings（越权下放拒绝）；permissions JSON 为空时按旧单值 permission 回退映射（legacyPermissionSet）同限（如 permission=0 管理 → 回退含空间管理权限 → 拒绝）。
3. **测试补充（st-team）**：
   - 集成测试：跨空间 folderNodeId 写/读被拒且目标文件夹无规则残留（P1 回归）；all 含 manage_members / manage_settings、permissions 为空 + permission=0 回退、非法 subjectType 均被拒且无规则残留（P2 回归）；合法 all/member/role 同批规则通过并回读 spaceId 正确。
   - 单测：FolderPermissionServiceRuleTest 新增跨空间规则不参与并集用例；两个 FolderPermissionService 单测规则补 spaceId=1 对齐新语义，differentSpaceKeyIsolated 增加「跨空间规则不命中」断言。

### 与验收标准对照

- P1 节点归属校验就位：rg 复核 `requireFolderNodeInSpace` 命中两接口 + FolderPermissionService.matches spaceId 过滤 ✓
- P2 subjectType 白名单 + all 禁 manage_*（含 permissions 为 null 时回退映射同限）✓
- 新增测试覆盖：跨空间节点被拒 / all 含 manage_* 被拒 / 合法 all/member/role 规则通过 ✓
- 未改动：st-share、st-web、其它模块；未创建子 Agent ✓

### 测试结果

- 静态自检通过（代码走查 + rg 复核）；mvn 编译/测试由主线程统一串行执行（validation 指定 `mvn -q -pl st-team -am test`）。

### 风险

- matches 严格等值校验 rule.spaceId：历史数据 space_id 列 NOT NULL（21 号脚本与 H2 schema 一致），无兼容性回归。
- getFolderPermissions 展示仍按 folderNodeId 列出规则：修复前若已注入跨空间残留行，会在受害者空间规则列表可见（不参与权限计算），必要时由主线程安排数据清理。
- 前端 FolderPermissionDialog 对 all 管理权限点的禁用（security.md P2 建议的 UI 侧同步）属 st-web 范围，本 TASK scope 未含，待前端迭代同步；服务端已为最终闸门。
