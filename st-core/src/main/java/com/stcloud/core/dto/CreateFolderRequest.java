package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "创建文件夹请求")
public class CreateFolderRequest {

    @Schema(description = "父文件夹ID，0=根目录", example = "0")
    @NotNull(message = "父文件夹ID不能为空")
    private Long parentId;

    @Schema(description = "文件夹名称", example = "新建文件夹")
    @NotBlank(message = "文件夹名称不能为空")
    private String folderName;
}
