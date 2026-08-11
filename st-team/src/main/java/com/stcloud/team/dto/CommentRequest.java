package com.stcloud.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "发表评论请求")
public class CommentRequest {
    @NotNull(message = "文件节点ID不能为空")
    @Schema(description = "文件节点ID") private Long nodeId;
    @NotBlank(message = "评论内容不能为空")
    @Schema(description = "评论内容") private String content;
    @Schema(description = "父评论ID（回复时传）") private Long parentId;
    @Schema(description = "@提及用户ID列表(逗号分隔)") private String mentions;
}