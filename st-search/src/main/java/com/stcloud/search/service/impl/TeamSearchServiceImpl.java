package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.search.dto.SearchResultVO;
import com.stcloud.search.dto.TeamSearchResultPage;
import com.stcloud.search.init.SearchIndexInitializer;
import com.stcloud.search.service.TeamSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import co.elastic.clients.json.JsonData;

/**
 * 团队全文搜索：ES 只负责候选召回，数据库与显式主体策略负责最终授权和元数据复核。
 */
@Slf4j
@Service
public class TeamSearchServiceImpl implements TeamSearchService {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_SIZE = 50;
    private static final int MAX_KEYWORD_LENGTH = 200;
    private static final int MAX_SUFFIXES = 20;
    private static final int MAX_SUFFIX_LENGTH = 32;
    private static final int MAX_PARENT_DEPTH = 20;
    private static final long DEFAULT_CURSOR_TTL_SECONDS = 10 * 60L;

    /** 独立的业务码，避免把 ES 故障伪装成成功空页。 */
    public static final int SEARCH_UNAVAILABLE = 4601;
    public static final int SEARCH_SCOPE_TOO_LARGE = 4602;
    public static final int SEARCH_CURSOR_INVALID = 4603;
    public static final int SEARCH_CURSOR_EXPIRED = 4604;

    private final ElasticsearchClient client;
    private final FileNodeMapper fileNodeMapper;
    private final TeamFileAccessPolicy accessPolicy;
    // 未配置共享密钥时仍可在单实例内安全分页；重启会使旧游标失效。
    private final String ephemeralCursorSecret = newEphemeralCursorSecret();

    @Value("${stcloud.search.team-max-candidates:2000}")
    private int maxCandidates = 2000;

    /** 生产必须由外部安全配置注入；不提供源码默认密钥。 */
    @Value("${stcloud.search.team-cursor-secret:${STCLOUD_SEARCH_TEAM_CURSOR_SECRET:}}")
    private String cursorSecret = "";

    @Value("${stcloud.search.team-cursor-ttl-seconds:600}")
    private long cursorTtlSeconds = DEFAULT_CURSOR_TTL_SECONDS;

    /**
     * 允许集成测试使用独立索引；生产默认仍使用既有 file_content 索引。
     */
    @Value("${stcloud.search.team-index:file_content}")
    private String teamSearchIndex = SearchIndexInitializer.INDEX_NAME;

    public TeamSearchServiceImpl(ElasticsearchClient client, FileNodeMapper fileNodeMapper,
                                 TeamFileAccessPolicy accessPolicy) {
        this.client = client;
        this.fileNodeMapper = fileNodeMapper;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public TeamSearchResultPage search(Long tenantId, Long userId, Long spaceId, Long folderId,
                                       String keyword, int size, String cursor, Integer nodeType,
                                       List<String> suffixes, Long sizeMin, Long sizeMax,
                                       Long dateFrom, Long dateTo) {
        validateRequest(tenantId, userId, spaceId, folderId, keyword, size, nodeType, suffixes,
                sizeMin, sizeMax, dateFrom, dateTo);
        String normalizedKeyword = keyword.trim();
        List<String> normalizedSuffixes = normalizeSuffixes(suffixes);
        String queryHash = queryHash(spaceId, folderId, normalizedKeyword, size, nodeType,
                normalizedSuffixes, sizeMin, sizeMax, dateFrom, dateTo);

        CursorState cursorState = parseCursor(cursor, tenantId, userId, spaceId, folderId, queryHash);
        String folderPath = null;
        try {
            if (!accessPolicy.isActiveMember(tenantId, userId, spaceId)) {
                throw new BusinessException(4605, "团队成员无权访问该空间");
            }
            if (folderId != null) {
                FileNode folder = findNode(tenantId, spaceId, folderId);
                if (folder == null || !isVisibleNode(folder) || !folder.isFolder()
                        || !accessPolicy.canView(tenantId, userId, spaceId, folderId)) {
                    throw new BusinessException(4605, "搜索目录无权访问");
                }
                folderPath = folder.getPath();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("团队搜索授权依赖暂不可用: tenantId={}, userId={}, spaceId={}, error={}",
                    tenantId, userId, spaceId, e.getMessage());
            throw new BusinessException(SEARCH_UNAVAILABLE, "SEARCH_UNAVAILABLE");
        }

        List<SearchCandidate> visible = new ArrayList<>(size + 1);
        List<FieldValue> searchAfter = cursorState == null
                ? null : List.of(FieldValue.of(cursorState.lastSort()));
        int scanned = 0;
        boolean endOfSearch = false;
        String lastEsSort = cursorState == null ? null : cursorState.lastSort();
        List<SearchResultVO> finalVisible = List.of();

        try {
            // 扫描阶段只在本请求内复用成员、祖先与权限规则；输出前仍走无缓存的即时核权。
            TeamFileAccessPolicy.ReadContext scanAccess = accessPolicy.openReadContext(tenantId, userId, spaceId);
            if (scanAccess == null) {
                scanAccess = nodeId -> accessPolicy.canView(tenantId, userId, spaceId, nodeId);
            }
            Map<Long, FileNode> scopeAncestors = new HashMap<>();
            while (true) {
                if (visible.size() > size) {
                    // lookahead 候选也必须经过最终即时核权；若它刚被撤权，继续扫描剩余候选。
                    finalVisible = recheckVisible(visible, tenantId, userId, spaceId, folderId,
                            normalizedKeyword);
                    if (finalVisible.size() > size || endOfSearch) {
                        break;
                    }
                } else if (endOfSearch) {
                    finalVisible = recheckVisible(visible, tenantId, userId, spaceId, folderId,
                            normalizedKeyword);
                    break;
                }
                if (scanned >= maxCandidates) {
                    throw scopeTooLarge();
                }
                int requestSize = Math.min(MAX_BATCH_SIZE, maxCandidates - scanned);
                SearchResponse<Map> response = executeSearch(normalizedKeyword, tenantId, spaceId,
                        nodeType, normalizedSuffixes, sizeMin, sizeMax, dateFrom, dateTo,
                        folderPath, requestSize, searchAfter);
                List<Hit<Map>> hits = response.hits() == null || response.hits().hits() == null
                        ? List.of() : response.hits().hits();
                if (hits.isEmpty()) {
                    endOfSearch = true;
                    break;
                }

                Map<Long, FileNode> nodes = loadNodes(hits, tenantId, spaceId);
                for (Hit<Map> hit : hits) {
                    scanned++;
                    Map<String, Object> source = hit.source();
                    Long fileId = source == null ? null : toLong(source.get(SearchIndexInitializer.FIELD_FILE_ID));
                    FileNode node = fileId == null ? null : nodes.get(fileId);
                    String sort = sortValue(hit, source, fileId);
                    if (sort != null) {
                        lastEsSort = sort;
                    }

                    // 先做 DB 身份/状态/索引元数据复核，再调用策略或构造任何敏感输出。
                    if (source == null || node == null || !metadataMatches(source, node)
                            || !isVisibleNode(node)
                            || (folderId != null && !isDescendantOrSelf(node, folderId, tenantId, spaceId, scopeAncestors))
                            || !scanAccess.canView(node.getId())) {
                        continue;
                    }
                    // 延迟组装 VO，避免最终即时复核前保留旧名称、路径或正文高亮。
                    visible.add(new SearchCandidate(source, hit, node));
                }

                if (hits.size() < requestSize
                        || (response.hits().total() != null
                        && response.hits().total().relation() == TotalHitsRelation.Eq
                        && response.hits().total().value() <= scanned)) {
                    endOfSearch = true;
                }
                if (scanned >= maxCandidates) {
                    if (!endOfSearch && visible.size() <= size) {
                        throw scopeTooLarge();
                    }
                    // ES 已明确结束时，即使刚好扫描到预算上限也可以安全返回；
                    // 未结束且已有 lookahead 时交给下一轮做最终复核。
                    continue;
                }
                searchAfter = searchAfterForLastHit(hits, lastEsSort);
            }

            // 返回前再次检查成员与已选节点，权限撤销时宁可缩页也不泄漏元数据。
            if (!accessPolicy.isActiveMember(tenantId, userId, spaceId)) {
                throw new BusinessException(4605, "团队成员无权访问该空间");
            }
            finalVisible = recheckVisible(visible, tenantId, userId, spaceId, folderId,
                    normalizedKeyword);
            List<SearchResultVO> records = new ArrayList<>(finalVisible.subList(0,
                    Math.min(size, finalVisible.size())));
            boolean hasMore = finalVisible.size() > size;
            String nextCursor = null;
            if (hasMore && !records.isEmpty()) {
                // 游标落在最后一条实际返回记录，不能越过 lookahead。
                nextCursor = createCursor(tenantId, userId, spaceId, folderId, queryHash,
                        String.valueOf(records.get(records.size() - 1).getFileId()));
            }
            return new TeamSearchResultPage(records, hasMore, nextCursor);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 日志不记录关键词、文件名、正文、路径或 ES 内部地址。
            log.warn("团队搜索暂不可用: tenantId={}, userId={}, spaceId={}, scanned={}, error={}",
                    tenantId, userId, spaceId, scanned, e.getMessage());
            throw new BusinessException(SEARCH_UNAVAILABLE, "SEARCH_UNAVAILABLE");
        }
    }

    private List<SearchResultVO> recheckVisible(List<SearchCandidate> candidates, Long tenantId,
                                                 Long userId, Long spaceId, Long folderId,
                                                 String keyword) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (SearchCandidate candidate : candidates) {
            if (candidate.indexedNode() != null && candidate.indexedNode().getId() != null) {
                ids.add(candidate.indexedNode().getId());
            }
        }
        Map<Long, FileNode> currentNodes = loadNodesByIds(ids, tenantId, spaceId);
        List<SearchResultVO> result = new ArrayList<>(candidates.size());
        for (SearchCandidate candidate : candidates) {
            FileNode indexedNode = candidate.indexedNode();
            FileNode currentNode = indexedNode == null ? null : currentNodes.get(indexedNode.getId());
            // 重新从数据库读取名称、路径和 MD5，防止索引命中后内容或目录权限发生变化时
            // 把旧正文高亮、旧路径或已失效元数据返回给调用方。
            if (currentNode != null
                    && metadataMatches(candidate.source(), currentNode)
                    && isVisibleNode(currentNode)
                    && (folderId == null || isDescendantOrSelf(currentNode, folderId, tenantId, spaceId))
                    && accessPolicy.canView(tenantId, userId, spaceId, currentNode.getId())) {
                result.add(toVO(candidate.source(), candidate.hit(), keyword, currentNode));
            }
        }
        return result;
    }

    private SearchResponse<Map> executeSearch(String keyword, Long tenantId, Long spaceId,
                                              Integer nodeType, List<String> suffixes,
                                              Long sizeMin, Long sizeMax, Long dateFrom, Long dateTo,
                                              String folderPath, int batchSize,
                                              List<FieldValue> searchAfter) throws Exception {
        String contentField = SearchIndexInitializer.FIELD_ATTACHMENT + "." + SearchIndexInitializer.FIELD_CONTENT;
        String fileNameField = SearchIndexInitializer.FIELD_FILE_NAME;
        String escapedKeyword = keyword.toLowerCase(Locale.ROOT).replaceAll("[*?\\\\]", "\\\\$0");
        return client.search(s -> {
            s.index(teamSearchIndex)
                    .size(batchSize)
                    .sort(so -> so.field(f -> f.field(SearchIndexInitializer.FIELD_FILE_ID)
                            .order(SortOrder.Asc)))
                    .query(q -> q.bool(b -> {
                        b.should(m -> m.match(mm -> mm.field(contentField).query(keyword)));
                        b.should(m -> m.matchPhrasePrefix(mm -> mm.field(contentField).query(keyword)));
                        b.should(m -> m.match(mm -> mm.field(fileNameField).query(keyword)));
                        b.should(m -> m.matchPhrasePrefix(mm -> mm.field(fileNameField).query(keyword)));
                        b.should(m -> m.wildcard(w -> w.field(fileNameField).wildcard("*" + escapedKeyword + "*")));
                        b.minimumShouldMatch("1");
                        b.filter(f -> f.term(t -> t.field(SearchIndexInitializer.FIELD_TENANT_ID).value(tenantId)));
                        b.filter(f -> f.term(t -> t.field(SearchIndexInitializer.FIELD_SPACE_ID).value(spaceId)));
                        if (folderPath != null && !folderPath.isBlank()) {
                            String prefix = folderPath.endsWith("/") ? folderPath : folderPath + "/";
                             b.filter(f -> f.bool(scope -> {
                                 scope.should(termQuery -> termQuery.term(t -> t.field(SearchIndexInitializer.FIELD_PATH).value(folderPath)));
                                 scope.should(prefixQuery -> prefixQuery.prefix(p -> p.field(SearchIndexInitializer.FIELD_PATH).value(prefix)));
                                scope.minimumShouldMatch("1");
                                return scope;
                            }));
                        }
                        if (nodeType != null) {
                            b.filter(f -> f.term(t -> t.field(SearchIndexInitializer.FIELD_NODE_TYPE).value(nodeType)));
                        }
                        if (suffixes != null && !suffixes.isEmpty()) {
                            b.filter(f -> f.terms(t -> t.field(SearchIndexInitializer.FIELD_SUFFIX)
                                    .terms(tt -> tt.value(suffixes.stream().map(FieldValue::of).toList()))));
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
            if (searchAfter != null && !searchAfter.isEmpty()) {
                s.searchAfter(searchAfter);
            }
            s.highlight(h -> h.fields(contentField,
                    f -> f.preTags("<em>").postTags("</em>").fragmentSize(150).numberOfFragments(3)));
            return s;
        }, Map.class);
    }

    private Map<Long, FileNode> loadNodes(List<Hit<Map>> hits, Long tenantId, Long spaceId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Hit<Map> hit : hits) {
            if (hit.source() != null) {
                Long id = toLong(hit.source().get(SearchIndexInitializer.FIELD_FILE_ID));
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loadNodesByIds(ids, tenantId, spaceId);
    }

    private Map<Long, FileNode> loadNodesByIds(Set<Long> ids, Long tenantId, Long spaceId) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<FileNode> nodes = fileNodeMapper.selectBatchIds(ids);
        Map<Long, FileNode> result = new HashMap<>();
        if (nodes != null) {
            for (FileNode node : nodes) {
                if (node != null && node.getId() != null
                        && Objects.equals(node.getTenantId(), tenantId)
                        && Objects.equals(node.getSpaceId(), spaceId)) {
                    result.put(node.getId(), node);
                }
            }
        }
        return result;
    }

    private boolean metadataMatches(Map<String, Object> source, FileNode node) {
        if (!Objects.equals(node.getId(), toLong(source.get(SearchIndexInitializer.FIELD_FILE_ID)))) {
            return false;
        }
        // 旧文档缺失身份字段时必须安全排除，不能用当前 DB 节点反推租户/空间。
        Long indexedNodeType = toLong(source.get(SearchIndexInitializer.FIELD_NODE_TYPE));
        if (!Objects.equals(node.getTenantId(), toLong(source.get(SearchIndexInitializer.FIELD_TENANT_ID)))
                || !Objects.equals(node.getSpaceId(), toLong(source.get(SearchIndexInitializer.FIELD_SPACE_ID)))
                || indexedNodeType == null
                || !Objects.equals(node.getNodeType(), indexedNodeType.intValue())) {
            return false;
        }
        if (!Objects.equals(node.getName(), source.get(SearchIndexInitializer.FIELD_FILE_NAME))
                || !Objects.equals(node.getPath(), source.get(SearchIndexInitializer.FIELD_PATH))) {
            return false;
        }
        if (node.isFile()) {
            Object indexedMd5 = source.get(SearchIndexInitializer.FIELD_FILE_MD5);
            return node.getFileMd5() != null && indexedMd5 != null
                    && Objects.equals(node.getFileMd5(), String.valueOf(indexedMd5));
        }
        return true;
    }

    private boolean isVisibleNode(FileNode node) {
        if (node.getDeleted() != null && node.getDeleted() != 0) {
            return false;
        }
        if (node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()
                || (node.getHidden() != null && node.getHidden() != 0)) {
            return false;
        }
        if (node.isFile()) {
            return node.getUploadStatus() != null && node.getUploadStatus() == 2;
        }
        return node.isFolder();
    }

    private FileNode findNode(Long tenantId, Long spaceId, Long nodeId) {
        return fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getId, nodeId)
                .eq(FileNode::getTenantId, tenantId)
                .eq(FileNode::getSpaceId, spaceId)
                .eq(FileNode::getDeleted, 0));
    }

    private boolean isDescendantOrSelf(FileNode node, Long folderId, Long tenantId, Long spaceId) {
        return isDescendantOrSelf(node, folderId, tenantId, spaceId, null);
    }

    private boolean isDescendantOrSelf(FileNode node, Long folderId, Long tenantId, Long spaceId,
                                       Map<Long, FileNode> ancestorCache) {
        Set<Long> visited = new HashSet<>();
        FileNode current = node;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && current != null; depth++) {
            if (current.getId() == null || !visited.add(current.getId())) {
                return false;
            }
            if (folderId.equals(current.getId())) {
                return true;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId <= 0) {
                return false;
            }
            if (ancestorCache != null && ancestorCache.containsKey(parentId)) {
                current = ancestorCache.get(parentId);
            } else {
                current = findNode(tenantId, spaceId, parentId);
                if (ancestorCache != null) ancestorCache.put(parentId, current);
            }
        }
        return false;
    }

    private SearchResultVO toVO(Map<String, Object> source, Hit<Map> hit, String keyword, FileNode node) {
        SearchResultVO vo = new SearchResultVO();
        vo.setFileId(node.getId());
        vo.setFileName(node.getName());
        vo.setPath(node.getPath());
        vo.setFileSize(node.getFileSize());
        vo.setSuffix(node.getSuffix());
        vo.setContentType(node.getContentType());
        vo.setNodeType(node.getNodeType());
        vo.setSpaceId(node.getSpaceId());
        vo.setParentId(node.getParentId());
        vo.setCreatedAt(node.getCreatedAt() != null ? node.getCreatedAt() : epochToDate(source.get(SearchIndexInitializer.FIELD_CREATED_AT)));
        vo.setUpdatedAt(node.getUpdatedAt() != null ? node.getUpdatedAt() : epochToDate(source.get(SearchIndexInitializer.FIELD_UPDATED_AT)));

        String contentField = SearchIndexInitializer.FIELD_ATTACHMENT + "." + SearchIndexInitializer.FIELD_CONTENT;
        if (hit.highlight() != null) {
            List<String> fragments = hit.highlight().get(contentField);
            if (fragments != null && !fragments.isEmpty()) {
                vo.setHighlight(String.join(" ... ", fragments));
            }
        }
        if (vo.getHighlight() == null && node.getName() != null && !keyword.isBlank()) {
            String lowerName = node.getName().toLowerCase(Locale.ROOT);
            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
            int index = lowerName.indexOf(lowerKeyword);
            if (index >= 0) {
                int start = Math.max(0, index - 30);
                int end = Math.min(node.getName().length(), index + keyword.length() + 30);
                String prefix = start > 0 ? "..." : "";
                String suffix = end < node.getName().length() ? "..." : "";
                vo.setHighlight(prefix + node.getName().substring(start, index)
                        + "<em>" + node.getName().substring(index, index + keyword.length()) + "</em>"
                        + node.getName().substring(index + keyword.length(), end) + suffix);
            }
        }
        return vo;
    }

    private List<FieldValue> searchAfterForLastHit(List<Hit<Map>> hits, String fallback) {
        Hit<Map> last = hits.get(hits.size() - 1);
        String value = sortValue(last, last.source(),
                last.source() == null ? null : toLong(last.source().get(SearchIndexInitializer.FIELD_FILE_ID)));
        if (value == null) {
            value = fallback;
        }
        return value == null ? null : List.of(FieldValue.of(value));
    }

    private String sortValue(Hit<Map> hit, Map<String, Object> source, Long fileId) {
        if (hit.sort() != null && !hit.sort().isEmpty()) {
            FieldValue value = hit.sort().get(0);
            if (value.isString()) {
                return value.stringValue();
            }
            if (value.isLong()) {
                return String.valueOf(value.longValue());
            }
            if (value.isDouble()) {
                return String.valueOf(value.doubleValue());
            }
        }
        return fileId == null ? null : String.valueOf(fileId);
    }

    private void validateRequest(Long tenantId, Long userId, Long spaceId, Long folderId,
                                 String keyword, int size, Integer nodeType, List<String> suffixes,
                                 Long sizeMin, Long sizeMax, Long dateFrom, Long dateTo) {
        if (!validId(tenantId) || !validId(userId) || !validId(spaceId)
                || (folderId != null && !validId(folderId))) {
            throw badRequest("团队搜索主体和空间 ID 必须为正整数");
        }
        if (keyword == null || keyword.trim().isEmpty() || keyword.trim().length() > MAX_KEYWORD_LENGTH) {
            throw badRequest("keyword 长度必须为 1～200");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw badRequest("size 必须为 1～50");
        }
        if (nodeType != null && nodeType != NodeType.FILE.getCode() && nodeType != NodeType.FOLDER.getCode()) {
            throw badRequest("nodeType 无效");
        }
        normalizeSuffixes(suffixes);
        if (sizeMin != null && sizeMin < 0 || sizeMax != null && sizeMax < 0
                || sizeMin != null && sizeMax != null && sizeMin > sizeMax) {
            throw badRequest("文件大小范围无效");
        }
        if (dateFrom != null && dateFrom < 0 || dateTo != null && dateTo < 0
                || dateFrom != null && dateTo != null && dateFrom > dateTo) {
            throw badRequest("时间范围无效");
        }
    }

    private List<String> normalizeSuffixes(List<String> suffixes) {
        if (suffixes == null || suffixes.isEmpty()) {
            return List.of();
        }
        if (suffixes.size() > MAX_SUFFIXES) {
            throw badRequest("suffixes 数量超限");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String suffix : suffixes) {
            if (suffix == null) {
                throw badRequest("suffix 无效");
            }
            String value = suffix.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty() || value.length() > MAX_SUFFIX_LENGTH) {
                throw badRequest("suffix 长度无效");
            }
            normalized.add(value);
        }
        List<String> result = new ArrayList<>(normalized);
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private String queryHash(Long spaceId, Long folderId, String keyword, int size, Integer nodeType,
                             List<String> suffixes, Long sizeMin, Long sizeMax,
                             Long dateFrom, Long dateTo) {
        String canonical = String.join("|", List.of(
                String.valueOf(spaceId), String.valueOf(folderId == null ? "-" : folderId), keyword,
                String.valueOf(size), String.valueOf(nodeType == null ? "-" : nodeType),
                String.join(",", suffixes), String.valueOf(sizeMin == null ? "-" : sizeMin),
                String.valueOf(sizeMax == null ? "-" : sizeMax), String.valueOf(dateFrom == null ? "-" : dateFrom),
                String.valueOf(dateTo == null ? "-" : dateTo)));
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(SEARCH_UNAVAILABLE, "SEARCH_UNAVAILABLE");
        }
    }

    private String createCursor(Long tenantId, Long userId, Long spaceId, Long folderId,
                                String queryHash, String lastSort) {
        String secret = requireCursorSecret();
        long expiresAt = Instant.now().getEpochSecond() + Math.max(1L, cursorTtlSeconds);
        String payload = String.join(";", "1", String.valueOf(tenantId), String.valueOf(userId),
                String.valueOf(spaceId), String.valueOf(folderId == null ? "-" : folderId),
                queryHash, lastSort, String.valueOf(expiresAt));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encodedPayload = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = encoder.encodeToString(hmac(secret, payload.getBytes(StandardCharsets.UTF_8)));
        return encodedPayload + "." + signature;
    }

    private CursorState parseCursor(String token, Long tenantId, Long userId, Long spaceId,
                                    Long folderId, String queryHash) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String secret = requireCursorSecret();
            String[] pieces = token.split("\\.", -1);
            if (pieces.length != 2) {
                throw cursorInvalid();
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] payloadBytes = decoder.decode(pieces[0]);
            byte[] signature = decoder.decode(pieces[1]);
            // Base64URL 最后一位可能包含未使用位；必须拒绝非规范编码，避免同一签名有多个可接受的游标文本。
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            if (!pieces[0].equals(encoder.encodeToString(payloadBytes))
                    || !pieces[1].equals(encoder.encodeToString(signature))) {
                throw cursorInvalid();
            }
            byte[] expected = hmac(secret, payloadBytes);
            if (!MessageDigest.isEqual(expected, signature)) {
                throw cursorInvalid();
            }
            String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split(";", -1);
            if (fields.length != 8 || !"1".equals(fields[0])) {
                throw cursorInvalid();
            }
            long tokenTenant = Long.parseLong(fields[1]);
            long tokenUser = Long.parseLong(fields[2]);
            long tokenSpace = Long.parseLong(fields[3]);
            Long tokenFolder = "-".equals(fields[4]) ? null : Long.valueOf(fields[4]);
            long expiresAt = Long.parseLong(fields[7]);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                throw new BusinessException(SEARCH_CURSOR_EXPIRED, "SEARCH_CURSOR_EXPIRED");
            }
            if (!Objects.equals(tenantId, tokenTenant) || !Objects.equals(userId, tokenUser)
                    || !Objects.equals(spaceId, tokenSpace) || !Objects.equals(folderId, tokenFolder)
                    || !Objects.equals(queryHash, fields[5]) || fields[6].isBlank()
                    || !validPositiveNumber(fields[6])) {
                throw cursorInvalid();
            }
            return new CursorState(fields[6]);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw cursorInvalid();
        }
    }

    private String requireCursorSecret() {
        String secret = cursorSecret;
        if (secret == null || secret.isBlank()) {
            // 兼容聚合应用未加载 st-search 自带 application.yml 的场景，仍只从环境变量取密钥。
            secret = System.getenv("STCLOUD_SEARCH_TEAM_CURSOR_SECRET");
        }
        return secret == null || secret.isBlank() ? ephemeralCursorSecret : secret;
    }

    private static String newEphemeralCursorSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new BusinessException(SEARCH_UNAVAILABLE, "SEARCH_UNAVAILABLE");
        }
    }

    private boolean validPositiveNumber(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    private BusinessException cursorInvalid() {
        return new BusinessException(SEARCH_CURSOR_INVALID, "SEARCH_CURSOR_INVALID");
    }

    private BusinessException scopeTooLarge() {
        return new BusinessException(SEARCH_SCOPE_TOO_LARGE, "SEARCH_SCOPE_TOO_LARGE");
    }

    private boolean validId(Long value) {
        return value != null && value > 0;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.valueOf(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private LocalDateTime epochToDate(Object value) {
        Long millis = toLong(value);
        return millis == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record CursorState(String lastSort) {
    }

    private record SearchCandidate(Map<String, Object> source, Hit<Map> hit, FileNode indexedNode) {
    }
}
