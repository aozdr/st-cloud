# 影响分析：团队空间 P2 高级协作

> 归属 exitCriteria: IMPACT_ANALYSIS（依赖 REQ_ANALYSIS）

## 分析

### 1. P2-1 文件锁定影响

**FileNode 实体变更：**
- 新增 `lockedBy`/`lockedAt`/`lockExpireAt` 字段（对应 file_node 表 3 个新字段）
- FileNodeMapper 无需改动（MyBatis-Plus 自动映射）

**锁定检查点（TeamController 层拦截）：**

| 端点 | 锁定检查 |
|------|----------|
| createFolder | 检查 parentId 是否被锁定 |
| deleteFiles | 逐个检查 nodeIds 是否被锁定 |
| renameFile | 检查 nodeId 是否被锁定 |
| moveFiles | 检查源节点 + 目标父文件夹是否被锁定 |

**新增端点：**
- `POST /api/team/{spaceId}/files/{nodeId}/lock` -- 锁定
- `POST /api/team/{spaceId}/files/{nodeId}/unlock` -- 解锁

**定时任务：** 过期锁自动释放（检查 lock_expire_at < now）

### 2. P2-2 自定义角色影响（最高风险）

**兼容设计关键：**
- `team_member.role` 字段值域扩展：0/1/2 = 预设角色，>= 100 = 自定义角色 ID
- `checkPermission` 逻辑扩展：
  - role < 100：走原逻辑（数值比较）
  - role >= 100：从 team_role 表加载 permissions JSON，按权限项判断

**新增端点：**
- `GET /api/team/{spaceId}/roles` -- 角色列表
- `POST /api/team/{spaceId}/role` -- 创建角色
- `PUT /api/team/{spaceId}/role/{roleId}` -- 编辑角色
- `DELETE /api/team/{spaceId}/role/{roleId}` -- 删除角色

**权限映射：** 9 项权限映射到现有 checkPermission 的 minRole 参数：
- view -> minPermission 2
- upload/download/rename/move/delete -> minPermission 1
- manage_members/manage_settings -> minPermission 0

### 3. P2-3 外部协作者影响

**team_member 表变更：** 新增 member_type + expire_at
**新增 team_external_config 表：** 空间级外部协作开关

**新增端点：**
- `PUT /api/team/{spaceId}/member/{memberId}/external` -- 标记/取消外部协作者
- `GET /api/team/{spaceId}/external-config` -- 获取外部协作配置
- `PUT /api/team/{spaceId}/external-config` -- 设置外部协作开关

**定时任务：** 清理过期外部协作者（expire_at < now）

### 4. P2-4 空间统计仪表板影响

**完全新增，无现有代码改动：**
- 新增 `GET /api/team/{spaceId}/stats` 接口，聚合查询
- 基于 file_node（文件类型分布）、team_member（活跃度）、team_activity（操作统计）

### 5. 数据库影响

| 脚本 | 内容 |
|------|------|
| 23_file_lock.sql | file_node 新增 locked_by/locked_at/lock_expire_at |
| 24_team_role.sql | 新增 team_role 表 |
| 25_team_external.sql | team_member 新增 member_type/expire_at + team_external_config 表 |

## 决策

1. 锁定检查在 Controller 层统一拦截，不侵入 FileService
2. 自定义角色 ID >= 100，预设角色 0/1/2 保留，向后兼容
3. 外部协作者复用现有 team_member 表，新增 member_type 区分
4. 统计接口实时聚合，不预计算（空间内文件量可控）

## State Delta
- 勾选 `IMPACT_ANALYSIS = done`
- 无 blockers

## 下一步
并行派发 Experience Reviewer + Architect。