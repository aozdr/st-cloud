package com.stcloud.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "文件版本信息")
public class FileVersionVO {

    @Schema(description = "版本ID")
    private Long id;

    @Schema(description = "文件节点ID")
    private Long fileNodeId;

    @Schema(description = "版本号")
    private Integer versionNum;

    @Schema(description = "文件大小(字节)")
    private Long fileSize;

    @Schema(description = "文件MD5")
    private String fileMd5;

    @Schema(description = "修改人ID")
    private Long modifierId;

    @Schema(description = "修改人名称")
    private String modifierName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "是否为当前版本")
    private Boolean current;
}