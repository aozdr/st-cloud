package com.stcloud.sync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件块布局实体（迭代 5 块级增量同步）
 * <p>
 * 持久化每个文件版本的分块布局，供块级增量同步对比复用。
 * 块大小 5MB，块存储路径 = 整文件对象路径（不单独存块对象）。
 */
@Data
@TableName("file_block")
public class FileBlock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long fileNodeId;
    private Integer version;
    private Integer blockIndex;
    private String blockMd5;
    private Long blockSize;
    private String storagePath;
    private LocalDateTime createdAt;
}
