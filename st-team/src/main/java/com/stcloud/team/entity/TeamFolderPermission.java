package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 团队文件夹权限实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_folder_permission")
public class TeamFolderPermission extends BaseEntity {

    private Long spaceId;
    private Long folderNodeId;
    private String subjectType;  // role/member
    private Long subjectId;      // 角色值或用户ID
    private Integer permission;  // -1-无权限 0-管理 1-编辑 2-查看
    private String permissions;  // 权限点JSON：{"view":true,"upload":true,...}，优先于单值 permission
}
