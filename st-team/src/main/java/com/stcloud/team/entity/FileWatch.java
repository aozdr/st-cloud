package com.stcloud.team.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 当前用户对文件/文件夹的订阅；取消关注直接删除，重新关注生成新的代际 ID。 */
@Data
@TableName("file_watch")
public class FileWatch implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long nodeId;
    private LocalDateTime createdAt;
}
