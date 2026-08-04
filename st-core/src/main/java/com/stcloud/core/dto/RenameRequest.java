package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "重命名请求")
public class RenameRequest {

    @Schema(description = "新名称", example = "重命名文件.txt")
    @NotBlank(message = "新名称不能为空")
    private String newName;
}
