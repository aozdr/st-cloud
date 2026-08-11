package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stcloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_favorite")
public class FileFavorite extends BaseEntity {

    /** 收藏者用户ID */
    private Long userId;

    /** 被收藏的文件节点ID */
    private Long fileNodeId;
}