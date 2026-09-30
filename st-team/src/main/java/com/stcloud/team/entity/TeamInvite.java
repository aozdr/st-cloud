package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 团队空间邀请链接实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_invite")
public class TeamInvite extends BaseEntity {

    private Long spaceId;
    private String inviteCode; // 32位随机邀请码
    private Long role; // 预设角色或自定义角色 ID
    private Long createdBy; // 创建者ID
    private LocalDateTime expireAt; // 过期时间，NULL=永久
    private Integer status; // 状态：0-已撤销 1-有效
}
