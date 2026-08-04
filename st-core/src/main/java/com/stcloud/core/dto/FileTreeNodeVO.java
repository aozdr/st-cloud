package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "文件夹树节点")
public class FileTreeNodeVO {

    @Schema(description = "节点ID")
    private Long id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "路径")
    private String path;

    @Schema(description = "子文件夹")
    private List<FileTreeNodeVO> children = new ArrayList<>();
}
