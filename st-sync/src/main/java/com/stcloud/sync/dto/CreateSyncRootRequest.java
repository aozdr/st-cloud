package com.stcloud.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "注册同步根")
public class CreateSyncRootRequest {

    @Schema(description = "云端文件夹节点ID")
    @NotNull(message = "云端文件夹ID不能为空")
    private Long cloudFolderNodeId;

    @Schema(description = "本地路径提示")
    private String localPathHint;
}