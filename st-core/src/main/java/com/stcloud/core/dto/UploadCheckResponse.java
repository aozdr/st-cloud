package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "秒传检查响应")
public class UploadCheckResponse {

    @Schema(description = "是否秒传成功")
    private Boolean instant;

    @Schema(description = "文件节点ID（秒传成功时返回）")
    private Long fileId;
}
