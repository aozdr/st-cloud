package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ApiTypeHelper;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.search.dto.TeamSearchResultPage;
import com.stcloud.search.init.SearchIndexInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队搜索 S01～S05 关键边界：显式成员、元数据复核、无权首批、lookahead、ES 故障。
 */
@ExtendWith(MockitoExtension.class)
class TeamSearchServiceTest {

    private static final String TEST_CURSOR_SECRET = "test-team-search-secret";

    @Mock
    private ElasticsearchClient client;
    @Mock
    private FileNodeMapper nodeMapper;
    @Mock
    private TeamFileAccessPolicy accessPolicy;

    private TeamSearchServiceImpl service;

    @BeforeAll
    static void disableRequiredPropertiesCheck() {
        ApiTypeHelper.DANGEROUS_disableRequiredPropertiesCheck(true);
    }

    @BeforeEach
    void setUp() {
        service = new TeamSearchServiceImpl(client, nodeMapper, accessPolicy);
        ReflectionTestUtils.setField(service, "cursorSecret", TEST_CURSOR_SECRET);
        ReflectionTestUtils.setField(service, "maxCandidates", 2000);
    }

    private void stubActiveMember() {
        when(accessPolicy.isActiveMember(1L, 5L, 10L)).thenReturn(true);
    }

    private FileNode file(long id, Long parentId) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(1L);
        node.setSpaceId(10L);
        node.setParentId(parentId);
        node.setName("file" + id + ".txt");
        node.setPath("/file" + id + ".txt");
        node.setFileMd5("md5-" + id);
        node.setNodeType(1);
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setFileSize(100L);
        node.setSuffix("txt");
        return node;
    }

    private FileNode folder(long id, Long parentId) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(1L);
        node.setSpaceId(10L);
        node.setParentId(parentId);
        node.setName("folder" + id);
        node.setPath("/team/folder" + id);
        node.setNodeType(0);
        node.setStatus(0);
        node.setHidden(0);
        return node;
    }

    private Map<String, Object> source(FileNode node) {
        Map<String, Object> source = new HashMap<>();
        source.put(SearchIndexInitializer.FIELD_FILE_ID, String.valueOf(node.getId()));
        source.put(SearchIndexInitializer.FIELD_FILE_NAME, node.getName());
        source.put(SearchIndexInitializer.FIELD_PATH, node.getPath());
        source.put(SearchIndexInitializer.FIELD_FILE_MD5, node.getFileMd5());
        source.put(SearchIndexInitializer.FIELD_NODE_TYPE, node.getNodeType());
        source.put(SearchIndexInitializer.FIELD_FILE_SIZE, node.getFileSize());
        source.put(SearchIndexInitializer.FIELD_SUFFIX, node.getSuffix());
        source.put(SearchIndexInitializer.FIELD_SPACE_ID, node.getSpaceId());
        source.put(SearchIndexInitializer.FIELD_TENANT_ID, node.getTenantId());
        return source;
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<Map> response(List<Map<String, Object>> sources) {
        return response(sources, sources.size());
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<Map> response(List<Map<String, Object>> sources, long total) {
        List<Hit<Map>> hits = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            String id = String.valueOf(source.get(SearchIndexInitializer.FIELD_FILE_ID));
            Hit<Map> hit = Hit.of(builder -> builder.id(id).index(SearchIndexInitializer.INDEX_NAME)
                    .source((Map<String, Object>) source)
                    .sort(List.of(FieldValue.of(id))));
            hits.add(hit);
        }
        return SearchResponse.of(builder -> builder
                .took(1L)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hitsBuilder -> hitsBuilder
                        .total(totalHits -> totalHits.value(total).relation(TotalHitsRelation.Eq))
                        .hits(hits)));
    }

    private void stubNodes(FileNode... nodes) {
        when(nodeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(nodes));
    }

    private void stubNodes(Map<Long, FileNode> nodes) {
        when(nodeMapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> nodes.get(((Number) id).longValue()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        });
    }

    private TeamSearchResultPage search(int size, String cursor) {
        return service.search(1L, 5L, 10L, null, "合同", size, cursor,
                null, null, null, null, null, null);
    }

    private String expiredCursor(String cursor) throws Exception {
        String[] pieces = cursor.split("\\.", -1);
        byte[] payload = Base64.getUrlDecoder().decode(pieces[0]);
        String[] fields = new String(payload, StandardCharsets.UTF_8).split(";", -1);
        fields[7] = String.valueOf(Instant.now().getEpochSecond() - 1);
        byte[] expiredPayload = String.join(";", fields).getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_CURSOR_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(expiredPayload) + "."
                + encoder.encodeToString(mac.doFinal(expiredPayload));
    }

    @Test
    void nonMemberIsRejectedBeforeEs() throws Exception {
        when(accessPolicy.isActiveMember(1L, 5L, 10L)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> search(20, null));

        assertEquals(4605, exception.getCode());
        verify(client, never()).search(any(Function.class), eq(Map.class));
    }

    @Test
    void unauthorizedCandidateIsFilteredWithoutSensitiveVo() throws Exception {
        stubActiveMember();
        FileNode allowed = file(1L, null);
        FileNode denied = file(2L, null);
        stubNodes(allowed, denied);
        when(accessPolicy.canView(1L, 5L, 10L, 1L)).thenReturn(true);
        when(accessPolicy.canView(1L, 5L, 10L, 2L)).thenReturn(false);
        doReturn(response(List.of(source(allowed), source(denied))))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(20, null);

        assertEquals(1, page.getRecords().size());
        assertEquals(1L, page.getRecords().get(0).getFileId());
        assertFalse(page.isHasMore());
    }

    @Test
    void candidateScanUsesRequestContextButFinalCheckRemainsFresh() throws Exception {
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        stubNodes(first, second);
        AtomicInteger scanned = new AtomicInteger();
        when(accessPolicy.openReadContext(1L, 5L, 10L)).thenReturn(nodeId -> {
            scanned.incrementAndGet();
            return true;
        });
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second))))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(20, null);

        assertEquals(2, page.getRecords().size());
        assertEquals(2, scanned.get());
        verify(accessPolicy, times(1)).openReadContext(1L, 5L, 10L);
        verify(accessPolicy, times(4)).canView(any(), any(), any(), any());
    }

    @Test
    void staleMd5IsDroppedBeforePolicyAndHighlight() throws Exception {
        stubActiveMember();
        FileNode current = file(3L, null);
        Map<String, Object> stale = source(current);
        stale.put(SearchIndexInitializer.FIELD_FILE_MD5, "old-md5");
        stubNodes(current);
        doReturn(response(List.of(stale))).when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(20, null);

        assertTrue(page.getRecords().isEmpty());
        verify(accessPolicy, never()).canView(1L, 5L, 10L, 3L);
    }

    @Test
    void md5ChangedAfterInitialCandidateCheckDropsStaleHighlightAtFinalCheck() throws Exception {
        stubActiveMember();
        FileNode indexed = file(4L, null);
        FileNode current = file(4L, null);
        current.setFileMd5("md5-version-b");
        Map<String, Object> source = source(indexed);
        when(nodeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(indexed), List.of(current));
        when(accessPolicy.canView(1L, 5L, 10L, 4L)).thenReturn(true);
        doReturn(response(List.of(source))).when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(20, null);

        assertTrue(page.getRecords().isEmpty(), "正文版本变化后不得返回旧摘要或旧高亮");
    }

    @Test
    void exactEsEndAtCandidateBudgetDoesNotReportScopeTooLarge() throws Exception {
        stubActiveMember();
        ReflectionTestUtils.setField(service, "maxCandidates", 2);
        FileNode first = file(5L, null);
        FileNode second = file(6L, null);
        stubNodes(first, second);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second))))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(2, null);

        assertEquals(2, page.getRecords().size());
        assertFalse(page.isHasMore());
    }

    @Test
    void folderScopeUsesDatabaseParentChainBeforeReturningResult() throws Exception {
        stubActiveMember();
        FileNode folder = folder(50L, null);
        FileNode child = file(51L, 50L);
        child.setPath("/team/folder50/child51.txt");
        stubNodes(child);
        when(nodeMapper.selectOne(any())).thenReturn(folder, folder);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(child)))).when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = service.search(1L, 5L, 10L, 50L, "合同", 20, null,
                null, null, null, null, null, null);

        assertEquals(1, page.getRecords().size());
        assertEquals(51L, page.getRecords().get(0).getFileId());
    }

    @Test
    void authorizedLookaheadProducesOpaqueCursorAtLastReturnedRecord() throws Exception {
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        stubNodes(first, second);
        when(accessPolicy.canView(1L, 5L, 10L, 1L)).thenReturn(true);
        when(accessPolicy.canView(1L, 5L, 10L, 2L)).thenReturn(true);
        doReturn(response(List.of(source(first), source(second))))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(1, null);

        assertEquals(1, page.getRecords().size());
        assertEquals(1L, page.getRecords().get(0).getFileId());
        assertTrue(page.isHasMore());
        assertNotNull(page.getNextCursor());
        assertFalse(page.getNextCursor().contains("file1"));
    }

    @Test
    void tamperedCursorIsRejectedAndEsIsNotCalled() throws Exception {
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        stubNodes(first, second);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second))))
                .when(client).search(any(Function.class), eq(Map.class));
        String cursor = search(1, null).getNextCursor();
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");

        BusinessException exception = assertThrows(BusinessException.class, () -> search(1, tampered));

        assertEquals(TeamSearchServiceImpl.SEARCH_CURSOR_INVALID, exception.getCode());
    }

    @Test
    void esFailureIsUnavailableInsteadOfEmptySuccessPage() throws Exception {
        stubActiveMember();
        when(client.search(any(Function.class), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        BusinessException exception = assertThrows(BusinessException.class, () -> search(20, null));

        assertEquals(TeamSearchServiceImpl.SEARCH_UNAVAILABLE, exception.getCode());
    }

    @Test
    void scansPastFirstHundredUnauthorizedCandidatesForLaterAuthorizedResult() throws Exception {
        stubActiveMember();
        Map<Long, FileNode> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> firstBatch = new ArrayList<>();
        for (long id = 1L; id <= 101L; id++) {
            FileNode node = file(id, null);
            nodes.put(id, node);
            if (id <= 100L) {
                firstBatch.add(source(node));
            }
        }
        stubNodes(nodes);
        when(accessPolicy.canView(any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Long) invocation.getArgument(3)) == 101L);
        doReturn(response(firstBatch, 101L), response(List.of(source(nodes.get(101L))), 101L))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage page = search(1, null);

        assertEquals(List.of(101L), page.getRecords().stream()
                .map(record -> record.getFileId()).toList());
        assertFalse(page.isHasMore());
        verify(client, times(2)).search(any(Function.class), eq(Map.class));
    }

    @Test
    void consecutiveCursorPagesDoNotRepeatVisibleRecords() throws Exception {
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        FileNode third = file(3L, null);
        stubNodes(first, second, third);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second)), 3L),
                response(List.of(source(second), source(third)), 3L),
                response(List.of(source(third)), 3L))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage firstPage = search(1, null);
        TeamSearchResultPage secondPage = search(1, firstPage.getNextCursor());
        TeamSearchResultPage thirdPage = search(1, secondPage.getNextCursor());

        assertEquals(List.of(1L), firstPage.getRecords().stream()
                .map(record -> record.getFileId()).toList());
        assertEquals(List.of(2L), secondPage.getRecords().stream()
                .map(record -> record.getFileId()).toList());
        assertEquals(List.of(3L), thirdPage.getRecords().stream()
                .map(record -> record.getFileId()).toList());
        assertFalse(thirdPage.isHasMore());
    }

    @Test
    void cursorIsBoundToPrincipalAndAllQueryConditions() throws Exception {
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        stubNodes(first, second);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second)), 2L))
                .when(client).search(any(Function.class), eq(Map.class));

        String cursor = search(1, null).getNextCursor();

        BusinessException otherTenant = assertThrows(BusinessException.class,
                () -> service.search(2L, 5L, 10L, null, "合同", 1, cursor,
                        null, null, null, null, null, null));
        BusinessException otherUser = assertThrows(BusinessException.class,
                () -> service.search(1L, 6L, 10L, null, "合同", 1, cursor,
                        null, null, null, null, null, null));
        BusinessException changedKeyword = assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "另一条件", 1, cursor,
                        null, null, null, null, null, null));
        BusinessException changedSize = assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 2, cursor,
                        null, null, null, null, null, null));

        assertEquals(TeamSearchServiceImpl.SEARCH_CURSOR_INVALID, otherTenant.getCode());
        assertEquals(TeamSearchServiceImpl.SEARCH_CURSOR_INVALID, otherUser.getCode());
        assertEquals(TeamSearchServiceImpl.SEARCH_CURSOR_INVALID, changedKeyword.getCode());
        assertEquals(TeamSearchServiceImpl.SEARCH_CURSOR_INVALID, changedSize.getCode());
        verify(client, times(1)).search(any(Function.class), eq(Map.class));
    }

    @Test
    void expiredCursorIsRejectedBeforeEs() throws Exception {
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        stubNodes(first, second);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second)), 2L))
                .when(client).search(any(Function.class), eq(Map.class));

        String cursor = search(1, null).getNextCursor();
        String expired = expiredCursor(cursor);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> search(1, expired));

        assertEquals(TeamSearchServiceImpl.SEARCH_CURSOR_EXPIRED, exception.getCode());
        verify(client, times(1)).search(any(Function.class), eq(Map.class));
    }

    @Test
    void candidateBudgetRejectsUnresolvedScopeInsteadOfReturningPartialPage() throws Exception {
        stubActiveMember();
        ReflectionTestUtils.setField(service, "maxCandidates", 2);
        FileNode deniedFirst = file(1L, null);
        FileNode deniedSecond = file(2L, null);
        stubNodes(deniedFirst, deniedSecond);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(false);
        doReturn(response(List.of(source(deniedFirst), source(deniedSecond)), 3L))
                .when(client).search(any(Function.class), eq(Map.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> search(1, null));

        assertEquals(TeamSearchServiceImpl.SEARCH_SCOPE_TOO_LARGE, exception.getCode());
    }

    @Test
    void validatesParameterBoundariesBeforeCallingEs() throws Exception {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> search(0, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> search(51, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(0L, 5L, 10L, null, "合同", 1, null,
                        null, null, null, null, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "   ", 1, null,
                        null, null, null, null, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "x".repeat(201), 1, null,
                        null, null, null, null, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 1, null, 99,
                        null, null, null, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 1, null,
                        null, null, -1L, null, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 1, null,
                        null, null, 20L, 10L, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 1, null,
                        null, null, null, null, 20L, 10L)).getCode());

        List<String> tooManySuffixes = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            tooManySuffixes.add("txt");
        }
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 1, null,
                        null, tooManySuffixes, null, null, null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.search(1L, 5L, 10L, null, "合同", 1, null,
                        null, List.of("x".repeat(33)), null, null, null, null)).getCode());
        verify(client, never()).search(any(Function.class), eq(Map.class));
    }

    @Test
    void missingCursorSecretAllowsPaginationWithinInstance() throws Exception {
        ReflectionTestUtils.setField(service, "cursorSecret", "");
        stubActiveMember();
        FileNode first = file(1L, null);
        FileNode second = file(2L, null);
        stubNodes(first, second);
        when(accessPolicy.canView(any(), any(), any(), any())).thenReturn(true);
        doReturn(response(List.of(source(first), source(second))),
                response(List.of(source(second))))
                .when(client).search(any(Function.class), eq(Map.class));

        TeamSearchResultPage firstPage = search(1, null);
        assertEquals(1L, firstPage.getRecords().get(0).getFileId());
        assertNotNull(firstPage.getNextCursor());
        TeamSearchResultPage secondPage = search(1, firstPage.getNextCursor());
        assertEquals(2L, secondPage.getRecords().get(0).getFileId());
        assertFalse(secondPage.isHasMore());
    }
}
