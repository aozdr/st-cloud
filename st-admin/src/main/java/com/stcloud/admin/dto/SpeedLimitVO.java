package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "限速规则")
public class SpeedLimitVO {
    private Long id;
    private String ruleName;
    private Integer scope;
    private Long targetId;
    private String targetCode;
    private String targetName;
    private Integer uploadSpeedLimit;
    private Integer downloadSpeedLimit;
    private Integer enabled;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}