package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 我的关注列表记录；失效记录只保留 nodeId 供取消关注。 */
@Data
@Schema(description = "文件关注记录")
public class FileWatchVO {
    private Long watchId;
    private Long nodeId;
    private Integer nodeType;
    private Long spaceId;
    private Long parentId;
    private String name;
    private String path;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    private boolean available;
}
