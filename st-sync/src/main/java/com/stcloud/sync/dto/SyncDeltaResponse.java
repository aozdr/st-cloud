package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "增量变更响应")
public class SyncDeltaResponse {

    @Schema(description = "新游标（sync_change_log.id，下次请求传入）")
    private Long cursor;

    @Schema(description = "是否还有更多变更")
    private Boolean hasMore;

    @Schema(description = "变更列表")
    private List<SyncDeltaItem> changes;

    @Schema(description = "范围投影协议版本")
    private Integer scopeProjectionVersion;

    @Schema(description = "存在无法可靠投影的历史事件，客户端需先完整对账")
    private Boolean reconcileRequired;
}
