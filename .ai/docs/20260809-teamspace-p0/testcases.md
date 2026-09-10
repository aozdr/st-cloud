# 测试用例：团队空间 P0 基础协作补齐

> 归属 exitCriteria: TESTCASES（依赖 TECH_DESIGN）
> 关联文档: requirement.md / design.md

## 测试范围

覆盖 P0 全部 5 项需求的正常流程、边界条件、权限校验、异常场景。

---

## TC-P0-1 邀请链接机制

### TC-1.1 管理员生成邀请链接（正常）
- **前置**：用户 A 为空间管理员（role=0），空间 S 存在
- **步骤**：A 调用 `POST /api/team/S/invite`，body=`{role:2, expireAt:"2026-08-10T00:00:00"}`
- **预期**：返回 200，含 inviteCode（32位）、role=2、status=1；team_invite 表新增记录；team_activity 新增 INVITE_CREATE 记录

### TC-1.2 非管理员生成邀请链接（权限拒绝）
- **前置**：用户 B 为空间查看者（role=2）
- **步骤**：B 调用 `POST /api/team/S/invite`
- **预期**：返回 4004 TEAM_PERMISSION_DENIED

### TC-1.3 查看邀请链接列表
- **前置**：空间 S 有 2 条有效邀请 + 1 条已撤销
- **步骤**：管理员调用 `GET /api/team/S/invites`
- **预期**：返回 3 条记录，含状态、角色、创建时间、过期时间

### TC-1.4 撤销邀请链接
- **前置**：邀请链接 L 有效
- **步骤**：管理员调用 `DELETE /api/team/S/invite/L`
- **预期**：返回 200；L.status 变为 0；team_activity 新增 INVITE_REVOKE 记录

### TC-1.5 通过邀请码加入（正常-未登录场景）
- **前置**：邀请码 C 有效，用户 D 未登录
- **步骤**：D 访问 `/team/invite/C`
- **预期**：前端引导登录；登录后 POST `/api/team/invite/C` 返回成功 + spaceId；team_member 新增 D 记录；team_activity 新增 MEMBER_JOIN

### TC-1.6 通过邀请码加入（已是成员）
- **前置**：用户 D 已是空间 S 成员，邀请码 C 有效
- **步骤**：D 调用 `POST /api/team/invite/C`
- **预期**：返回成功但标识"已是成员"；team_member 不新增记录；前端跳转空间页

### TC-1.7 邀请码不存在
- **步骤**：调用 `POST /api/team/invite/INVALID_CODE`
- **预期**：返回 4005 TEAM_INVITE_NOT_FOUND

### TC-1.8 邀请码已过期
- **前置**：邀请码 C 的 expireAt 已过
- **步骤**：调用 `POST /api/team/invite/C`
- **预期**：返回 4006 TEAM_INVITE_EXPIRED

### TC-1.9 邀请码已撤销
- **前置**：邀请码 C 的 status=0
- **步骤**：调用 `POST /api/team/invite/C`
- **预期**：返回 4005 TEAM_INVITE_NOT_FOUND

### TC-1.10 永久邀请链接
- **前置**：生成邀请链接时 expireAt=null
- **步骤**：任意时间调用 `POST /api/team/invite/C`
- **预期**：成功加入，不因时间过期

---

## TC-P0-2 空间设置完善

### TC-2.1 编辑空间名称（正常）
- **前置**：用户 A 为空间管理员
- **步骤**：A 调用 `PUT /api/team/S`，body=`{spaceName:"新名称"}`
- **预期**：返回 200；team_space.space_name 更新；team_activity 新增 SPACE_UPDATE

### TC-2.2 编辑空间描述和图标
- **步骤**：A 调用 `PUT /api/team/S`，body=`{description:"新描述", icon:"🚀"}`
- **预期**：字段更新成功

### TC-2.3 非管理员编辑空间设置
- **前置**：用户 B 为查看者
- **步骤**：B 调用 `PUT /api/team/S`
- **预期**：返回 4004 TEAM_PERMISSION_DENIED

### TC-2.4 空间名称为空
- **步骤**：A 调用 `PUT /api/team/S`，body=`{spaceName:""}`
- **预期**：返回 400 BAD_REQUEST（@NotBlank 校验）

### TC-2.5 前端设置表单提交
- **步骤**：在设置弹窗填写名称+描述+选择emoji+配额，点击保存
- **预期**：调 PUT 接口提交全部字段；列表页和详情页显示更新后的名称/描述/图标

---

## TC-P0-3 空间活动日志

### TC-3.1 查看活动日志（正常）
- **前置**：空间 S 有活动记录
- **步骤**：成员调用 `GET /api/team/S/activities?page=1&size=20`
- **预期**：返回活动列表，按 created_at 倒序，含操作人昵称/头像/操作类型/目标名称/时间

### TC-3.2 查看者可查看活动日志
- **前置**：用户 B 为查看者
- **步骤**：B 调用 `GET /api/team/S/activities`
- **预期**：返回 200（查看者可读）

### TC-3.3 非成员不可查看
- **前置**：用户 E 不是空间 S 成员
- **步骤**：E 调用 `GET /api/team/S/activities`
- **预期**：返回 4004 TEAM_PERMISSION_DENIED

### TC-3.4 活动日志按类型筛选
- **步骤**：调用 `GET /api/team/S/activities?filter=FILE`
- **预期**：仅返回 action 以 FILE_ 开头的记录

### TC-3.5 文件操作产生活动记录
- **步骤**：成员创建文件夹 -> 检查 team_activity 表
- **预期**：新增 FOLDER_CREATE 记录，含文件夹名称

### TC-3.6 成员操作产生活动记录
- **步骤**：管理员邀请成员 -> 检查 team_activity
- **预期**：新增 MEMBER_INVITE 记录

### TC-3.7 活动日志分页
- **前置**：空间 S 有 25 条活动记录
- **步骤**：调用 size=20，再调用 page=2
- **预期**：第一页 20 条，第二页 5 条

### TC-3.8 前端动态 Tab 切换
- **步骤**：在空间详情页点击「动态」Tab
- **预期**：文件浏览器隐藏（不卸载），动态列表展示；切回「文件」Tab 文件浏览状态保留

---

## TC-P0-4 成员退出与所有权移交

### TC-4.1 普通成员退出空间（正常）
- **前置**：用户 B 为查看者，空间有多名成员
- **步骤**：B 调用 `POST /api/team/S/leave`
- **预期**：返回 200；team_member 删除 B 记录；team_activity 新增 MEMBER_LEAVE；B 再访问空间返回 4004

### TC-4.2 管理员退出空间（非拥有者，有其他管理员）
- **前置**：用户 A 为管理员（非拥有者），空间有另一名管理员
- **步骤**：A 调用 `POST /api/team/S/leave`
- **预期**：返回 200，成功退出

### TC-4.3 管理员退出空间（最后一个管理员）
- **前置**：用户 A 是空间唯一管理员
- **步骤**：A 调用 `POST /api/team/S/leave`
- **预期**：返回 4007 TEAM_LAST_ADMIN

### TC-4.4 拥有者退出空间（未移交）
- **前置**：用户 A 是空间拥有者
- **步骤**：A 调用 `POST /api/team/S/leave`
- **预期**：返回错误提示"请先移交所有权"

### TC-4.5 移交所有权（正常）
- **前置**：用户 A 是拥有者，用户 B 是管理员（role=0）
- **步骤**：A 调用 `POST /api/team/S/transfer`，body=`{targetMemberId: B的memberId}`
- **预期**：返回 200；team_space.owner_id 变为 B.userId；team_activity 新增 SPACE_TRANSFER；A 仍为管理员

### TC-4.6 非拥有者移交所有权
- **前置**：用户 B 为管理员（非拥有者）
- **步骤**：B 调用 `POST /api/team/S/transfer`
- **预期**：返回 4004 TEAM_PERMISSION_DENIED

### TC-4.7 移交给非管理员
- **前置**：用户 C 为查看者（role=2）
- **步骤**：拥有者 A 调用 transfer，targetMemberId=C
- **预期**：返回 4008 TEAM_TRANSFER_TARGET_INVALID

### TC-4.8 移交给非本空间成员
- **步骤**：拥有者 A 调用 transfer，targetMemberId=其他空间的memberId
- **预期**：返回 4003 TEAM_MEMBER_NOT_FOUND

### TC-4.9 退出后文件归属不变
- **前置**：用户 B 上传了文件 F 到空间 S
- **步骤**：B 退出空间 S -> 其他成员查看空间文件
- **预期**：文件 F 仍在空间中，B 无法访问

---

## TC-P0-5 成员活跃追踪

### TC-5.1 访问空间更新活跃时间
- **前置**：用户 B 的 last_active_at 为 null
- **步骤**：B 调用 `GET /api/team/S/files`
- **预期**：team_member.last_active_at 更新为当前时间

### TC-5.2 5分钟内不重复更新
- **前置**：用户 B 刚访问过空间（< 5分钟）
- **步骤**：B 再次调用 `GET /api/team/S/files`
- **预期**：last_active_at 不更新（去重生效）

### TC-5.3 超过5分钟后更新
- **前置**：用户 B 上次访问 > 5分钟前
- **步骤**：B 调用 `GET /api/team/S`
- **预期**：last_active_at 更新

### TC-5.4 成员列表展示活跃时间
- **步骤**：管理员调用 `GET /api/team/S/members`
- **预期**：返回 lastActiveAt 字段

### TC-5.5 按活跃时间排序
- **步骤**：调用 `GET /api/team/S/members?sortBy=active`
- **预期**：成员按 last_active_at 倒序排列

### TC-5.6 前端活跃状态标签
- **步骤**：成员列表查看
- **预期**：1小时内活跃=绿点、1天内=黄点、7天内=灰点、超7天=暗灰点

---

## TC-构建验证

### TC-B1 后端编译
- **步骤**：`mvn compile -pl st-team,st-common -am`
- **预期**：编译成功无错误

### TC-B2 前端构建
- **步骤**：`cd st-web && npm run build`
- **预期**：构建成功无 TS 错误

### TC-B3 数据库迁移
- **步骤**：执行 `17_team_invite.sql` 和 `18_team_activity.sql`
- **预期**：表创建成功，索引正确
