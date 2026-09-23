package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 通知安全目标；不可用时仅返回 available=false。 */
@Data
@Schema(description = "通知安全目标")
public class NotificationTargetVO {
    private boolean available;
    private Long nodeId;
    private Long parentId;
    private Long spaceId;
    private Integer nodeType;
}
