package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "文件夹权限规则")
public class FolderPermissionVO {
    @Schema(description = "权限记录ID") private Long id;
    @Schema(description = "空间ID") private Long spaceId;
    @Schema(description = "文件夹节点ID") private Long folderNodeId;
    @Schema(description = "授权对象类型：role/member") private String subjectType;
    @Schema(description = "角色值或用户ID") private Long subjectId;
    @Schema(description = "对象名称") private String subjectName;
    @Schema(description = "权限：-1-无权限 0-管理 1-编辑 2-查看") private Integer permission;
    @Schema(description = "权限点JSON") private String permissions;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间") private LocalDateTime createdAt;
}
