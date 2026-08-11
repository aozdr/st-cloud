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
    private Integer isPinned; // 是否置顶：0-否 1-是
    private Integer memberType; // 0-内部 1-外部
    private LocalDateTime expireAt; // 外部协作者有效期
}
