package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.StorageService;
import com.stcloud.search.dto.SearchResultVO;
import com.stcloud.search.init.SearchIndexInitializer;
import com.stcloud.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.json.JsonData;

/**
 * ES 搜索服务：文件内容索引、搜索、删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final ElasticsearchClient client;
    private final StorageService storageService;
    private final FileNodeMapper fileNodeMapper;

    /**
     * 可索引的文件后缀（小写）
     */
    private static final Set<String> INDEXABLE_SUFFIXES = Set.of(
            "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    );

    /**
     * 文件大小限制：20MB（ES ingest-attachment 对大文件解析耗时较长）
     */
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;

    /**
     * 判断文件类型是否可索引内容（走 pipeline 提取文本）
     * 文件夹、图片、视频等返回 false，但仍然会索引元数据
     */
    @Override
    public boolean isIndexable(FileNode fileNode) {
        if (fileNode == null || fileNode.getSuffix() == null) {
            return false;
        }
        return INDEXABLE_SUFFIXES.contains(fileNode.getSuffix().toLowerCase());
    }

    /**
     * 索引文件到 ES
     * - 文件夹 / 不可索引文件 / 超大文件：只索引元数据（不走 pipeline）
     * - 可索引文件：下载内容 → Base64 → pipeline 提取文本 → 索引元数据+内容
     */
    @Override
    public void indexFile(FileNode fileNode) {
        if (fileNode == null) {
            return;
        }

        // 文件夹、不可索引文件类型、超大文件：只索引元数据
        if (fileNode.isFolder() || !isIndexable(fileNode)
                || (fileNode.getFileSize() != null && fileNode.getFileSize() > MAX_FILE_SIZE)) {
            indexMetadata(fileNode);
            return;
        }

        // 可索引文件：下载内容并通过 pipeline 索引
        try (InputStream is = storageService.downloadObject(fileNode.getStoragePath())) {
            byte[] bytes = is.readAllBytes();
            String base64Data = Base64.getEncoder().encodeToString(bytes);

            Map<String, Object> doc = new HashMap<>();
            doc.put(SearchIndexInitializer.FIELD_FILE_ID, fileNode.getId());
            doc.put(SearchIndexInitializer.FIELD_FILE_NAME, fileNode.getName());
            doc.put(SearchIndexInitializer.FIELD_OWNER_ID, fileNode.getOwnerId());
            doc.put(SearchIndexInitializer.FIELD_STORAGE_PATH, fileNode.getStoragePath());
            doc.put(SearchIndexInitializer.FIELD_CONTENT_TYPE, fileNode.getContentType());
            doc.put(SearchIndexInitializer.FIELD_SUFFIX, fileNode.getSuffix());
            doc.put(SearchIndexInitializer.FIELD_FILE_SIZE, fileNode.getFileSize());
            doc.put(SearchIndexInitializer.FIELD_PATH, fileNode.getPath());
            doc.put(SearchIndexInitializer.FIELD_NODE_TYPE, fileNode.getNodeType());
            doc.put(SearchIndexInitializer.FIELD_CREATED_AT, fileNode.getCreatedAt() != null ? fileNode.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli() : null);
            doc.put(SearchIndexInitializer.FIELD_UPDATED_AT, fileNode.getUpdatedAt() != null ? fileNode.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli() : null);
            doc.put(SearchIndexInitializer.FIELD_DATA, base64Data);

            client.index(i -> i
                    .index(SearchIndexInitializer.INDEX_NAME)
                    .id(String.valueOf(fileNode.getId()))
                    .pipeline(SearchIndexInitializer.PIPELINE_NAME)
                    .document(doc)
            );
            log.info("Indexed file {} to ES, name={}, size={}bytes", fileNode.getId(), fileNode.getName(), bytes.length);
        } catch (Exception e) {
            log.warn("Failed to index file {} to ES: {}", fileNode.getId(), e.getMessage());
        }
    }

    /**
     * 只索引元数据到 ES（不下载文件内容，不走 pipeline）
     * 用于文件夹、不可索引文件类型、超大文件
     */
    private void indexMetadata(FileNode fileNode) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put(SearchIndexInitializer.FIELD_FILE_ID, fileNode.getId());
            doc.put(SearchIndexInitializer.FIELD_FILE_NAME, fileNode.getName());
            doc.put(SearchIndexInitializer.FIELD_OWNER_ID, fileNode.getOwnerId());
            doc.put(SearchIndexInitializer.FIELD_STORAGE_PATH, fileNode.getStoragePath());
            doc.put(SearchIndexInitializer.FIELD_CONTENT_TYPE, fileNode.getContentType());
            doc.put(SearchIndexInitializer.FIELD_SUFFIX, fileNode.getSuffix());
            doc.put(SearchIndexInitializer.FIELD_FILE_SIZE, fileNode.getFileSize());
            doc.put(SearchIndexInitializer.FIELD_PATH, fileNode.getPath());
            doc.put(SearchIndexInitializer.FIELD_NODE_TYPE, fileNode.getNodeType());
            doc.put(SearchIndexInitializer.FIELD_CREATED_AT, fileNode.getCreatedAt() != null ? fileNode.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli() : null);
            doc.put(SearchIndexInitializer.FIELD_UPDATED_AT, fileNode.getUpdatedAt() != null ? fileNode.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli() : null);

            client.index(i -> i
                    .index(SearchIndexInitializer.INDEX_NAME)
                    .id(String.valueOf(fileNode.getId()))
                    .document(doc)
            );
            log.info("Indexed metadata for node {} to ES, name={}", fileNode.getId(), fileNode.getName());
        } catch (Exception e) {
            log.warn("Failed to index metadata for node {} to ES: {}", fileNode.getId(), e.getMessage());
        }
    }

    /**
     * 通过关键词搜索文件名和文档内容
     *
     * @param keyword 搜索关键词
     * @param ownerId 用户 ID（权限过滤，null 表示不限制）
     * @param page    页码（从 1 开始）
     * @param size    每页数量
     * @return 搜索结果列表
     */
    @Override
    public List<SearchResultVO> searchContent(String keyword, Long ownerId, int page, int size,
            Integer nodeType, List<String> suffixes, Long sizeMin, Long sizeMax, Long dateFrom, Long dateTo) {
        log.info("Search request: keyword='{}', ownerId={}, page={}, size={}, filters=[nodeType={}, suffixes={}, sizeMin={}, sizeMax={}, dateFrom={}, dateTo={}]", keyword, ownerId, page, size, nodeType, suffixes, sizeMin, sizeMax, dateFrom, dateTo);
        try {
            int from = Math.max(0, (page - 1) * size);
            String contentField = SearchIndexInitializer.FIELD_ATTACHMENT + "." + SearchIndexInitializer.FIELD_CONTENT;
            String fileNameField = SearchIndexInitializer.FIELD_FILE_NAME;

            boolean matchAll = keyword == null || keyword.isBlank() || "*".equals(keyword.trim());
            SearchResponse<Map> response = client.search(s -> {
                s.index(SearchIndexInitializer.INDEX_NAME)
                        .from(from)
                        .size(size)
                        .query(q -> q.bool(b -> {
                            if (matchAll) {
                                // 无关键词（首页按文件类型浏览）：匹配全部，仅靠过滤器筛选
                                b.must(m -> m.matchAll(ma -> ma));
                            } else {
                                // 文件名 OR 内容匹配
                                b.should(m -> m.match(mm -> mm
                                        .field(contentField)
                                        .query(keyword)
                                ));
                                b.should(m -> m.matchPhrasePrefix(mpp -> mpp
                                        .field(contentField)
                                        .query(keyword)
                                ));
                                b.should(m -> m.match(mm -> mm
                                        .field(fileNameField)
                                        .query(keyword)
                                ));
                                b.should(m -> m.matchPhrasePrefix(mpp -> mpp
                                        .field(fileNameField)
                                        .query(keyword)
                                ));
                                String escapedKw = keyword.toLowerCase().replaceAll("[*?\\\\]", "\\\\$0");
                                b.should(m -> m.wildcard(w -> w
                                        .field(fileNameField)
                                        .wildcard("*" + escapedKw + "*")
                                ));
                                // 英文子串兜底：IK 对粘连/驼峰英文合成单 token，standard/ngram 子字段补足召回
                                b.should(m -> m.match(mm -> mm
                                        .field(contentField + "." + SearchIndexInitializer.FIELD_ENG_SUFFIX)
                                        .query(keyword)
                                ));
                                b.should(m -> m.match(mm -> mm
                                        .field(contentField + "." + SearchIndexInitializer.FIELD_NGRAM_SUFFIX)
                                        .query(keyword)
                                ));
                                b.should(m -> m.match(mm -> mm
                                        .field(fileNameField + "." + SearchIndexInitializer.FIELD_ENG_SUFFIX)
                                        .query(keyword)
                                ));
                                b.should(m -> m.match(mm -> mm
                                        .field(fileNameField + "." + SearchIndexInitializer.FIELD_NGRAM_SUFFIX)
                                        .query(keyword)
                                ));
                                b.minimumShouldMatch("1");
                            }
                            if (ownerId != null) {
                                b.filter(f -> f.term(t -> t
                                        .field(SearchIndexInitializer.FIELD_OWNER_ID)
                                        .value(ownerId)
                                ));
                            }
                            if (nodeType != null) {
                                b.filter(f -> f.term(t -> t
                                        .field(SearchIndexInitializer.FIELD_NODE_TYPE)
                                        .value(nodeType)
                                ));
                            }
                            if (suffixes != null && !suffixes.isEmpty()) {
                                b.filter(f -> f.terms(t -> t
                                        .field(SearchIndexInitializer.FIELD_SUFFIX)
                                        .terms(tt -> tt.value(suffixes.stream().map(FieldValue::of).toList()))
                                ));
                            }
                            if (sizeMin != null || sizeMax != null) {
                                b.filter(f -> f.range(r -> {
                                    r.field(SearchIndexInitializer.FIELD_FILE_SIZE);
                                    if (sizeMin != null) r.gte(JsonData.of(sizeMin));
                                    if (sizeMax != null) r.lte(JsonData.of(sizeMax));
                                    return r;
                                }));
                            }
                            if (dateFrom != null || dateTo != null) {
                                b.filter(f -> f.range(r -> {
                                    r.field(SearchIndexInitializer.FIELD_UPDATED_AT);
                                    if (dateFrom != null) r.gte(JsonData.of(dateFrom));
                                    if (dateTo != null) r.lte(JsonData.of(dateTo));
                                    return r;
                                }));
                            }
                            return b;
                        }));
                if (!matchAll) {
                    s.highlight(h -> h
                            .fields(contentField,
                                    f -> f.preTags("<em>").postTags("</em>").fragmentSize(150).numberOfFragments(3))
                    );
                }
                return s;
            }, Map.class);

            List<SearchResultVO> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) {
                    continue;
                }
                results.add(toVO(source, hit, keyword));
            }

            // Enrich with createdAt/updatedAt from database (ES index may not have them yet)
            if (!results.isEmpty()) {
                List<Long> fileIds = new ArrayList<>();
                for (SearchResultVO vo : results) {
                    if (vo.getFileId() != null) fileIds.add(vo.getFileId());
                }
                if (!fileIds.isEmpty()) {
                    List<FileNode> nodes = fileNodeMapper.selectBatchIds(fileIds);
                    Map<Long, FileNode> nodeMap = new HashMap<>();
                    if (nodes != null) {
                        for (FileNode node : nodes) {
                            nodeMap.put(node.getId(), node);
                        }
                    }
                    for (SearchResultVO vo : results) {
                        FileNode node = vo.getFileId() != null ? nodeMap.get(vo.getFileId()) : null;
                        if (node != null) {
                            if (vo.getCreatedAt() == null && node.getCreatedAt() != null) {
                                vo.setCreatedAt(node.getCreatedAt());
                            }
                            if (vo.getUpdatedAt() == null && node.getUpdatedAt() != null) {
                                vo.setUpdatedAt(node.getUpdatedAt());
                            }
                        }
                    }
                    // 可访问性过滤：回收/删除文件夹不再逐子孙删除索引，改为查询时按祖先链排除。
                    // 排除：节点已删除（DB 查不到）、自身非正常态、祖先链含回收/已删除节点。
                    List<Long> inaccessible = fileNodeMapper.findIdsWithInaccessibleAncestor(fileIds);
                    Set<Long> inaccessibleIds = new HashSet<>(
                            inaccessible == null ? Collections.emptyList() : inaccessible);
                    results.removeIf(vo -> {
                        if (vo.getFileId() == null) return false;
                        FileNode node = nodeMap.get(vo.getFileId());
                        if (node == null) return true;
                        if (node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()) return true;
                        return inaccessibleIds.contains(vo.getFileId());
                    });
                }
            }

            long total = response.hits().total() != null ? response.hits().total().value() : 0;
            log.info("Search completed: keyword='{}', ownerId={}, totalHits={}, returned={}",
                    keyword, ownerId, total, results.size());
            return results;
        } catch (Exception e) {
            log.error("Search failed, keyword={}, ownerId={}: {}", keyword, ownerId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除 ES 中的文件索引
     */
    @Override
    public void removeIndex(Long fileId) {
        if (fileId == null) {
            return;
        }
        try {
            client.delete(d -> d
                    .index(SearchIndexInitializer.INDEX_NAME)
                    .id(String.valueOf(fileId))
            );
            log.info("Removed file {} from ES index", fileId);
        } catch (Exception e) {
            log.warn("Failed to remove file {} from ES: {}", fileId, e.getMessage());
        }
    }

    /**
     * 更新 ES 文档的元数据（fileName/path），不重新解析文件内容
     * 用于文件移动、重命名等操作后同步 ES 中的路径信息
     */
    @Override
    public void updateMeta(FileNode fileNode) {
        if (fileNode == null || fileNode.getId() == null) {
            return;
        }
        try {
            Map<String, Object> partialDoc = new HashMap<>();
            partialDoc.put(SearchIndexInitializer.FIELD_FILE_NAME, fileNode.getName());
            partialDoc.put(SearchIndexInitializer.FIELD_PATH, fileNode.getPath());

            client.update(u -> u
                    .index(SearchIndexInitializer.INDEX_NAME)
                    .id(String.valueOf(fileNode.getId()))
                    .doc(partialDoc),
                    Map.class
            );
            log.info("Updated file meta in ES: fileId={}, name={}, path={}",
                    fileNode.getId(), fileNode.getName(), fileNode.getPath());
        } catch (Exception e) {
            log.warn("Failed to update file meta in ES: fileId={}, error={}",
                    fileNode.getId(), e.getMessage());
        }
    }

    /**
     * 全量重建索引：将数据库中所有正常状态的文件节点重新索引到 ES
     *
     * @return 成功索引的节点数
     */
    @Override
    public int reindexAll() {
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode());
        List<FileNode> nodes = fileNodeMapper.selectList(wrapper);
        int count = 0;
        for (FileNode node : nodes) {
            try {
                indexFile(node);
                count++;
            } catch (Exception e) {
                log.warn("Failed to reindex node {}: {}", node.getId(), e.getMessage());
            }
        }
        log.info("Reindexed {} / {} file nodes to ES", count, nodes.size());
        return count;
    }

    /**
     * 将 ES 文档转为 SearchResultVO
     */
    @SuppressWarnings("unchecked")
    private SearchResultVO toVO(Map<String, Object> source, Hit<Map> hit, String keyword) {
        SearchResultVO vo = new SearchResultVO();
        vo.setFileId(toLong(source.get(SearchIndexInitializer.FIELD_FILE_ID)));
        String rawFileName = (String) source.get(SearchIndexInitializer.FIELD_FILE_NAME);
        vo.setFileName(rawFileName);
        vo.setPath((String) source.get(SearchIndexInitializer.FIELD_PATH));
        vo.setFileSize(toLong(source.get(SearchIndexInitializer.FIELD_FILE_SIZE)));
        vo.setSuffix((String) source.get(SearchIndexInitializer.FIELD_SUFFIX));
        vo.setContentType((String) source.get(SearchIndexInitializer.FIELD_CONTENT_TYPE));
        Object nodeTypeVal = source.get(SearchIndexInitializer.FIELD_NODE_TYPE);
        vo.setNodeType(nodeTypeVal instanceof Number ? ((Number) nodeTypeVal).intValue() : null);

        Long createdAtMs = toLong(source.get(SearchIndexInitializer.FIELD_CREATED_AT));
        Long updatedAtMs = toLong(source.get(SearchIndexInitializer.FIELD_UPDATED_AT));
        if (createdAtMs != null) vo.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAtMs), ZoneOffset.UTC));
        if (updatedAtMs != null) vo.setUpdatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(updatedAtMs), ZoneOffset.UTC));

        // 合并高亮片段：优先内容高亮，没有则用文件名高亮
        String contentField = SearchIndexInitializer.FIELD_ATTACHMENT + "." + SearchIndexInitializer.FIELD_CONTENT;
        if (hit.highlight() != null) {
            List<String> contentFragments = hit.highlight().get(contentField);
            if (contentFragments != null && !contentFragments.isEmpty()) {
                vo.setHighlight(String.join(" ... ", contentFragments));
            }
        }
        // If no content highlight, build a file-name snippet with the keyword highlighted
        if (vo.getHighlight() == null && keyword != null && !keyword.isBlank() && rawFileName != null) {
            String lowerName = rawFileName.toLowerCase();
            String lowerKw = keyword.toLowerCase();
            int idx = lowerName.indexOf(lowerKw);
            if (idx >= 0) {
                int start = Math.max(0, idx - 30);
                int end = Math.min(rawFileName.length(), idx + keyword.length() + 30);
                String prefix = start > 0 ? "..." : "";
                String suffix = end < rawFileName.length() ? "..." : "";
                String matched = rawFileName.substring(idx, idx + keyword.length());
                String snippet = prefix + rawFileName.substring(start, idx) + "<em>" + matched + "</em>" + rawFileName.substring(idx + keyword.length(), end) + suffix;
                vo.setHighlight(snippet);
            }
        }
        return vo;
    }

    /**
     * 将 ES 返回的值安全转为 Long
     * ES keyword 字段返回 String，long 字段返回 Number，需兼容两种情况
     */
    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
