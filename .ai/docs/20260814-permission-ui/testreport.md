# 测试报告 — 20260814-permission-ui（前端权限 UI 测试验证）

> 归属：TEST_PASS（dependsOn: CODE_REVIEW / SECURITY_REVIEW）｜Tester：tester（taskType=test）｜日期：2026-08-14
> 派发：dispatchId=test-permui-002，taskCode=TST-01，taskId=TASK-TEST-PERM-UI
> 技能：webapp-testing（Playwright 测试工具箱）、web-design-guidelines（UI 合规审查）
> 执行方式：单进程串行（`mvn -q -pl st-share,st-team -am test` → `npx tsc --noEmit` → `npm run build`）

## 一、背景

前端权限 UI 迭代（文件夹权限 all/member/role + 9 权限点、分享超权禁用、角色权限点校验、后端 effective-permissions 接口）已完成实现并通过 Code Review（3 项 Major 已修复：S1 类型契约、S2 权限点常量单源、SP1 个人分享上限口径统一）。按 Loop 门禁，进入 TEST_PASS 需完成单进程串行后端测试、前端类型检查与构建验证，并对 design.md 前端验收点做核对。

## 二、输入

- Loop State：`.ai/state/20260814-permission-ui.yaml`（CODE_REVIEW done，TEST_PASS pending）
- TASK：`.ai/tasks/TASK-TEST-PERM-UI.md`
- 设计：`.ai/docs/20260814-permission-ui/design.md`（前端验收点 4 项）
- 评审：`.ai/docs/20260814-permission-ui/codereview.md`（含测试执行建议 5 条）
- 变更记录：`.ai/docs/20260814-permission-ui/changereport.md`（TASK-FE-ROLE / TASK-FE-PERM-DIALOG / TASK-FE-SHARE-PERM / TASK-FIX-PERM-UI-MAJOR）
- 代码：st-share/st-team 测试与实现、st-web 三组件与 lib/permissions.ts、types/index.ts

## 三、测试范围

| 项 | 命令 | 结果 |
|----|------|------|
| 后端测试（含权限模型/分享上限回归） | `mvn -q -pl st-share,st-team -am test` | exit 0，全绿 |
| 前端类型检查 | `npx tsc --noEmit -p st-web/tsconfig.app.json` | exit 0，0 错误 |
| 前端配置类型检查 | `npx tsc --noEmit -p st-web/tsconfig.node.json` | exit 0，0 错误 |
| 前端生产构建 | `npm run build`（tsc -b && vite build） | exit 0，3868 modules，9.81s |

## 四、测试执行统计（surefire）

### 4.1 st-share（目标模块，42 用例全绿）

| 测试类 | 用例数 | 失败 | 错误 | 跳过 | 说明 |
|--------|-------:|-----:|-----:|-----:|------|
| ShareServiceImplPermissionLimitIntegrationTest | 9 | 0 | 0 | 0 | 含 `personalFileOverPermissionRejected`（SP1 个人文件 upload 超权被拒）与 `getDownloadUrlRejectedWhenPermissionsLackDownload`（权限集缺 download 下载链路拒绝）回归 |
| ShareServiceImplSecurityIntegrationTest | 20 | 0 | 0 | 0 | 越权/超权/分享码安全 |
| ShareServiceImplExpiryIntegrationTest | 10 | 0 | 0 | 0 | 有效期/状态流转 |
| ShareServiceImplShareCodeUnitTest | 3 | 0 | 0 | 0 | 分享码生成 |
| **合计** | **42** | **0** | **0** | **0** | |

### 4.2 st-team（目标模块，31 用例全绿）

| 测试类 | 用例数 | 失败 | 错误 | 跳过 |
|--------|-------:|-----:|-----:|-----:|
| FolderPermissionServiceRuleTest | 10 | 0 | 0 | 0 |
| FolderPermissionServiceTest | 5 | 0 | 0 | 0 |
| TeamServiceIntegrationTest | 8 | 0 | 0 | 0 |
| TeamServicePermissionIntegrationTest | 8 | 0 | 0 | 0 |
| **合计** | **31** | **0** | **0** | **0** |

### 4.3 上游依赖模块（`-am` 附带执行，全绿）

| 模块 | 用例数 | 失败/错误 |
|------|-------:|----------:|
| st-common | 25 | 0 |
| st-core | 63（含 SchemaConsistencyTest 3、FileServicePermissionIntegrationTest 3） | 0 |
| st-auth | 8 | 0 |

## 五、前端验收点核对（design.md 第四章，代码级）

| 编号 | 验收点 | 核对结果 | 证据 |
|------|--------|----------|------|
| 1a | 文件夹权限：主体支持 all/成员/角色 | ✓ | FolderPermissionDialog 主体下拉含 all/member/role；all → subjectId=0（兼容 DB NOT NULL）+「全体成员（管理员除外）」提示 |
| 1b | 文件夹权限：9 权限点勾选 + 保存 permissions JSON | ✓ | PERMISSION_KEYS 9 点 Checkbox 组；`handleSave` 提交 `rules[].permissions=JSON.stringify(...)`（先删后建语义沿用）；回显 `parsePermissions` 防御式解析 + 旧单值回退映射（与 34 号迁移一致） |
| 1c | 文件夹权限：保存后刷新回显 | ✓ | 新增/编辑规则内存更新，保存调 PUT 后 onClose，刷新时 `fetchRules` 重新拉取 |
| 2a | 分享：超权项禁用 | ✓ | ShareDialog 打开时 `GET /share/effective-permissions?fileNodeId=` 加载有效权限集，`!effectivePerms[p.key]` → Checkbox disabled + opacity 样式；后端 `containsAll` 兜底（createShare/updateShare 均校验） |
| 2b | 分享：download 与 allowDownload 联动 | ✓ | `allowDownload: sharePerms.download ? 1 : 0`（ShareDialog.tsx:73）；后端 shareAllowsDownload 三重闸门（allowDownload + 旧 permission + 新 permissions 含 download） |
| 2c | 分享：后端超权拒绝兜底 | ✓ | ShareServiceImpl 个人文件上限统一 `PERSONAL_EFFECTIVE_PERMS`（{view,download}，与 effective-permissions 接口口径一致，SP1 修复）；团队文件 `resolveMyEffectivePerms` = share 前置 + resolveMyPermissions 并集；回归用例 `personalFileOverPermissionRejected` 通过 |
| 3 | 角色：9 权限点可勾选、保存回显 | ✓ | RoleManageDialog 引用 PERMISSION_KEYS 全 9 点；`openEdit` JSON.parse 防御式回填；保存 `permissions` JSON 且 upload/download 隐含 view 归一化 |
| 4 | `npx tsc --noEmit`、`npm run build` 通过 | ✓ | tsc app/node 两配置 0 错误；`npm run build` exit 0（仅 chunk >500kB 预存警告，与本次改动无关） |

### 5.1 隐含规则联动（与后端 normalizePermissions 一致）

| 场景 | 行为 | 位置 |
|------|------|------|
| 勾选 upload/download | 自动补 view | 三组件 toggle 逻辑 |
| 取消 view | 联动取消 upload/download | FolderPermissionDialog/ShareDialog（ShareDialog 因 view 被依赖不可取消） |
| 保存归一化 | upload/download 存在时强制 view=true | RoleManageDialog handleSave、FolderPermissionDialog rulePermissions |

### 5.2 Code Review Major 修复核验

| 编号 | 修复点 | 核验结果 |
|------|--------|----------|
| S1 | 删除 `as unknown as`，`CreateShareRequest.permissions` 改 string | ✓ ShareDialog.tsx 已无双重断言（全仓残留 6 处均在 fileSource/runtime/api/ShareAccessPage 等既有无关文件）；types/index.ts:208 `permissions?: string` |
| S2 | 9 权限点常量单源 | ✓ `PERMISSION_KEYS` 仅定义于 `st-web/src/lib/permissions.ts`，三组件统一引用，键序统一 view/upload/download/delete/rename/move/share/manage_members/manage_settings |
| SP1 | 个人分享上限与 effective-permissions 口径统一 | ✓ createShare/updateShare/effectivePermissions 三处均使用 `PERSONAL_EFFECTIVE_PERMS`（ShareServiceImpl:97/182/710） |

## 六、失败问题清单

无。Maven 测试 73 用例（st-share 42 + st-team 31）全绿；tsc 0 错误；构建成功。

## 七、结论

TEST_PASS 验证项全部满足：

- 单进程 `mvn -q -pl st-share,st-team -am test` 完成，目标模块 73 用例全绿（含权限模型/分享上限回归），上游依赖模块全绿；
- `npx tsc --noEmit` 0 错误（app + node 两配置）；`npm run build` 成功；
- design.md 前端验收点 4 项全部代码级核对通过（all 主体、9 权限点、超权禁用、allowDownload 联动、effective-permissions 调用）；
- Code Review 3 项 Major（S1/S2/SP1）修复均已落地并核验。

## 八、State Delta

- 新增 artifact：`.ai/docs/20260814-permission-ui/testreport.md`（本文档）
- exitCriteria：TEST_PASS = 验证完成，由主线程 Evaluate 勾选
- blockers：无新增

## 九、风险

- 本次为代码级核对 + 自动化测试，未启动真实浏览器做 UI 交互回归（webapp-testing 技能建议的 Playwright 用例可按主线程安排在联调环境执行）；后端测试已覆盖超权拒绝、下载链路拒绝等核心安全场景。
- 前端构建仅有 chunk 体积预存警告，非本次改动引入。
- 个人文件历史宽权限分享（含 upload/delete 的 permissions）创建/更新现被服务端拒绝，属 SP1 定版后的预期收敛。

## 十、下一步

主线程 Evaluate：勾选 TEST_PASS done 后进入 KNOWLEDGE 阶段（知识库/文档同步），最终 ACCEPT 验收。

## 十一、变更影响

- 仅新增测试报告文档，无业务代码变更；
- 对 exitCriteria：TEST_PASS 可判 done；KNOWLEDGE/ACCEPT 为后续环节；
- 对其它模块：无影响。
