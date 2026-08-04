package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "分片上传URL门控响应")
public class ChunkUrlResponse {

    @Schema(description = "预签名URL，为空表示尚未获得限速配额，需等待后重试")
    private String url;

    @Schema(description = "重试等待毫秒数（url为空时生效）")
    private long retryAfterMs;
}