package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "团队成员信息")
public class TeamMemberVO {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "角色：0-管理员 1-编辑者 2-查看者")
    private Integer role;

    @Schema(description = "加入时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime joinedAt;

    @Schema(description = "最后活跃时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastActiveAt;
}
