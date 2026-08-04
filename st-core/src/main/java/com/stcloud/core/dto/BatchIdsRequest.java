package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "批量操作请求")
public class BatchIdsRequest {

    @Schema(description = "节点ID列表")
    @NotEmpty(message = "节点ID列表不能为空")
    private List<Long> nodeIds;
}
