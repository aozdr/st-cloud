package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "分片上传初始化响应")
public class UploadInitResponse {

    @Schema(description = "上传唯一标识")
    private String uploadId;

    @Schema(description = "S3 multipart upload ID")
    private String s3UploadId;

    @Schema(description = "文件节点ID")
    private Long fileId;

    @Schema(description = "各分片预签名URL")
    private List<String> presignedUrls;

    @Schema(description = "传输模式：direct=预签名直传，relay=服务端中转（限速低于分片下限时）")
    private String transferMode;

    @Schema(description = "中转模式小块大小(字节)，relay 模式生效")
    private Long relayChunkSize;

    @Schema(description = "中转模式实际生效限速(KB/s)，relay 模式生效（服务端与客户端取最严格值）")
    private Long relayRateKb;
}
