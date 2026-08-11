package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "设置外部协作者请求")
public class ExternalMemberRequest {
    @Schema(description = "是否外部：0-内部 1-外部") private Integer memberType;
    @Schema(description = "有效期，NULL=永久") private LocalDateTime expireAt;
}