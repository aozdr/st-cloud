package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 团队空间文件评论实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("team_comment")
public class TeamComment extends BaseEntity {

    private Long spaceId;
    private Long nodeId;
    private Long userId;
    private String content;
    private Long parentId;      // 父评论ID，NULL=顶级评论
    private String mentions;    // @提及用户ID列表(逗号分隔)
}