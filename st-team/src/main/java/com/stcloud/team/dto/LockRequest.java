package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "锁定请求")
public class LockRequest {
    @Schema(description = "锁定时长（小时），0=永久，默认24")
    private Integer hours = 24;
}