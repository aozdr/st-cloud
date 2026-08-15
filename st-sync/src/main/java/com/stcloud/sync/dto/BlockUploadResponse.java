package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 块级同步上传组装响应（迭代 5）
 */
@Data
@Schema(description = "块级同步上传组装响应")
public class BlockUploadResponse {

    @Schema(description = "文件节点ID")
    private String fileId;

    @Schema(description = "新版本号")
    private Integer version;
}
