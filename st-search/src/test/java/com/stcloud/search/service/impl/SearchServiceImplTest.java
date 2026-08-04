package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ApiTypeHelper;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.StorageService;
import com.stcloud.search.dto.SearchResultVO;
import com.stcloud.search.init.SearchIndexInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SearchServiceImpl 单元测试
 * <p>
 * 通过 Mock ElasticsearchClient 和 StorageService，验证：
 * 1. isIndexable - 文件类型判断逻辑
 * 2. indexFile - 索引文件流程（含跳过、异常处理）
 * 3. searchContent - 搜索结果转换（含 keyword 字段返回 String 的 bug 修复验证）
 * 4. removeIndex - 删除索引
 * 5. updateMeta - 更新元数据
 * 6. reindexAll - 全量重建索引
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("搜索服务单元测试")
class SearchServiceImplTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private StorageService storageService;

    @Mock
    private FileNodeMapper fileNodeMapper;

    @InjectMocks
    private SearchServiceImpl searchService;

    @BeforeAll
    static void disableRequiredPropertiesCheck() {
        // 禁用 ES 客户端的必填属性校验，便于构建测试用 Response 对象
        ApiTypeHelper.DANGEROUS_disableRequiredPropertiesCheck(true);
    }

    // ==================== 辅助方法 ====================

    private FileNode buildFileNode(Long id, String name, String suffix, Long fileSize) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setName(name);
        node.setSuffix(suffix);
        node.setFileSize(fileSize);
        node.setPath("/" + name);
        node.setStoragePath("tenant/1/" + id + "_" + name);
        node.setContentType("text/plain");
        node.setOwnerId(1L);
        node.setNodeType(NodeType.FILE.getCode());
        node.setStatus(NodeStatus.NORMAL.getCode());
        return node;
    }

    private FileNode buildFolder(Long id, String name) {
        FileNode folder = new FileNode();
        folder.setId(id);
        folder.setName(name);
        folder.setPath("/" + name);
        folder.setNodeType(NodeType.FOLDER.getCode());
        folder.setStatus(NodeStatus.NORMAL.getCode());
        folder.setOwnerId(1L);
        return folder;
    }

    /**
     * 构建模拟 ES 搜索响应
     */
    @SuppressWarnings("unchecked")
    private SearchResponse<Map> buildSearchResponse(List<Map<String, Object>> sourceMaps,
                                                     List<Map<String, List<String>>> highlights) {
        List<Hit<Map>> hits = new ArrayList<>();
        for (int i = 0; i < sourceMaps.size(); i++) {
            Map<String, Object> source = sourceMaps.get(i);
            Map<String, List<String>> highlight = (highlights != null && i < highlights.size()) ? highlights.get(i) : null;
            final int idx = i;
            Hit<Map> hit = Hit.of(h -> {
                h.id(String.valueOf(idx + 1));
                h.index(SearchIndexInitializer.INDEX_NAME);
                h.score(1.0);
                h.source((Map<String, Object>) source);
                if (highlight != null) {
                    h.highlight(highlight);
                }
                return h;
            });
            hits.add(hit);
        }

        return SearchResponse.of(srb -> srb
                .took(1L)
                .timedOut(false)
                .shards(sh -> sh.total(1).successful(1).failed(0))
                .hits(hb -> hb
                        .total(tb -> tb.value((long) sourceMaps.size()).relation(TotalHitsRelation.Eq))
                        .hits(hits)
                )
        );
    }

    private IndexResponse mockIndexResponse(String id) {
        return IndexResponse.of(r -> r.index("file_content").id(id).version(1L).primaryTerm(1L).seqNo(0L)
                .shards(sh -> sh.total(1).successful(1).failed(0)).result(Result.Created));
    }

    // ==================== isIndexable 测试 ====================

    @Nested
    @DisplayName("isIndexable - 文件类型判断")
    class IsIndexableTest {

        @Test
        @DisplayName("可索引文件类型：txt/pdf/doc/docx/xls/xlsx/ppt/pptx")
        void testIndexableFileTypes() throws Exception {
            String[] indexable = {"txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"};
            for (String suffix : indexable) {
                FileNode node = buildFileNode(1L, "test." + suffix, suffix, 1024L);
                assertTrue(searchService.isIndexable(node),
                        "后缀 " + suffix + " 应该可索引");
            }
        }

        @Test
        @DisplayName("不可索引文件类型：jpg/png/mp4/zip 等")
        void testNonIndexableFileTypes() throws Exception {
            String[] nonIndexable = {"jpg", "png", "gif", "mp4", "mp3", "zip", "rar", "exe"};
            for (String suffix : nonIndexable) {
                FileNode node = buildFileNode(1L, "test." + suffix, suffix, 1024L);
                assertFalse(searchService.isIndexable(node),
                        "后缀 " + suffix + " 不应该可索引");
            }
        }

        @Test
        @DisplayName("后缀大写也应可索引")
        void testIndexableUpperCaseSuffix() throws Exception {
            FileNode node = buildFileNode(1L, "test.PDF", "PDF", 1024L);
            assertTrue(searchService.isIndexable(node), "大写后缀 PDF 应该可索引");
        }

        @Test
        @DisplayName("null FileNode 返回 false")
        void testNullFileNode() throws Exception {
            assertFalse(searchService.isIndexable(null));
        }

        @Test
        @DisplayName("null 后缀返回 false")
        void testNullSuffix() throws Exception {
            FileNode node = buildFileNode(1L, "nofile", null, 1024L);
            assertFalse(searchService.isIndexable(node));
        }
    }

    // ==================== indexFile 测试 ====================

    @Nested
    @DisplayName("indexFile - 索引文件")
    class IndexFileTest {

        @Test
        @DisplayName("成功索引 txt 文件 - 验证 client.index 被调用")
        void testIndexFileSuccess() throws Exception {
            FileNode node = buildFileNode(100L, "report.txt", "txt", 1024L);
            byte[] fileContent = "Hello World, this is test content.".getBytes();

            when(storageService.downloadObject(node.getStoragePath()))
                    .thenReturn(new ByteArrayInputStream(fileContent));
            when(client.index(any(Function.class))).thenReturn(mockIndexResponse("100"));

            searchService.indexFile(node);

            verify(storageService).downloadObject(node.getStoragePath());
            verify(client).index(any(Function.class));
        }

        @Test
        @DisplayName("不可索引文件类型 - 索引元数据但不下载内容")
        void testNonIndexableFileIndexesMetadata() throws Exception {
            FileNode node = buildFileNode(1L, "photo.jpg", "jpg", 1024L);
            when(client.index(any(Function.class))).thenReturn(mockIndexResponse("1"));

            searchService.indexFile(node);

            verify(storageService, never()).downloadObject(any());
            verify(client).index(any(Function.class));
        }

        @Test
        @DisplayName("超大文件（>20MB）- 索引元数据但不下载内容")
        void testLargeFileIndexesMetadata() throws Exception {
            FileNode node = buildFileNode(1L, "big.pdf", "pdf", 25 * 1024 * 1024L);
            when(client.index(any(Function.class))).thenReturn(mockIndexResponse("1"));

            searchService.indexFile(node);

            verify(storageService, never()).downloadObject(any());
            verify(client).index(any(Function.class));
        }

        @Test
        @DisplayName("文件夹节点 - 索引元数据但不下载内容")
        void testFolderIndexesMetadata() throws Exception {
            FileNode folder = buildFolder(200L, "myFolder");
            when(client.index(any(Function.class))).thenReturn(mockIndexResponse("200"));

            searchService.indexFile(folder);

            verify(storageService, never()).downloadObject(any());
            verify(client).index(any(Function.class));
        }

        @Test
        @DisplayName("null fileNode 直接返回 - 不调用 client.index")
        void testNullNode() throws Exception {
            searchService.indexFile(null);
            verify(client, never()).index(any(Function.class));
        }

        @Test
        @DisplayName("恰好 20MB 的文件不被跳过")
        void testExactMaxSize() throws Exception {
            FileNode node = buildFileNode(1L, "edge.pdf", "pdf", 20 * 1024 * 1024L);
            byte[] content = new byte[100];

            when(storageService.downloadObject(node.getStoragePath()))
                    .thenReturn(new ByteArrayInputStream(content));
            when(client.index(any(Function.class))).thenReturn(mockIndexResponse("1"));

            searchService.indexFile(node);

            verify(client).index(any(Function.class));
        }

        @Test
        @DisplayName("ES 索引异常时不抛出 - 静默处理")
        void testIndexFileException() throws Exception {
            FileNode node = buildFileNode(1L, "test.txt", "txt", 100L);
            byte[] content = "test".getBytes();

            when(storageService.downloadObject(node.getStoragePath()))
                    .thenReturn(new ByteArrayInputStream(content));
            when(client.index(any(Function.class))).thenThrow(new RuntimeException("ES connection refused"));

            assertDoesNotThrow(() -> searchService.indexFile(node));
        }

        @Test
        @DisplayName("S3 下载异常时不抛出 - 静默处理")
        void testDownloadException() throws Exception {
            FileNode node = buildFileNode(1L, "test.txt", "txt", 100L);

            when(storageService.downloadObject(node.getStoragePath()))
                    .thenThrow(new RuntimeException("S3 error"));

            assertDoesNotThrow(() -> searchService.indexFile(node));
            verify(client, never()).index(any(Function.class));
        }
    }

    // ==================== searchContent 测试 ====================

    @Nested
    @DisplayName("searchContent - 内容搜索")
    class SearchContentTest {

        @Test
        @DisplayName("搜索返回结果 - fileId 为 String（keyword 字段）时正确转换")
        void testSearchWithResults_KeywordFileId() throws Exception {
            Map<String, Object> source = new HashMap<>();
            source.put(SearchIndexInitializer.FIELD_FILE_ID, "100");
            source.put(SearchIndexInitializer.FIELD_FILE_NAME, "report.txt");
            source.put(SearchIndexInitializer.FIELD_PATH, "/docs/report.txt");
            source.put(SearchIndexInitializer.FIELD_FILE_SIZE, 2048);
            source.put(SearchIndexInitializer.FIELD_SUFFIX, "txt");
            source.put(SearchIndexInitializer.FIELD_CONTENT_TYPE, "text/plain");

            Map<String, List<String>> highlight = new HashMap<>();
            String hlField = SearchIndexInitializer.FIELD_ATTACHMENT + "." + SearchIndexInitializer.FIELD_CONTENT;
            highlight.put(hlField, List.of("<em>Hello</em> World"));

            SearchResponse<Map> mockResponse = buildSearchResponse(
                    List.of(source), List.of(highlight));

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("Hello", 1L, 1, 20);

            assertEquals(1, results.size());
            SearchResultVO vo = results.get(0);
            assertEquals(100L, vo.getFileId(),
                    "fileId 应从 String '100' 正确转为 Long 100");
            assertEquals("report.txt", vo.getFileName());
            assertEquals("/docs/report.txt", vo.getPath());
            assertEquals(2048L, vo.getFileSize());
            assertEquals("txt", vo.getSuffix());
            assertEquals("text/plain", vo.getContentType());
            assertEquals("<em>Hello</em> World", vo.getHighlight());
        }

        @Test
        @DisplayName("搜索返回结果 - fileId 为 Number（兼容旧数据）")
        void testSearchWithResults_NumberFileId() throws Exception {
            Map<String, Object> source = new HashMap<>();
            source.put(SearchIndexInitializer.FIELD_FILE_ID, 200L);
            source.put(SearchIndexInitializer.FIELD_FILE_NAME, "doc.pdf");
            source.put(SearchIndexInitializer.FIELD_PATH, "/doc.pdf");
            source.put(SearchIndexInitializer.FIELD_FILE_SIZE, "4096");
            source.put(SearchIndexInitializer.FIELD_SUFFIX, "pdf");
            source.put(SearchIndexInitializer.FIELD_CONTENT_TYPE, "application/pdf");

            SearchResponse<Map> mockResponse = buildSearchResponse(
                    List.of(source), Collections.singletonList(null));

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("test", null, 1, 10);

            assertEquals(1, results.size());
            SearchResultVO vo = results.get(0);
            assertEquals(200L, vo.getFileId());
            assertEquals(4096L, vo.getFileSize(),
                    "fileSize 应从 String '4096' 正确转为 Long");
            assertNull(vo.getHighlight(), "无高亮时 highlight 应为 null");
        }

        @Test
        @DisplayName("搜索返回多条结果")
        void testSearchMultipleResults() throws Exception {
            List<Map<String, Object>> sources = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                Map<String, Object> src = new HashMap<>();
                src.put(SearchIndexInitializer.FIELD_FILE_ID, String.valueOf(i));
                src.put(SearchIndexInitializer.FIELD_FILE_NAME, "file" + i + ".txt");
                src.put(SearchIndexInitializer.FIELD_PATH, "/file" + i + ".txt");
                sources.add(src);
            }

            SearchResponse<Map> mockResponse = buildSearchResponse(sources, null);

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("keyword", 1L, 1, 10);

            assertEquals(3, results.size());
            assertEquals(1L, results.get(0).getFileId());
            assertEquals(2L, results.get(1).getFileId());
            assertEquals(3L, results.get(2).getFileId());
        }

        @Test
        @DisplayName("搜索无结果 - 返回空列表")
        void testSearchNoResults() throws Exception {
            SearchResponse<Map> mockResponse = buildSearchResponse(
                    Collections.emptyList(), Collections.emptyList());

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("nothing", 1L, 1, 10);

            assertNotNull(results);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("ES 异常时返回空列表 - 不抛出")
        void testSearchException() throws Exception {
            when(client.search(any(Function.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("ES connection refused"));

            List<SearchResultVO> results = searchService.searchContent("test", 1L, 1, 10);

            assertNotNull(results);
            assertTrue(results.isEmpty(), "ES 异常时应返回空列表");
        }

        @Test
        @DisplayName("source 为 null 的 hit 被跳过")
        void testSearchNullSourceSkipped() throws Exception {
            Hit<Map> nullSourceHit = Hit.of(h -> h.id("1").index("file_content").source(null));
            Hit<Map> validHit = Hit.of(h -> {
                Map<String, Object> src = new HashMap<>();
                src.put(SearchIndexInitializer.FIELD_FILE_ID, "2");
                src.put(SearchIndexInitializer.FIELD_FILE_NAME, "valid.txt");
                return h.id("2").index("file_content").source(src);
            });

            @SuppressWarnings("unchecked")
            SearchResponse<Map> mockResponse = SearchResponse.of(srb -> srb
                    .took(1L)
                    .timedOut(false)
                    .shards(sh -> sh.total(1).successful(1).failed(0))
                    .hits(hb -> hb
                            .total(tb -> tb.value(2L).relation(TotalHitsRelation.Eq))
                            .hits(List.of(nullSourceHit, validHit))
                    )
            );

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("test", 1L, 1, 10);

            assertEquals(1, results.size(), "source 为 null 的 hit 应被跳过");
            assertEquals(2L, results.get(0).getFileId());
        }

        @Test
        @DisplayName("page=0 时 from 不为负数")
        void testPaginationFromNotNegative() throws Exception {
            SearchResponse<Map> mockResponse = buildSearchResponse(
                    Collections.emptyList(), Collections.emptyList());

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            assertDoesNotThrow(() -> searchService.searchContent("test", 1L, 0, 10));
        }
    }

    // ==================== removeIndex 测试 ====================

    @Nested
    @DisplayName("removeIndex - 删除索引")
    class RemoveIndexTest {

        @Test
        @DisplayName("成功删除索引 - 验证 client.delete 被调用")
        void testRemoveIndexSuccess() throws Exception {
            DeleteResponse mockResp = DeleteResponse.of(r -> r.index("file_content").id("1").version(1L).primaryTerm(1L).seqNo(0L).shards(sh -> sh.total(1).successful(1).failed(0)).result(Result.Deleted));
            when(client.delete(any(Function.class))).thenReturn(mockResp);

            searchService.removeIndex(100L);

            verify(client).delete(any(Function.class));
        }

        @Test
        @DisplayName("null fileId 直接返回 - 不调用 client.delete")
        void testRemoveIndexNullId() throws Exception {
            searchService.removeIndex(null);

            verify(client, never()).delete(any(Function.class));
        }

        @Test
        @DisplayName("ES 异常时不抛出 - 静默处理")
        void testRemoveIndexException() throws Exception {
            when(client.delete(any(Function.class))).thenThrow(new RuntimeException("ES error"));

            assertDoesNotThrow(() -> searchService.removeIndex(1L));
        }
    }

    // ==================== updateMeta 测试 ====================

    @Nested
    @DisplayName("updateMeta - 更新元数据")
    class UpdateMetaTest {

        @Test
        @DisplayName("成功更新元数据 - 验证 client.update 被调用")
        void testUpdateMetaSuccess() throws Exception {
            FileNode node = buildFileNode(100L, "renamed.txt", "txt", 1024L);
            node.setPath("/new/path/renamed.txt");

            UpdateResponse<Map> mockResp = UpdateResponse.of(r -> r.index("file_content").id("100").version(1L).primaryTerm(1L).seqNo(0L).shards(sh -> sh.total(1).successful(1).failed(0)).result(Result.Updated));
            doReturn(mockResp).when(client).update(any(Function.class), eq(Map.class));

            searchService.updateMeta(node);

            verify(client).update(any(Function.class), eq(Map.class));
        }

        @Test
        @DisplayName("null fileNode 直接返回")
        void testUpdateMetaNullNode() throws Exception {
            searchService.updateMeta(null);

            verify(client, never()).update(any(Function.class), any());
        }

        @Test
        @DisplayName("null id 直接返回")
        void testUpdateMetaNullId() throws Exception {
            FileNode node = new FileNode();
            node.setId(null);

            searchService.updateMeta(node);

            verify(client, never()).update(any(Function.class), any());
        }

        @Test
        @DisplayName("ES 异常时不抛出 - 静默处理")
        void testUpdateMetaException() throws Exception {
            FileNode node = buildFileNode(1L, "test.txt", "txt", 100L);

            when(client.update(any(Function.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("ES error"));

            assertDoesNotThrow(() -> searchService.updateMeta(node));
        }
    }

    // ==================== reindexAll 测试 ====================

    @Nested
    @DisplayName("reindexAll - 全量重建索引")
    class ReindexAllTest {

        @Test
        @DisplayName("索引所有 NORMAL 状态节点 - 文件和文件夹")
        void testReindexAll() throws Exception {
            FileNode txtFile = buildFileNode(1L, "a.txt", "txt", 100L);
            FileNode jpgFile = buildFileNode(2L, "b.jpg", "jpg", 100L);
            FileNode folder = buildFolder(3L, "folder");

            when(fileNodeMapper.selectList(any())).thenReturn(List.of(txtFile, jpgFile, folder));
            when(storageService.downloadObject(any())).thenReturn(new ByteArrayInputStream("test".getBytes()));
            when(client.index(any(Function.class))).thenReturn(mockIndexResponse("1"));

            int count = searchService.reindexAll();

            assertEquals(3, count);
            verify(client, times(3)).index(any(Function.class));
            // txt 走内容索引下载一次；jpg 和 folder 走元数据不下载
            verify(storageService, times(1)).downloadObject(any());
        }

        @Test
        @DisplayName("数据库无数据时返回 0")
        void testReindexAllEmpty() throws Exception {
            when(fileNodeMapper.selectList(any())).thenReturn(Collections.emptyList());

            int count = searchService.reindexAll();

            assertEquals(0, count);
            verify(client, never()).index(any(Function.class));
        }
    }

    // ==================== toVO 转换边界测试 ====================

    @Nested
    @DisplayName("toVO 转换边界场景")
    class ToVOEdgeCaseTest {

        @Test
        @DisplayName("fileId 为非数字 String 时返回 null - 不抛出异常")
        void testNonNumericFileId() throws Exception {
            Map<String, Object> source = new HashMap<>();
            source.put(SearchIndexInitializer.FIELD_FILE_ID, "abc");
            source.put(SearchIndexInitializer.FIELD_FILE_NAME, "test.txt");

            SearchResponse<Map> mockResponse = buildSearchResponse(
                    List.of(source), Collections.singletonList(null));

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("test", 1L, 1, 10);

            assertEquals(1, results.size());
            assertNull(results.get(0).getFileId(), "非数字 fileId 应返回 null");
            assertEquals("test.txt", results.get(0).getFileName());
        }

        @Test
        @DisplayName("fileId 为 null 时返回 null")
        void testNullFileId() throws Exception {
            Map<String, Object> source = new HashMap<>();
            source.put(SearchIndexInitializer.FIELD_FILE_ID, null);
            source.put(SearchIndexInitializer.FIELD_FILE_NAME, "test.txt");

            SearchResponse<Map> mockResponse = buildSearchResponse(
                    List.of(source), Collections.singletonList(null));

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("test", 1L, 1, 10);

            assertEquals(1, results.size());
            assertNull(results.get(0).getFileId());
        }

        @Test
        @DisplayName("多片段高亮用 ' ... ' 连接")
        void testMultipleHighlightFragments() throws Exception {
            Map<String, Object> source = new HashMap<>();
            source.put(SearchIndexInitializer.FIELD_FILE_ID, "1");
            source.put(SearchIndexInitializer.FIELD_FILE_NAME, "test.txt");

            Map<String, List<String>> highlight = new HashMap<>();
            String hlField = SearchIndexInitializer.FIELD_ATTACHMENT + "." + SearchIndexInitializer.FIELD_CONTENT;
            highlight.put(hlField, List.of("<em>foo</em>", "<em>bar</em>", "<em>baz</em>"));

            SearchResponse<Map> mockResponse = buildSearchResponse(
                    List.of(source), List.of(highlight));

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("test", 1L, 1, 10);

            assertEquals("<em>foo</em> ... <em>bar</em> ... <em>baz</em>",
                    results.get(0).getHighlight());
        }

        @Test
        @DisplayName("文件名高亮 - 当内容无高亮时返回文件名高亮")
        void testFileNameHighlight() throws Exception {
            Map<String, Object> source = new HashMap<>();
            source.put(SearchIndexInitializer.FIELD_FILE_ID, "1");
            source.put(SearchIndexInitializer.FIELD_FILE_NAME, "test.txt");

            Map<String, List<String>> highlight = new HashMap<>();
            highlight.put(SearchIndexInitializer.FIELD_FILE_NAME, List.of("<em>test</em>.txt"));

            SearchResponse<Map> mockResponse = buildSearchResponse(
                    List.of(source), List.of(highlight));

            doReturn(mockResponse).when(client).search(any(Function.class), eq(Map.class));

            List<SearchResultVO> results = searchService.searchContent("test", 1L, 1, 10);

            assertEquals(1, results.size());
            assertEquals("<em>test</em>.txt", results.get(0).getHighlight());
        }
    }
}
