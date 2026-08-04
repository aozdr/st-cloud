package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建团队空间请求")
public class CreateSpaceRequest {

    @NotBlank(message = "空间名称不能为空")
    @Schema(description = "空间名称")
    private String spaceName;

    @Schema(description = "空间描述")
    private String description;

    @Schema(description = "空间图标(emoji或URL)")
    private String icon;

    @Schema(description = "存储配额(字节)，默认10GB")
    private Long storageQuota;
}
