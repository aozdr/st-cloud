package com.stcloud.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_chunk")
public class FileChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long tenantId;
    private String uploadId;
    private Long fileNodeId;
    private Integer chunkIndex;
    private Long chunkSize;
    private String chunkMd5;
    private String storagePath;
    private Long originalSize;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
