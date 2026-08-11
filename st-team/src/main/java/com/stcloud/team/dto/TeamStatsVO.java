package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "空间统计数据")
public class TeamStatsVO {
    @Schema(description = "已用存储") private Long storageUsed;
    @Schema(description = "存储配额") private Long storageQuota;
    @Schema(description = "文件总数") private Long fileCount;
    @Schema(description = "文件类型分布") private List<Map<String, Object>> fileTypeDistribution;
    @Schema(description = "成员活跃度排行") private List<Map<String, Object>> memberActivity;
    @Schema(description = "操作统计") private List<Map<String, Object>> operationStats;
}