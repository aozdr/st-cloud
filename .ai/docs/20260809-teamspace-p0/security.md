# Security Review：团队空间 P0 基础协作补齐

> 归属 exitCriteria: SECURITY_REVIEW
> 关联云盘专项安全规则：文件权限安全、分享访问控制

## 背景

团队空间 P0 涉及邀请链接（外部加入路径）、成员退出/移交（权限变更）、活动日志（信息泄露面），需专项安全审查。

## 审查范围

- 邀请链接机制（生成/加入/撤销）
- 退出与所有权移交（权限边界）
- 活动日志（访问控制）
- 活跃追踪（数据写入安全）
- 前端邀请落地页（XSS / 开放重定向）

## 审查结果

### 1. 邀请链接安全

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 邀请码可预测/遍历 | ✅ 安全 | SecureRandom 生成 32 位字母数字，熵充足，不可猜测 |
| 邀请链接泄露导致未授权加入 | ⚠️ 可接受 | P0 设计为直接加入（无审批），链接泄露即可加入；管理员可随时撤销；活动日志记录 MEMBER_JOIN 可追溯。P1 可加审批流。 |
| 撤销后链接仍可用 | ✅ 安全 | joinByCode 校验 status==0 则拒绝 |
| 过期链接仍可用 | ✅ 安全 | joinByCode 校验 expireAt |
| 无效空间仍可加入 | ✅ 已修复 | joinByCode 新增 space 状态校验（status!=1 拒绝） |
| 越权生成/撤销邀请 | ✅ 安全 | createInvite/revokeInvite/listInvites 均 checkPermission(spaceId, 0) |

### 2. 退出与所有权移交安全

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 非成员退出他人空间 | ✅ 安全 | leaveSpace 通过 checkPermission(spaceId, 2) 校验成员身份 |
| 非拥有者移交所有权 | ✅ 安全 | transferOwnership 校验 space.ownerId == userId |
| 移交给非管理员导致越权 | ✅ 安全 | 校验 target.role == 0，否则抛 TEAM_TRANSFER_TARGET_INVALID |
| 移交给非本空间成员 | ✅ 安全 | 校验 target.spaceId == spaceId |
| 最后管理员退出导致空间无人管理 | ✅ 安全 | leaveSpace 校验 adminCount <= 1 时拒绝 |
| 拥有者直接退出未移交 | ✅ 安全 | leaveSpace 校验 ownerId == userId 时要求先移交 |
| 事务原子性 | ✅ 安全 | @Transactional 保证 owner_id 更新与活动日志原子性 |
| 退出后仍可访问文件 | ✅ 安全 | 退出删除 team_member 记录，后续 checkPermission 拒绝 |

### 3. 活动日志安全

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 非成员查看空间活动 | ✅ 安全 | listActivities checkPermission(spaceId, 2) |
| 查看者伪造文件活动记录 | ✅ 已修复 | reportFileActivity 改为 checkPermission(spaceId, 1)，仅编辑者以上可上报 |
| 活动日志含敏感信息 | ✅ 安全 | 仅记录操作人/类型/目标名称，不记录文件内容/IP |
| 活动日志篡改 | ✅ 安全 | 仅系统写入（TeamActivityHelper），无用户编辑/删除接口 |

### 4. 活跃追踪安全

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 越权更新他人活跃时间 | ✅ 安全 | touchActive 使用 UserContext.getUserId()，仅更新当前用户 |
| Redis 不可用导致服务中断 | ✅ 安全 | ActiveTracker 有降级方案（查库判断时间差） |

### 5. 前端安全

| 风险点 | 评估 | 措施 |
|--------|------|------|
| 邀请落地页 XSS | ✅ 安全 | React 默认转义，无 dangerouslySetInnerHTML |
| 开放重定向 | ✅ 安全 | 邀请码经后端校验后返回 spaceId，前端仅 navigate 到 /team/{spaceId} |
| 邀请码注入 | ✅ 安全 | 邀请码作为 @PathVariable，后端用 LambdaQueryWrapper 参数化查询 |

### 6. 数据库安全

| 风险点 | 评估 | 措施 |
|--------|------|------|
| SQL 注入 | ✅ 安全 | 全部使用 MyBatis-Plus LambdaQueryWrapper，参数化查询 |
| team_invite.invite_code 唯一约束 | ✅ 安全 | UNIQUE KEY uk_invite_code 防重复 |
| team_activity 索引 | ✅ 安全 | idx_space_created 支撑分页查询性能 |

## 安全审查结论

**通过**。所有关键安全风险点已有防护措施，Code Review 中发现的 3 个问题已修复。残留可接受风险：

- 邀请链接直接加入（无审批）-- P0 可接受，P1 建议加审批流
- 活动日志批量操作 targetName 为 null -- 非安全问题，体验优化

## State Delta

- 勾选 exitCriteria: `SECURITY_REVIEW = done`
- 无新增 blockers

## 下一步

门禁依赖满足（CODE_REVIEW + SECURITY_REVIEW done），可进入测试执行（TEST_PASS）。