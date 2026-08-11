package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 团队自定义角色实体
 * role ID >= 100 为自定义角色，0/1/2 为预设角色
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_role")
public class TeamRole extends BaseEntity {
    private Long spaceId;
    private String name;
    private String permissions;  // 权限JSON: {"view":true,"upload":false,...}
    private Integer status;      // 0-停用 1-启用
}