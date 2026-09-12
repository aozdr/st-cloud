package com.stcloud.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 分片上传服务端边界；客户端不能通过请求覆盖这些限制。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "stcloud.upload")
public class UploadProperties {
    private long maxFileSize = 10L * 1024 * 1024 * 1024;
    private int maxTotalChunks = 20_000;
    private long minChunkSize = 5L * 1024 * 1024;
    private long maxChunkSize = 100L * 1024 * 1024;
    private int maxClientLimitKb = 1_048_576;
}
