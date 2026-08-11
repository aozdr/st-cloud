package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "添加排除路径")
public class AddExclusionRequest {

    @Schema(description = "相对同步根的路径（以 / 开头）")
    @NotBlank(message = "排除路径不能为空")
    private String relativePath;
}