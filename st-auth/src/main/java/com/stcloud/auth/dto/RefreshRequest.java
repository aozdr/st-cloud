package com.stcloud.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "刷新Token请求")
public class RefreshRequest {

    @Schema(description = "刷新令牌")
    private String refreshToken;
}