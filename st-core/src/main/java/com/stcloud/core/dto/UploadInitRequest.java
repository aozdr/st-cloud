package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "分片上传初始化请求")
public class UploadInitRequest {

    @Schema(description = "文件名")
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @Schema(description = "文件大小(字节)")
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    @Schema(description = "文件MD5")
    @NotBlank(message = "文件MD5不能为空")
    private String fileMd5;

    @Schema(description = "总分片数")
    @NotNull(message = "总分片数不能为空")
    private Integer totalChunks;

    @Schema(description = "分片大小(字节)")
    @NotNull(message = "分片大小不能为空")
    private Long chunkSize;

    @Schema(description = "父文件夹ID，0或null=根目录")
    private Long parentId;

    @Schema(description = "替换上传的目标文件ID（可选）。传入时覆盖该文件内容并生成历史版本，不传则为新建上传")
    private Long replaceFileId;

    @Schema(description = "团队空间ID（可选，团队空间上传时传入）")
    private Long spaceId;

    @Schema(description = "客户端自设上传限速 KB/s（可选，0=不限速）。与服务端限速取最严格值")
    private Integer clientLimit;
}
