package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "增量变更响应")
public class SyncDeltaResponse {

    @Schema(description = "新游标（epoch ms，下次请求传入）")
    private Long cursor;

    @Schema(description = "是否还有更多变更")
    private Boolean hasMore;

    @Schema(description = "变更列表")
    private List<SyncDeltaItem> changes;
}