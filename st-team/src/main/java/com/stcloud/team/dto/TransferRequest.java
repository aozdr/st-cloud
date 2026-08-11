package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 移交所有权请求
 */
@Data
@Schema(description = "移交所有权请求")
public class TransferRequest {

    @NotNull(message = "目标成员ID不能为空")
    @Schema(description = "目标成员记录ID")
    private Long targetMemberId;
}