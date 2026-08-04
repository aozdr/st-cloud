package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_space")
public class TeamSpace extends BaseEntity {

    private String spaceName;
    private String description;
    private String icon;
    private Long ownerId;
    private Long storageUsed;
    private Long storageQuota;
    private Integer status; // 0-禁用 1-正常
}
