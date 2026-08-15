package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "中转分片接收确认响应")
public class RelayChunkResponse {

    @Schema(description = "是否已确认接收")
    private boolean confirmed;

    @Schema(description = "本次是否触发了 S3 分片写入")
    private boolean partUploaded;

    @Schema(description = "本次写入的 S3 part 序号（未触发为0）")
    private int partNumber;
}
