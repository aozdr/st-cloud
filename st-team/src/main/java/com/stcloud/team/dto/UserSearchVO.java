package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户搜索结果（用于邀请成员时选择用户）
 */
@Data
@Schema(description = "用户搜索结果")
public class UserSearchVO {

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像")
    private String avatar;
}