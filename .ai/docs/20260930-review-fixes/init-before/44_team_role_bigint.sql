SET NAMES utf8mb4;

-- 团队成员和邀请必须保存完整的自定义角色雪花 ID。
ALTER TABLE team_member
    MODIFY COLUMN role BIGINT NOT NULL DEFAULT 2 COMMENT '角色ID：0管理员 1编辑者 2查看者，其余为自定义角色';

ALTER TABLE team_invite
    MODIFY COLUMN role BIGINT NOT NULL DEFAULT 2 COMMENT '角色ID：0管理员 1编辑者 2查看者，其余为自定义角色';
