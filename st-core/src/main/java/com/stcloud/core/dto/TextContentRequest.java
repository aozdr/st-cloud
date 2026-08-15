package com.stcloud.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文本内容保存请求
 */
@Data
@Schema(description = "文本内容保存请求")
public class TextContentRequest {

    @Schema(description = "文本内容（UTF-8，上限 2MB）")
    @NotBlank(message = "内容不能为空")
    private String content;
}
