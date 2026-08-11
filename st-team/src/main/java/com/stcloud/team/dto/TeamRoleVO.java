package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "自定义角色信息")
public class TeamRoleVO {
    @Schema(description = "角色ID") private Long id;
    @Schema(description = "空间ID") private Long spaceId;
    @Schema(description = "角色名称") private String name;
    @Schema(description = "权限JSON") private String permissions;
    @Schema(description = "状态：0-停用 1-启用") private Integer status;
    @Schema(description = "是否预设角色") private Boolean isPreset;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime createdAt;
}