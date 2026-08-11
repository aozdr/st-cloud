package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.stcloud.common.entity.BaseEntity;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_node")
public class FileNode extends BaseEntity {

    private Long parentId;
    private Integer nodeType;      // 0-文件夹 1-文件
    private String name;
    private String path;
    private Long fileSize;
    private String fileMd5;
    private String contentType;
    private String suffix;
    private String storagePath;
    private Integer status;         // 0-正常 1-回收站 2-已删除
    private Integer uploadStatus;   // 0-待上传 1-上传中 2-已完成 3-失败
    private Long uploaderId;
    private Long ownerId;
    private Long spaceId;
    private Integer refCount;
    @Version
    private Integer version;
    private String thumbnailPath;
    private Integer hidden;
    // P2: 文件锁定
    private Long lockedBy;
    private java.time.LocalDateTime lockedAt;
    private java.time.LocalDateTime lockExpireAt;  // 0-正常 1-隐藏

    public boolean isFolder() {
        return nodeType != null && nodeType == NodeType.FOLDER.getCode();
    }

    public boolean isFile() {
        return nodeType != null && nodeType == NodeType.FILE.getCode();
    }

    public boolean isNormal() {
        return status != null && status == NodeStatus.NORMAL.getCode();
    }
}
