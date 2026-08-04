package com.stcloud.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户管理信息")
public class UserManageVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "状态：0-禁用 1-正常")
    private Integer status;

    @Schema(description = "是否管理员：0-否 1-是")
    private Integer isAdmin;

    @Schema(description = "已用存储(字节)")
    private Long storageUsed;

    @Schema(description = "存储配额(字节)")
    private Long storageQuota;

    @Schema(description = "角色列表")
    private List<RoleVO> roles;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
