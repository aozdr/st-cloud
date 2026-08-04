package com.stcloud.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "系统统计数据")
public class StatsVO {

    @Schema(description = "总用户数")
    private Long totalUsers;

    @Schema(description = "活跃用户数（近7天登录）")
    private Long activeUsers;

    @Schema(description = "总文件数")
    private Long totalFiles;

    @Schema(description = "总存储用量(字节)")
    private Long totalStorageUsed;

    @Schema(description = "分享总数")
    private Long totalShares;

    @Schema(description = "团队空间数")
    private Long totalTeams;

    @Schema(description = "云盘总容量(字节)，NULL=不限")
    private Long cloudTotalCapacity;

    @Schema(description = "云盘已用容量(字节)")
    private Long cloudStorageUsed;
}
