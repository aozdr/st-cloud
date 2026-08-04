package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "移动请求")
public class MoveRequest {

    @Schema(description = "要移动的节点ID列表")
    @NotEmpty(message = "节点ID列表不能为空")
    private List<Long> nodeIds;

    @Schema(description = "目标父文件夹ID，0=根目录")
    @NotNull(message = "目标文件夹ID不能为空")
    private Long targetParentId;
}
