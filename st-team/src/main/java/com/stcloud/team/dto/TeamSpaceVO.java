package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "团队空间信息")
public class TeamSpaceVO {

    @Schema(description = "空间ID")
    private Long id;

    @Schema(description = "空间名称")
    private String spaceName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "拥有者ID")
    private Long ownerId;

    @Schema(description = "拥有者名称")
    private String ownerName;

    @Schema(description = "已用存储(字节)")
    private Long storageUsed;

    @Schema(description = "存储配额(字节)")
    private Long storageQuota;

    @Schema(description = "成员数")
    private Integer memberCount;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
