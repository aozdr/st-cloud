package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "云盘总容量设置请求")
public class CloudCapacityRequest {

    @Schema(description = "云盘总容量(字节)，null=不限")
    private Long capacity;
}
