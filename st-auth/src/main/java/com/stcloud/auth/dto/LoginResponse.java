package com.stcloud.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LoginResponse {

    private String token;
    private String refreshToken;
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private Long storageUsed;
    private Long storageQuota;
    /** 角色编码列表 */
    private List<String> roles;
    /** 权限码列表 */
    private List<String> permissions;
}
