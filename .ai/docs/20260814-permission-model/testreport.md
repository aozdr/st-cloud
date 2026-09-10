# 权限模型改造测试报告（TASK-PERM-TEST）

> 执行角色：tester（taskType=test）｜dispatchId: perm-test-001｜日期：2026-08-14

## 一、执行命令

```text
mvn -q -pl st-team,st-share -am test
```

- 单进程串行执行（无并行 mvn），`-am` 连带构建上游 st-common / st-auth / st-core / st-team。
- 执行结果：退出码 **0**（BUILD SUCCESS），耗时约 128 秒。
- 未修改任何业务代码；仅输出本测试报告。

## 二、各模块测试统计（Surefire 汇总）

| 模块 | 测试套件 | Tests run | Failures | Errors | Skipped |
|------|---------:|----------:|---------:|-------:|--------:|
| st-common | 5 | 25 | 0 | 0 | 0 |
| st-auth | 1 | 8 | 0 | 0 | 0 |
| st-core | 16 | 63 | 0 | 0 | 0 |
| st-team | 4 | 31 | 0 | 0 | 0 |
| st-share | 4 | 41 | 0 | 0 | 0 |
| **合计** | **30** | **168** | **0** | **0** | **0** |

## 三、权限模型关键用例核对

### 3.1 st-team（角色权限组合 + 文件夹增强，BE1）

| # | 核对项 | 覆盖测试 | 结果 |
|---|--------|----------|------|
| T1 | 权限集解析 / permissions JSON 读写 | `FolderPermissionServiceRuleTest.permissionsJsonRoundTrip`、`legacyPermissionFallback` | ✅ 通过 |
| T2 | 用户例子：上传者 {view,upload} + 文件夹 member 规则 {download} → {view,upload,download}（并集增强） | `FolderPermissionServiceRuleTest.unionOfRoleAndMemberRule`、`TeamServicePermissionIntegrationTest.resolveMyPermissions_unionsMemberRule` | ✅ 通过 |
| T3 | `all` 规则（全体成员生效） | `FolderPermissionServiceRuleTest.allRuleAppliesToEveryMember`、`TeamServicePermissionIntegrationTest.resolveMyPermissions_unionsAllRule` | ✅ 通过 |
| T4 | 自定义角色（roleId >= 100）命中 role 规则 | `FolderPermissionServiceRuleTest.roleRuleMatchesCustomRole`、`TeamServicePermissionIntegrationTest.customRolePermissionsApplied` | ✅ 通过 |
| T5 | 自定义角色权限实际生效（上传者 id=100 = view+upload） | `TeamServicePermissionIntegrationTest.customRolePermissionsApplied` | ✅ 通过 |
| T6 | 管理员直通（manage_settings 等全权限，不受文件夹规则限制） | `TeamServicePermissionIntegrationTest.adminBypassFolderRules` | ✅ 通过 |
| T7 | 查看者预设仅 view（download=false） | `FolderPermissionServiceRuleTest.presetViewerHasNoDownload`、`TeamServicePermissionIntegrationTest.viewerPresetHasNoDownload` | ✅ 通过 |
| T8 | 查看者 view 通过、download 拒绝 | `TeamServicePermissionIntegrationTest.requirePermissions_viewerCanViewButNotDownload` | ✅ 通过 |
| T9 | 祖先链向上收集规则取并集（子树继承） | `FolderPermissionServiceRuleTest.ancestorRulesCollectUnion` | ✅ 通过 |
| T10 | upload 隐含 view | `FolderPermissionServiceTest.uploadImpliesView` | ✅ 通过 |
| T11 | 规则只增强：-1 仅标注不参与并集 | `FolderPermissionServiceRuleTest.denyRuleEnhanceOnly` | ✅ 通过 |
| T12 | 禁用自定义角色回退查看者 / 非成员拒绝 | `TeamServicePermissionIntegrationTest.disabledCustomRoleFallsBackToViewer`、`nonMemberDenied` | ✅ 通过 |

### 3.2 st-share（分享权限上限，BE2）

| # | 核对项 | 覆盖测试 | 结果 |
|---|--------|----------|------|
| S1 | 分享权限 ⊆ 用户有效权限，超权（含 download）拒绝 SHARE_ACCESS_DENIED「分享权限不能超过你的权限」 | `ShareServiceImplPermissionLimitIntegrationTest.teamFileOverPermissionRejected` | ✅ 通过 |
| S2 | 发起分享需 `share` 权限点前置（缺少 → TEAM_PERMISSION_DENIED） | `ShareServiceImplPermissionLimitIntegrationTest.teamShareRequiresSharePermissionPoint`、`teamShareWithoutSharePointRejected` | ✅ 通过 |
| S3 | 个人文件默认分享 {view,download} → allow_download=1（兼容旧单值字段） | `ShareServiceImplPermissionLimitIntegrationTest.personalFileDefaultPermissionsViewAndDownload` | ✅ 通过 |
| S4 | 团队文件未传权限时默认=用户有效权限（无 download → allow_download=0） | `ShareServiceImplPermissionLimitIntegrationTest.teamFileDefaultPermissionsFromEffectivePerms` | ✅ 通过 |
| S5 | allow_download 与权限集含 download 联动（创建 + 更新，取交集） | `ShareServiceImplPermissionLimitIntegrationTest.allowDownloadLinkedWithPermissions`、`updateSharePermissionsLinkedWithAllowDownload` | ✅ 通过 |
| S6 | 下载按权限集判断：权限集不含 download → getDownloadUrl 拒绝 | `ShareServiceImplPermissionLimitIntegrationTest.getDownloadUrlRejectedWhenPermissionsLackDownload` | ✅ 通过 |
| S7 | 下载/流式双保险：allow_download=0 时 getDownloadUrl / streamShareFile 均拒绝 | `ShareServiceImplSecurityIntegrationTest.allowDownloadZeroDownloadUrlRejected`、`allowDownloadZeroStreamRejected`、`viewOnlyShareDownloadRejected` | ✅ 通过 |
| S8 | allow_download=1 时下载放行 | `ShareServiceImplSecurityIntegrationTest.allowDownloadOneDownloadUrlOk` | ✅ 通过 |

## 四、失败问题清单

无。全部 168 个测试通过（Failures=0，Errors=0）。

## 五、结论与风险提示

- 权限模型改造（角色权限组合 + 文件夹增强并集 + 分享权限上限）的自动化验证全部通过，与 design.md 第 4/5 节语义一致。
- 本报告基于 H2 集成测试 + 单测；数据库迁移（34/35 号脚本）与真实 MySQL 的 schema 一致性属于 compare-schema.ps1 门禁范围，不在本任务验证范围内，建议主线程单独核验。
- 前端权限点勾选/分享权限选择禁用逻辑未在本任务覆盖（前端不在 scope），建议由前端验证环节补充。
