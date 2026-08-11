package com.stcloud.team.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "评论信息")
public class TeamCommentVO {
    @Schema(description = "评论ID") private Long id;
    @Schema(description = "空间ID") private Long spaceId;
    @Schema(description = "文件节点ID") private Long nodeId;
    @Schema(description = "评论人ID") private Long userId;
    @Schema(description = "用户名") private String username;
    @Schema(description = "昵称") private String nickname;
    @Schema(description = "头像") private String avatar;
    @Schema(description = "评论内容") private String content;
    @Schema(description = "父评论ID") private Long parentId;
    @Schema(description = "@提及用户ID列表") private String mentions;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "时间") private LocalDateTime createdAt;
    @Schema(description = "回复列表") private List<TeamCommentVO> replies;
}