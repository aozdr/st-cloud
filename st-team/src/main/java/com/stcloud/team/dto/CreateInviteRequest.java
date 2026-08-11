package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成邀请链接请求
 */
@Data
@Schema(description = "生成邀请链接请求")
public class CreateInviteRequest {

    @Schema(description = "默认角色：0-管理员 1-编辑者 2-查看者", example = "2")
    @Min(value = 0, message = "角色无效")
    @Max(value = 2, message = "角色无效")
    private Integer role = 2;

    @Schema(description = "过期时间，NULL=永久")
    private LocalDateTime expireAt;
}