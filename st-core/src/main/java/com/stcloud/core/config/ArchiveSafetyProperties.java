package com.stcloud.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 在线解压资源上限；默认值用于安全兜底，部署环境可通过 stcloud.archive 覆盖。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "stcloud.archive")
public class ArchiveSafetyProperties {
    private int maxEntries = 100_000;
    private long maxEntrySize = 100L * 1024 * 1024;
    private long maxTotalSize = 500L * 1024 * 1024;
    private int maxDepth = 20;
    private long maxCompressionRatio = 100L;
    private int maxActiveTasksPerUser = 2;
    private int maxQueueSize = 50;
}
