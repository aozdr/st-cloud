package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文件格式转换请求
 */
@Data
@Schema(description = "文件格式转换请求")
public class ConvertFileRequest {

    @Schema(description = "转换后的文件名（可编辑；空则用默认名「原文件名-转换.目标后缀」）", example = "周报-转换.pdf")
    private String fileName;
}
