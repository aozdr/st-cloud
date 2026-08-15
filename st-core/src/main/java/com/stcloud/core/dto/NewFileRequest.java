package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建空白文件请求
 */
@Data
@Schema(description = "新建空白文件请求")
public class NewFileRequest {

    @Schema(description = "父文件夹ID，0=根目录", example = "0")
    private Long parentId;

    @Schema(description = "文件类型：txt/docx/xlsx/pptx", example = "txt")
    @NotBlank(message = "文件类型不能为空")
    private String type;

    @Schema(description = "文件名（可选，空则使用默认名，如「新建文档.docx」；无后缀自动补对应类型后缀）", example = "周报")
    private String fileName;
}
