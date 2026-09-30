# CR-02 程序设计

修订：design-v1；主线程自检；未实施。

## 1. 计划修改范围

| 层 | 位置与修改 |
|---|---|
| Entity | `st-team/.../entity/TeamMember.java`、`TeamInvite.java` 的 role 改 Long；TeamRole.id 已为 Long |
| DTO/VO | `InviteMemberRequest`、`CreateInviteRequest`、`TeamMemberVO`、`TeamInviteVO` 的 role 改 Long；移除邀请角色 @Max(2)，保留非负/必填语义并用业务校验替代 |
| Controller/Service | `TeamController.updateMemberRole` 的请求参数与 `TeamService`/`TeamServiceImpl` 对应签名改 Long；创建空间、转移所有者、成员邀请/接受链接、排序和角色常量适配 |
| 鉴权 | `TeamServiceImpl`、`FolderPermissionService`、`TeamFileAccessPolicyImpl` 的角色解析、比较和 ACL subject 匹配 |
| 前端 | `st-web/src/types/index.ts`、`pages/TeamSpacePage.tsx`、`pages/TeamInvitePage.tsx`、`components/team/RoleManageDialog.tsx`、`FolderPermissionDialog.tsx` |
| 数据 | team_member.role、team_invite.role 扩为 BIGINT NOT NULL DEFAULT 2；同步所有声明这两表的测试 schema |

表中 Java 简写均位于 `st-team/src/main/java/com/stcloud/team/` 下。ExternalMemberRequest 目前仅有 memberType/expireAt，不增加无需求的 role 字段；外部成员使用相同 TeamMember.role。

## 2. 角色类型与 API

字段不改名，减少 SQL/客户端迁移面。所有预设常量为 0L/1L/2L；Objects.equals 或明确 long 比较，不对 Long 装箱对象用引用相等，不调用 intValue 截断自定义 ID。FolderPermission.permission 和 legacyLevel 仍为小整数，不因同名 role 被误改。

现有 `st-common/.../config/JacksonConfig.java` 已把 Long 序列化为 String，复用该机制并核对成员/邀请/角色所有响应，不添加互相冲突的局部序列化。

请求缺省邀请角色为 2L；显式 null 拒绝。JSON role 接受十进制字符串及旧预设整数 0/1/2，拒绝浮点、科学计数形式、布尔、空串及超 Long 范围值。自定义数值 JSON 一律拒绝，避免客户端已丢精度后提交看似合法 ID；通过 DTO 局部反序列化/请求转换实现，不改变全局 Long 解析。Query role 按字符串校验后转 Long，路径和参数名不变。

前端定义 TeamRoleId=string，入口将仅有的旧预设 number 转 String，所有内部状态及提交用字符串。删除角色上的 parseInt/Number 和数组索引 roleConfig[role]，改预设字符串映射与角色 ID→名称映射。排序按预设显示顺序、自定义角色名/ID 的稳定文本顺序，不做 Snowflake 数值相减。

## 3. 统一解析与 fail-closed

在 st-team 内提供一个小型角色解析方法/组件：`resolveRole(tenantId, spaceId, roleId)` 返回 valid、preset、permissions、name。预设直接映射；自定义按 id+tenant+space+enabled+notDeleted 查询。非法引用返回 invalid，不回退 viewer。

TeamServiceImpl 的 resolveMyPermissions 与 TeamFileAccessPolicyImpl 都先校验成员、空间、外部有效期，再解析角色。invalid 时直接拒绝/false，不允许目录 all/member 规则把失效角色重新放行；有效但权限为空的角色可按现有目录规则并集计算。管理员快捷路径须在成员和角色有效性确认后执行。

两个 checkPermission 条件统一为 `level < 0 || (min != null && level > min)`。min 为 null 表示不限制正向等级，仍不接受无权限。权限点 requirePermissions 的最终集合仍按缺任一点即拒绝。

FolderPermissionService 的 subjectId 保持 Long，角色匹配直接与成员 Long role 比较；旧 resolvePermission(int spaceRole) 仅作为预设等级适配器，不允许把自定义 ID 强转塞入。ACL 显示名称按当前空间角色解析，不把未知 ID 统一标成查看者。

## 4. 写入、删除与并发

直接邀请、链接创建、链接接受、角色切换先检查管理权限/所有者限制和角色有效性，再写成员/邀请。所有涉及角色引用新增与自定义角色删除的事务按“空间行 → 角色行 → 成员/邀请行”顺序串行化，角色删除在锁内确认无成员和有效邀请引用；防止检查无引用后与新分配交错。

角色更新/删除必须确认角色属于 URL 指定空间，预设不可修改为自定义或删除。过期/撤销链接不阻止删除，但接受时必须重新验证，不能通过旧链接复活已删除角色。已有无效引用不自动改数据，只拒绝和标注。

## 5. 缓存与权限时效

事实：TeamServiceImpl.resolveMyPermissions 已调用 resolvePermissionsFresh；TeamFileAccessPolicyImpl 也采用 fresh 和请求内上下文。保留这些路径，不能退回共享 60 秒缓存。角色本身每次从数据库重新读取；角色 ID 改变即使权限集合相同，也必须按新 role 匹配目录规则。

角色/成员/规则变更的 invalidateSpace 调整到提交后执行，避免未提交时清空后旧值回填。保留清理用于非授权展示/兼容缓存，但“立即生效”的正确性由授权入口 fresh 提供，不依赖跨实例清缓存成功。核对所有仍调用旧 cached resolvePermissions 的授权调用点，将本项角色相关鉴权转 fresh；不新建全局缓存版本基础设施。

## 6. UI 与发布

交互详见 uispec.md。角色列表使用现有 `/team/{spaceId}/roles`，预设与自定义去重。角色保存后刷新选项/成员显示；提交失败保留选择，不乐观宣布成功。

发布顺序：①扩容 DB，两列保留旧值；②Web/桌面内嵌 Web 发布兼容读取 number预设|string 的版本，暂不启用自定义分配入口；③全部团队后端支持 Long 和字符串响应；④刷新客户端并开放选择。可采用同一发布窗口先排空业务、整体更新，避免永久兼容开关。已有旧桌面包需要升级，不能认为服务端扩列会自动兼容旧 UI。

回退保持 BIGINT，不缩列；一旦已有自定义成员，不能让旧 Integer 后端继续处理团队业务。回退窗口暂时限制角色写入并修复前进，不自动重分配成员。

## 7. 迁移及映射

新增递增迁移同时修改 team_member 与 team_invite，保留 NOT NULL/default/comment 的业务含义；迁移前核对 role 列类型、异常引用及行数，禁止默默把非法值强转为 2。MySQL 与 H2 双 schema 一致性按总设计执行，本文不生成 SQL 文件。

R02-1→类型/API；R02-2/6→引用写事务；R02-3/4→统一解析和门禁；R02-5→fresh；R02-7→成员边界；R02-8→UI 文档。此轮未编写测试用例、未构建、未执行数据库操作。
