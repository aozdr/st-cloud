package com.stcloud.team.entity;

import lombok.Data;

/** 事件匹配的最小结果，避免从队列匹配阶段读取或保存可变文件路径。 */
@Data
public class FileWatchMatch {
    private Long id;
    private Long tenantId;
    private Long userId;
    private Long nodeId;
}
