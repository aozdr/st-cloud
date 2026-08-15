package com.stcloud.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * 上传中转限速配置 - 中转模式临时文件目录与超时清理参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "stcloud.upload.relay")
public class UploadRelayConfig {

    /** 中转临时文件目录 */
    private String tempDir = System.getProperty("java.io.tmpdir") + File.separator + "stcloud-relay";

    /** 中转会话超时(毫秒)，超时未活跃自动清理 */
    private long sessionTimeoutMs = 10 * 60_000L;

    /** 超时清理定时任务间隔(毫秒)，默认 60 秒扫描一次 */
    private long cleanupIntervalMs = 60_000L;

    /** multipart 分片下限(字节)，S3/MinIO 非末片最小 5MB */
    private long partMinSize = 5L * 1024 * 1024;

    /** 获取并初始化临时目录 */
    public File getTempDirFile() {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
