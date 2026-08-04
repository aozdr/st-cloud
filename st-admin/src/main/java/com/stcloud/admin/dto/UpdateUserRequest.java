package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改用户请求")
public class UpdateUserRequest {

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "状态：0-禁用 1-正常")
    private Integer status;

    @Schema(description = "存储配额(字节)")
    private Long storageQuota;

    @Schema(description = "是否管理员")
    private Integer isAdmin;

    @Schema(description = "重置密码（明文，自动BCrypt加密）")
    private String resetPassword;
}
