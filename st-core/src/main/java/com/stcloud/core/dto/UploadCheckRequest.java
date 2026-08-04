package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "秒传检查请求")
public class UploadCheckRequest {

    @Schema(description = "文件MD5")
    @NotBlank(message = "文件MD5不能为空")
    private String fileMd5;

    @Schema(description = "文件大小(字节)")
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    @Schema(description = "文件名")
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @Schema(description = "父文件夹ID，0或null=根目录")
    private Long parentId;

    @Schema(description = "团队空间ID（可选，团队空间上传时传入）")
    private Long spaceId;
}
