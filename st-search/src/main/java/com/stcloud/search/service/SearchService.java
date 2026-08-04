package com.stcloud.search.service;

import com.stcloud.core.entity.FileNode;
import com.stcloud.search.dto.SearchResultVO;

import java.util.List;

/**
 * ES 搜索服务：文件内容索引、搜索、删除
 */
public interface SearchService {

    /**
     * 判断文件类型是否可索引
     */
    boolean isIndexable(FileNode fileNode);

    /**
     * 索引文件到 ES
     */
    void indexFile(FileNode fileNode);

    /**
     * 通过关键词搜索文档内容
     *
     * @param keyword 搜索关键词
     * @param ownerId 用户 ID（权限过滤，null 表示不限制）
     * @param page    页码（从 1 开始）
     * @param size    每页数量
     * @return 搜索结果列表
     */
    List<SearchResultVO> searchContent(String keyword, Long ownerId, int page, int size);

    /**
     * 删除 ES 中的文件索引
     */
    void removeIndex(Long fileId);

    /**
     * 更新 ES 文档的元数据（fileName/path），不重新解析文件内容
     */
    void updateMeta(FileNode fileNode);

    /**
     * 全量重建索引：将数据库中所有正常状态的文件节点重新索引到 ES
     *
     * @return 成功索引的节点数
     */
    int reindexAll();
}
