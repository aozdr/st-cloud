package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请链接信息
 */
@Data
@Schema(description = "邀请链接信息")
public class TeamInviteVO {

    @Schema(description = "邀请ID")
    private Long id;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "邀请码")
    private String inviteCode;

    @Schema(description = "默认角色")
    private Integer role;

    @Schema(description = "创建者ID")
    private Long createdBy;

    @Schema(description = "创建者名称")
    private String createdByName;

    @Schema(description = "过期时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    @Schema(description = "状态：0-已撤销 1-有效")
    private Integer status;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}