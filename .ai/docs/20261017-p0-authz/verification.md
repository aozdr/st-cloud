# 20261017 P0 授权问题修复 — 验证记录

## 变更清单

### 生产代码

| 文件 | 变更 |
| --- | --- |
| `st-preview/.../PreviewServiceImpl.java` | `getFileNode` 增加资源级鉴权：租户/存在性 → 团队走 `TeamFileAccessPolicy.canView`（缺失即拒绝）→ 个人必须 owner → 祖先链可访问 → 排除文件夹 |
| `st-share/.../ShareServiceImpl.java` | `isWithinShare` 改为 parentId 祖先链 + 同租户/同归属范围判定，新增 `sameShareScope` 与最大深度 64 |
| `st-core/.../editor/EditorCallbackServiceImpl.java` | 回调 JWT 必须带 `key` claim，且与请求 key、目标 nodeId 绑定；status 不一致仍拒绝 |
| `st-team/.../FolderPermissionService.java` | `legacyLevelOf` 不再把 `manage_members` 映射为管理员等级 0（降为查看等级 2），`manage_settings` 仍为 0 |
| `st-team/.../TeamServiceImpl.java` | 新增 `checkMemberManage` / `isSpaceOwner` / `isBuiltInAdmin` / `canGrantAdmin`；成员类入口改用成员管理门禁；禁止改自己角色；只有拥有者/内置管理员可授予 `role=0`；移除成员时禁止移除拥有者 |

### 测试代码（新增/修改）

| 文件 | 用例 |
| --- | --- |
| `st-preview/.../PreviewAccessControlIntegrationTest.java`（新增） | 4 条：本人可预览、他人被拒且不读存储、团队策略拒绝、团队策略放行 |
| `st-preview/.../AbstractPreviewIntegrationTest.java`（改） | 增加可指定 `spaceId` 的节点构造重载 |
| `st-share/.../ShareSubtreeOwnershipIntegrationTest.java`（新增） | 3 条：同名目录下载越权、列目录越权、真实子树不误伤 |
| `st-core/.../EditorCallbackIntegrationTest.java`（改） | 新增 2 条：config token 无 key claim 被拒、token 绑定他文件的 key 被拒 |
| `st-team/.../TeamRoleEscalationIntegrationTest.java`（新增） | 6 条：自我提权、改设置、授予管理员/邀请管理员、移除 owner、正常邀请、owner 授权 |

## 编译验证

- 命令：`mvn -o -DskipTests -pl st-core,st-preview,st-share,st-team -am test-compile`
- 结果：**BUILD SUCCESS**（Parent / Common / Auth / Core / Search / Team / Preview / Share 全部 SUCCESS，测试源码一并编译通过）
- 说明：首次编译因工作区构建目录的 Windows 写入限制失败（非代码问题），随后在同一环境修复权限并重跑；过程中修正了一处测试辅助方法与基类 protected 方法同名导致的可见性冲突。

## 测试执行

环境：JDK 17.0.19（Temurin）+ Maven 3.9.11（PATH 无 mvn，使用 IntelliJ 内置 Maven），`-o` 离线。

### 1. 定向回归（4 个目标用例类）

```bash
mvn -o -B -pl st-core,st-preview,st-share,st-team -am test \
  -Dtest='EditorCallbackIntegrationTest,PreviewAccessControlIntegrationTest,ShareSubtreeOwnershipIntegrationTest,TeamRoleEscalationIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：**BUILD SUCCESS**，23 个用例全通过（Core 10 / Team 6 / Preview 4 / Share 3），0 failure、0 error、0 skip。

### 2. 受影响模块全量回归

```bash
mvn -o -B -pl st-core,st-preview,st-share,st-team -am test
```

结果：**BUILD SUCCESS**，741 个用例，0 failure、0 error、53 skip（均为需真实 MySQL/ES 的用例按条件跳过）。

| 模块 | 用例数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| st-common | 35 | 0 | 0 | 0 |
| st-auth | 38 | 0 | 0 | 7 |
| st-core | 285 | 0 | 0 | 17 |
| st-search | 77 | 0 | 0 | 5 |
| st-team | 175 | 0 | 0 | 5 |
| st-preview | 18 | 0 | 0 | 0 |
| st-share | 113 | 0 | 0 | 19 |

日志：`.ai/runtime/test-p0-authz.log`、`.ai/runtime/test-p0-authz-full.log`。运行时 Maven 判定 class 已是最新、未重编译；已核对 4 个测试类与 5 个生产类的 `.class` 时间戳均晚于当前源码，确认执行的是本次改动的代码。

定向用例与修复点的对应关系（修复前会失败）：预览越权用例在旧实现下会读到对象存储；分享子树的 2 条越权用例在 path 前缀判定下会返回预签名 URL；team 的 6 条用例在 `manage_members→0` 映射下会提权成功；Core 的 2 条在"claim 存在才比较"逻辑下会落盘成功。

## 风险与未覆盖

1. **P0-4 行为变化**：只含 `manage_members` 的自定义角色不再能进入空间设置、角色 CRUD、文件夹 ACL；成员管理能力保留。需确认前端对这类角色的菜单可见性与后端一致。
2. **P0-3 兼容性**：要求回调 token 顶层含 `key`。OnlyOffice 回调 body 必然包含 `key`；若线上使用非常规版本不签发该字段，保存会被拒绝（需在联调环境确认一次真实回调）。
3. **P0-2 性能**：每次子树判定最多 64 次主键查询（实际深度通常 < 10），分享访问是低频接口，可接受。
4. 未处理（P1，另行排期）：所有权移交缺空间写锁、`team_member` 唯一键不含 `deleted` 导致移除后无法重新加入。
