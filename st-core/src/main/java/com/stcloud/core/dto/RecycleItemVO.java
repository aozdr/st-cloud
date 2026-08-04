package com.stcloud.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "回收站项")
public class RecycleItemVO {

    @Schema(description = "节点ID")
    private Long id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "节点类型：0-文件夹 1-文件")
    private Integer nodeType;

    @Schema(description = "原路径")
    private String path;

    @Schema(description = "文件大小(字节)")
    private Long fileSize;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "删除时间")
    private LocalDateTime updatedAt;

    @Schema(description = "剩余保留天数（30天后自动清理）")
    private Integer remainingDays;
}
