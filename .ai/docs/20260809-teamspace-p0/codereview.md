# Code Review：团队空间 P0 基础协作补齐

> 归属 exitCriteria: CODE_REVIEW
> 审查范围：本次 P0 全部变更文件

## 背景

对 P0 迭代的全部代码变更进行质量审查，覆盖后端 st-team 模块新增/修改、前端 st-web 页面改造、数据库迁移脚本。

## 审查结果

### 后端

| 检查项 | 结果 | 说明 |
|--------|------|------|
| 代码规范一致性 | ✅ | 沿用现有 Lombok + MyBatis-Plus + @Resource 注入风格 |
| 中文注释 | ✅ | 核心逻辑（权限校验、邀请码生成、活跃去重、移交校验）均有中文注释 |
| 事务边界 | ✅ | createInvite/revokeInvite/joinByCode/leaveSpace/transferOwnership 均标注 @Transactional |
| 异步写入 | ✅ | TeamActivityHelper 使用独立线程池异步写入，@PreDestroy 优雅关闭 |
| Redis 降级 | ✅ | ActiveTracker 在 Redis 不可用时降级查库，保证可用性 |
| 邀请码安全性 | ✅ | 使用 SecureRandom + 32 位字母数字，防遍历 |
| 权限校验完整性 | ✅ | 所有新增接口均有 checkPermission 校验 |
| DTO 校验 | ✅ | CreateInviteRequest 用 @Min/@Max 校验角色，TransferRequest 用 @NotNull |

### 前端

| 检查项 | 结果 | 说明 |
|--------|------|------|
| 类型安全 | ✅ | 新增 TeamInvite/TeamActivity 类型定义，无 any |
| 组件复用 | ✅ | FileBrowser 复用，设置/成员弹窗在现有结构扩展 |
| 状态管理 | ✅ | Tab 切换用 hidden 不卸载 FileBrowser，保持文件浏览状态 |
| 事件处理 | ✅ | 邀请复制/撤销/退出/移交均有二次确认或 Toast 反馈 |
| 无障碍 | ✅ | aria-label 覆盖关闭/复制/撤销/移除按钮 |

### 发现并已修复的问题

| 问题 | 严重度 | 修复 |
|------|--------|------|
| reportFileActivity 允许查看者调用，可伪造活动记录 | 中 | 改为 checkPermission(spaceId, 1)，仅编辑者以上可上报 |
| joinByCode 未校验空间是否存在/正常 | 中 | 新增 space 状态校验，status!=1 时拒绝加入 |
| 前端 sortBy 切换不触发重新获取成员列表 | 低 | 新增 useEffect 监听 sortBy 变化自动 fetchMembers |

### 残留低优先级项（不阻塞）

- TeamInvitePage 的"已是成员"状态未区分（后端返回 spaceId，前端统一显示"加入成功"）-- 后续可优化
- 活动日志 targetName 在批量删除/移动时为 null -- 可后续查询文件名填充

## State Delta

- 勾选 exitCriteria: `CODE_REVIEW = done`
- 无新增 blockers

## 下一步

建议编排器派发 Security Reviewer 做安全专项审查。