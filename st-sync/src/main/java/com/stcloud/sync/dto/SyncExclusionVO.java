package com.stcloud.sync.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "同步排除路径")
public class SyncExclusionVO {

    @Schema(description = "排除项ID")
    private String id;

    @Schema(description = "同步根ID")
    private String syncRootId;

    @Schema(description = "相对同步根的路径")
    private String relativePath;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}