package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 关注状态响应；watchId 为空表示当前用户未关注。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件关注状态")
public class FileWatchStateVO {
    private Long nodeId;
    private boolean watching;
    private Long watchId;
}
