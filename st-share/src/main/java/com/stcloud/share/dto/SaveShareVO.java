package com.stcloud.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "保存分享内容到云盘结果")
public class SaveShareVO {

    @Schema(description = "保存成功的文件/文件夹总数")
    private Integer savedCount;

    @Schema(description = "保存的源分享文件名/根节点名")
    private String sourceName;

    @Schema(description = "保存后生成的根节点ID（文件或文件夹）")
    private Long rootNodeId;

    @Schema(description = "保存后生成的节点ID列表")
    private List<Long> nodeIds;
}
