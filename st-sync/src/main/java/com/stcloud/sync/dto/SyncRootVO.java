package com.stcloud.sync.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "同步根信息")
public class SyncRootVO {

    @Schema(description = "同步根ID")
    private String id;

    @Schema(description = "云端文件夹节点ID")
    private String cloudFolderNodeId;

    @Schema(description = "云端文件夹名称")
    private String cloudFolderName;

    @Schema(description = "本地路径提示")
    private String localPathHint;

    @Schema(description = "状态：0-启用 1-暂停")
    private Integer status;

    @Schema(description = "冲突策略：keep_both / latest_wins / server_wins / local_wins")
    private String conflictStrategy;

    @Schema(description = "上次同步游标（sync_change_log.id）")
    private Long cursor;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "最后同步时间")
    private LocalDateTime lastSyncAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
