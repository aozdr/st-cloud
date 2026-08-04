package com.stcloud.search.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 搜索结果 VO
 */
@Data
public class SearchResultVO implements Serializable {

    /**
     * 文件 ID（关联 file_node.id）
     */
    private Long fileId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件路径
     */
    private String path;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 节点类型：0=文件夹, 1=文件
     */
    private Integer nodeType;

    /**
     * 文件后缀
     */
    private String suffix;

    /**
     * MIME 类型
     */
    private String contentType;

    /**
     * 匹配的高亮文本片段
     */
    private String highlight;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
