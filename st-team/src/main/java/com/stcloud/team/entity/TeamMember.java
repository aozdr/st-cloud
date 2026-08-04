package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_member")
public class TeamMember extends BaseEntity {

    private Long spaceId;
    private Long userId;
    private Integer role; // 0-管理员 1-编辑者 2-查看者
    private LocalDateTime joinedAt;
    private LocalDateTime lastActiveAt;
}
