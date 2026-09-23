package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "通知信息")
public class NotificationVO {
    @Schema(description = "通知ID") private Long id;
    @Schema(description = "类型") private String type;
    @Schema(description = "标题") private String title;
    @Schema(description = "正文") private String content;
    @Schema(description = "关联类型") private String refType;
    @Schema(description = "关联ID") private Long refId;
    @Schema(description = "变更事件ID") private Long eventId;
    @Schema(description = "节点ID") private Long nodeId;
    @Schema(description = "团队空间ID") private Long spaceId;
    @Schema(description = "当前父节点ID") private Long parentId;
    @Schema(description = "目标是否仍可用") private Boolean available;
    @Schema(description = "变更类型") private String changeType;
    @Schema(description = "已读：0-未读 1-已读") private Integer read;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "时间") private LocalDateTime createdAt;
}
