# 测试用例：团队空间 P1 协作增强

> 归属 exitCriteria: TESTCASES（依赖 TECH_DESIGN）

## TC-P1-1 文件夹级权限

### TC-1.1 设置文件夹成员级权限（正常）
- **前置**：A 为管理员，文件夹 F 存在
- **步骤**：A 调用 `PUT /api/team/S/folder/F/permissions`，body=`[{subjectType:"member", subjectId:B的userId, permission:2}]`
- **预期**：返回 200；team_folder_permission 新增记录；B 对 F 仅有查看权限

### TC-1.2 设置文件夹角色级权限
- **步骤**：A 设置规则 `[{subjectType:"role", subjectId:2, permission:-1}]`
- **预期**：查看者角色对 F 无权限，F 对查看者不可见

### TC-1.3 权限继承
- **前置**：F 有子文件夹 F2，F 设置 B 为查看权限
- **步骤**：B 尝试在 F2 中创建文件夹
- **预期**：返回 4004（继承父权限，B 仅查看，不可编辑）

### TC-1.4 权限覆盖中断继承
- **前置**：F 设置 B 为查看，F2 设置 B 为编辑
- **步骤**：B 尝试在 F2 中创建文件夹
- **预期**：成功（F2 覆盖了 F 的权限）

### TC-1.5 无覆盖规则回退空间级
- **前置**：文件夹 F 无任何权限规则，B 为空间编辑者
- **步骤**：B 尝试在 F 中创建文件夹
- **预期**：成功（回退空间级角色权限）

### TC-1.6 无权限文件夹不可见
- **前置**：F 设置查看者为无权限，B 为查看者
- **步骤**：B 调用 `GET /api/team/S/files?parentId=F的父`
- **预期**：F 不在返回列表中

### TC-1.7 非管理员设置权限
- **前置**：B 为编辑者
- **步骤**：B 调用 `PUT /api/team/S/folder/F/permissions`
- **预期**：返回 4004

### TC-1.8 文件操作受权限约束
- **前置**：F 设置 B 为查看权限
- **步骤**：B 尝试删除 F 中的文件
- **预期**：返回 4004（需编辑权限）

---

## TC-P1-2 文件评论与@提及

### TC-2.1 发表评论（正常）
- **前置**：B 为空间成员，文件 N 存在
- **步骤**：B 调用 `POST /api/team/S/comments`，body=`{nodeId:N, content:"很好"}`
- **预期**：返回 200；team_comment 新增记录

### TC-2.2 @提及触发通知
- **步骤**：B 发表评论 `{nodeId:N, content:"@张三 看看", mentions:"3"}`
- **预期**：team_comment 记录 mentions="3"；notification 表新增 MENTION 通知给 userId=3

### TC-2.3 回复评论
- **步骤**：C 回复 B 的评论 `{nodeId:N, content:"同意", parentId:B的commentId}`
- **预期**：parent_id 关联，二级嵌套显示

### TC-2.4 编辑评论（本人）
- **步骤**：B 调用 `PUT /api/team/S/comments/{commentId}`，body=`{content:"修改后"}`
- **预期**：content 更新

### TC-2.5 编辑评论（非本人）
- **前置**：C 尝试编辑 B 的评论
- **预期**：返回 4004

### TC-2.6 删除评论（本人）
- **步骤**：B 删除自己的评论
- **预期**：逻辑删除成功

### TC-2.7 删除评论（管理员）
- **步骤**：A 删除 B 的评论
- **预期**：成功

### TC-2.8 评论列表分页
- **前置**：文件 N 有 25 条评论
- **步骤**：size=20，再 page=2
- **预期**：第一页 20 条，第二页 5 条

---

## TC-P1-3 站内通知体系

### TC-3.1 获取未读数
- **前置**：用户 B 有 3 条未读通知
- **步骤**：B 调用 `GET /api/notification/unread-count`
- **预期**：返回 3

### TC-3.2 通知列表
- **步骤**：B 调用 `GET /api/notification?page=1&size=20`
- **预期**：返回通知列表，按时间倒序

### TC-3.3 标记单条已读
- **步骤**：B 调用 `PUT /api/notification/{id}/read`
- **预期**：read 变为 1；未读数减 1

### TC-3.4 全部已读
- **步骤**：B 调用 `PUT /api/notification/read-all`
- **预期**：所有通知 read=1

### TC-3.5 邀请触发通知
- **步骤**：A 邀请 B 加入空间
- **预期**：notification 新增 TEAM_INVITE 通知给 B

### TC-3.6 @提及触发通知
- **步骤**：评论 @提及 C
- **预期**：notification 新增 MENTION 通知给 C

### TC-3.7 被移除触发通知
- **步骤**：A 移除 B
- **预期**：notification 新增 MEMBER_CHANGE 通知给 B

### TC-3.8 仅查自己的通知
- **前置**：B 调用通知接口
- **预期**：仅返回 user_id = B 的通知

---

## TC-P1-4 空间搜索与排序

### TC-4.1 按名称搜索
- **步骤**：调用 `GET /api/team/spaces?keyword=产品`
- **预期**：仅返回名称含"产品"的空间

### TC-4.2 按创建时间排序
- **步骤**：调用 `sortBy=createdAt`
- **预期**：按创建时间倒序

### TC-4.3 按存储用量排序
- **步骤**：调用 `sortBy=storageUsed`
- **预期**：按存储用量倒序

### TC-4.4 置顶空间
- **步骤**：调用 `POST /api/team/S/pin`
- **预期**：team_member.is_pinned 变为 1；列表中 S 显示在最前

### TC-4.5 取消置顶
- **步骤**：再次调用 pin
- **预期**：is_pinned 变为 0

### TC-4.6 置顶空间排序优先
- **前置**：空间 A 置顶，空间 B 未置顶但创建时间更晚
- **步骤**：调用列表
- **预期**：A 显示在 B 前面（置顶优先于排序）

---

## TC-构建验证

### TC-B1 后端编译
- `mvn compile -pl st-team -am` 无错误

### TC-B2 前端构建
- `npm run build` 无 TS 错误

### TC-B3 数据库迁移
- 执行 19/20/21/22 脚本，表和字段创建成功