# 技术设计：团队空间 P2 高级协作

> 归属 exitCriteria: TECH_DESIGN（依赖 EXP_DESIGN）

## 1. 架构设计

```
st-team 模块扩展
│
├── P2-1 文件锁定
│   ├── Entity:  FileNode 新增 lockedBy/lockedAt/lockExpireAt
│   ├── Service: TeamServiceImpl 新增 lock/unlock + 锁定检查
│   ├── Controller: TeamController 新增 lock/unlock 端点 + 文件操作前拦截
│   └── Task: FileLockExpireTask 定时清理过期锁
│
├── P2-2 自定义角色
│   ├── Entity:  TeamRole
│   ├── Mapper:  TeamRoleMapper
│   ├── Service: TeamRoleService（CRUD + 权限矩阵加载）
│   └── Controller: TeamController 新增角色管理端点
│   └── checkPermission 扩展：role >= 100 时加载权限矩阵
│
├── P2-3 外部协作者
│   ├── Entity:  TeamMember 新增 memberType/expireAt + TeamExternalConfig
│   ├── Service: TeamServiceImpl 新增外部标记/配置 + 过期清理
│   └── Task: ExternalMemberExpireTask 定时清理过期外部成员
│
├── P2-4 空间统计
│   ├── Service: TeamStatsService 聚合查询
│   └── Controller: GET /api/team/{spaceId}/stats
│
数据库: 23_file_lock.sql / 24_team_role.sql / 25_team_external.sql
前端: TeamSpacePage 扩展 + RoleManageDialog + StatsTab + 外部标记
```

## 2. 后端设计

### 2.1 P2-1 文件锁定

**新增端点：**
| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/{spaceId}/files/{nodeId}/lock` | 锁定 | 编辑者+ |
| POST | `/{spaceId}/files/{nodeId}/unlock` | 解锁 | 锁定人/管理员 |

**锁定检查逻辑（Controller 层拦截）：**
```java
private void checkNotLocked(Long nodeId) {
    FileNode node = fileNodeMapper.selectById(nodeId);
    if (node != null && node.getLockedBy() != null) {
        // 检查锁是否过期
        if (node.getLockExpireAt() == null || node.getLockExpireAt().isAfter(LocalDateTime.now())) {
            SysUser locker = sysUserMapper.selectById(node.getLockedBy());
            throw new BusinessException(ResultCode.BAD_REQUEST,
                "文件被" + (locker != null ? locker.getNickname() : "他人") + "锁定");
        }
    }
}
```
在 createFolder/deleteFiles/renameFile/moveFiles 前调用。

### 2.2 P2-2 自定义角色

**checkPermission 扩展：**
```java
// 原逻辑保留：role < 100 走数值比较
if (spaceRole < 100) {
    if (spaceRole > minPermission) throw TEAM_PERMISSION_DENIED;
    return spaceRole;
}
// 自定义角色：role >= 100，加载权限矩阵
TeamRole role = teamRoleMapper.selectById((long) spaceRole);
if (role == null || role.getStatus() == 0) throw TEAM_PERMISSION_DENIED;
String perms = role.getPermissions(); // JSON
// minPermission 映射：2->view, 1->upload/delete/rename/move, 0->manage_members/manage_settings
boolean hasPermission = checkPermissionMatrix(perms, minPermission);
if (!hasPermission) throw TEAM_PERMISSION_DENIED;
return spaceRole;
```

### 2.3 P2-3 外部协作者

**新增端点：**
| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | `/{spaceId}/member/{memberId}/external` | 标记/取消外部 |
| GET | `/{spaceId}/external-config` | 获取配置 |
| PUT | `/{spaceId}/external-config` | 设置开关 |

### 2.4 P2-4 空间统计

**`GET /api/team/{spaceId}/stats?days=7` 返回：**
```json
{
  "storageUsed": 2415919104, "storageQuota": 10737418240,
  "fileCount": 156,
  "fileTypeDistribution": [{"type":"文档","count":70},...],
  "memberActivity": [{"userId":1,"nickname":"张三","lastActiveAt":"..."},...],
  "operationStats": [{"action":"FILE_UPLOAD","count":32},...]
}
```

## 3. 数据设计

### 23_file_lock.sql
```sql
ALTER TABLE file_node ADD COLUMN locked_by BIGINT DEFAULT NULL COMMENT '锁定人ID';
ALTER TABLE file_node ADD COLUMN locked_at DATETIME DEFAULT NULL COMMENT '锁定时间';
ALTER TABLE file_node ADD COLUMN lock_expire_at DATETIME DEFAULT NULL COMMENT '锁定过期时间，NULL=永久';
```

### 24_team_role.sql
```sql
CREATE TABLE IF NOT EXISTS team_role (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID（>=100）',
    tenant_id    BIGINT       NOT NULL COMMENT '租户ID',
    space_id     BIGINT       NOT NULL COMMENT '空间ID',
    name         VARCHAR(50)  NOT NULL COMMENT '角色名称',
    permissions  VARCHAR(500) NOT NULL COMMENT '权限JSON',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_space (space_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队自定义角色表';
```

### 25_team_external.sql
```sql
ALTER TABLE team_member ADD COLUMN member_type TINYINT NOT NULL DEFAULT 0 COMMENT '0-内部 1-外部';
ALTER TABLE team_member ADD COLUMN expire_at DATETIME DEFAULT NULL COMMENT '外部协作者有效期';
CREATE TABLE IF NOT EXISTS team_external_config (
    id           BIGINT  NOT NULL AUTO_INCREMENT,
    tenant_id    BIGINT  NOT NULL,
    space_id     BIGINT  NOT NULL,
    allow_external TINYINT NOT NULL DEFAULT 0 COMMENT '0-禁止 1-允许',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空间外部协作配置';
```

## 4. 修改文件范围

### 后端
- st-core: FileNode.java 新增 3 字段
- st-team: TeamRole/TeamExternalConfig Entity + Mapper（新增）
- st-team: TeamRoleRequest/TeamStatsVO/TeamExternalConfigVO 等 DTO（新增）
- st-team: TeamService + TeamServiceImpl + TeamController（修改：锁定/角色/外部/统计）
- st-common: ResultCode 新增锁定相关错误码
- st-team: FileLockExpireTask + ExternalMemberExpireTask（新增定时任务）
- docker/mysql/init: 23-25 脚本

### 前端
- types/index.ts: 新增 TeamRole/TeamStats 类型
- TeamSpacePage.tsx: 锁定/解锁按钮 + 角色管理入口 + 统计 Tab + 外部标记
- components/team/RoleManageDialog.tsx（新增）
- components/team/StatsPanel.tsx（新增）