package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.search.dto.TeamSearchResultPage;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 ES 团队搜索 fixture：使用独立临时索引，验证正文召回及 tenant/space/path scope。
 * 不创建、删除或写入 file_content 业务索引。
 * <p>
 * 需要本机 ES 8.x 在 127.0.0.1:9200；不可用时跳过，不能把环境缺失伪装为通过。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamSearchElasticsearchIntegrationTest {

    private static final String INDEX = "team_search_it_"
            + UUID.randomUUID().toString().replace("-", "");

    private RestClient restClient;
    private ElasticsearchClient client;
    private Map<Long, FileNode> nodes;
    private TeamSearchServiceImpl service;

    @BeforeAll
    void setUp() throws Exception {
        restClient = RestClient.builder(new HttpHost("127.0.0.1", 9200, "http")).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        try {
            client.indices().create(c -> c
                    .index(INDEX)
                    .mappings(m -> m
                            .properties("fileId", p -> p.keyword(k -> k))
                            .properties("fileName", textProperty())
                            .properties("path", p -> p.keyword(k -> k))
                            .properties("tenantId", p -> p.long_(l -> l))
                            .properties("spaceId", p -> p.long_(l -> l))
                            .properties("parentId", p -> p.long_(l -> l))
                            .properties("fileMd5", p -> p.keyword(k -> k))
                            .properties("nodeType", p -> p.integer(i -> i))
                            .properties("fileSize", p -> p.long_(l -> l))
                            .properties("suffix", p -> p.keyword(k -> k))
                            .properties("attachment", p -> p.object(o -> o
                                    .properties("content", textProperty())))
                    ));
        } catch (Exception e) {
            // 保持集成测试可在没有 ES 的开发机上编译；真实环境会执行下面的断言。
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "本机 ES 不可用，跳过真实团队搜索测试: " + e.getMessage());
            return;
        }

        FileNode allowed = file(101L, 1L, 10L, 100L,
                "alpha.txt", "/team/alpha/alpha.txt", "md5-alpha");
        FileNode outsideSpace = file(102L, 1L, 11L, 110L,
                "other-space.txt", "/team/other/other-space.txt", "md5-space");
        FileNode outsideTenant = file(103L, 2L, 10L, 100L,
                "other-tenant.txt", "/team/alpha/other-tenant.txt", "md5-tenant");
        FileNode outsideFolder = file(104L, 1L, 10L, 200L,
                "beta.txt", "/team/beta/beta.txt", "md5-beta");
        nodes = new LinkedHashMap<>();
        nodes.put(allowed.getId(), allowed);
        nodes.put(outsideSpace.getId(), outsideSpace);
        nodes.put(outsideTenant.getId(), outsideTenant);
        nodes.put(outsideFolder.getId(), outsideFolder);

        index(allowed, "alpha team bodyneedle content");
        index(outsideSpace, "other space bodyneedle content");
        index(outsideTenant, "other tenant bodyneedle content");
        index(outsideFolder, "beta folder bodyneedle content");
        client.indices().refresh(r -> r.index(INDEX));

        FileNodeMapper mapper = mock(FileNodeMapper.class);
        when(mapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> nodes.get(((Number) id).longValue()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        });
        when(mapper.selectOne(any())).thenReturn(folder(100L, "/team/alpha"));

        TeamFileAccessPolicy accessPolicy = mock(TeamFileAccessPolicy.class);
        when(accessPolicy.isActiveMember(1L, 5L, 10L)).thenReturn(true);
        when(accessPolicy.canView(1L, 5L, 10L, 101L)).thenReturn(true);
        when(accessPolicy.canView(1L, 5L, 10L, 100L)).thenReturn(true);
        when(accessPolicy.canView(1L, 5L, 10L, 104L)).thenReturn(true);
        service = new TeamSearchServiceImpl(client, mapper, accessPolicy);
        ReflectionTestUtils.setField(service, "teamSearchIndex", INDEX);
        ReflectionTestUtils.setField(service, "cursorSecret", "integration-test-secret");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (client != null) {
            try {
                client.indices().delete(d -> d.index(INDEX));
            } catch (Exception ignored) {
                // Fixture cleanup is best effort and never targets the business index.
            }
        }
        if (restClient != null) {
            restClient.close();
        }
    }

    @Test
    void contentRecallHonorsTenantAndSpaceScope() {
        TeamSearchResultPage page = service.search(1L, 5L, 10L, null,
                "bodyneedle", 20, null, null, null, null, null, null, null);

        assertEquals(List.of(101L, 104L), page.getRecords().stream()
                .map(record -> record.getFileId()).toList());
        assertTrue(page.getRecords().stream()
                .allMatch(record -> record.getHighlight() != null
                        && record.getHighlight().toLowerCase().contains("bodyneedle")));
    }

    @Test
    void folderPathScopeUsesExactOrPrefixPathAndReturnsOnlySubtree() {
        TeamSearchResultPage page = service.search(1L, 5L, 10L, 100L,
                "bodyneedle", 20, null, null, null, null, null, null, null);

        assertEquals(List.of(101L), page.getRecords().stream()
                .map(record -> record.getFileId()).toList());
    }

    private void index(FileNode node, String content) throws Exception {
        client.index(i -> i.index(INDEX).id(String.valueOf(node.getId())).document(Map.ofEntries(
                Map.entry("fileId", String.valueOf(node.getId())),
                Map.entry("fileName", node.getName()),
                Map.entry("path", node.getPath()),
                Map.entry("tenantId", node.getTenantId()),
                Map.entry("spaceId", node.getSpaceId()),
                Map.entry("parentId", node.getParentId()),
                Map.entry("fileMd5", node.getFileMd5()),
                Map.entry("nodeType", node.getNodeType()),
                Map.entry("fileSize", node.getFileSize()),
                Map.entry("suffix", node.getSuffix()),
                Map.entry("attachment", Map.of("content", content))
        )));
    }

    private FileNode file(long id, long tenantId, long spaceId, long parentId,
                          String name, String path, String md5) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(tenantId);
        node.setSpaceId(spaceId);
        node.setParentId(parentId);
        node.setName(name);
        node.setPath(path);
        node.setFileMd5(md5);
        node.setNodeType(1);
        node.setStatus(0);
        node.setHidden(0);
        node.setUploadStatus(2);
        node.setFileSize(100L);
        node.setSuffix("txt");
        return node;
    }

    private FileNode folder(long id, String path) {
        FileNode folder = new FileNode();
        folder.setId(id);
        folder.setTenantId(1L);
        folder.setSpaceId(10L);
        folder.setName("alpha");
        folder.setPath(path);
        folder.setNodeType(0);
        folder.setStatus(0);
        folder.setHidden(0);
        return folder;
    }

    private static java.util.function.Function<Property.Builder,
            co.elastic.clients.util.ObjectBuilder<Property>> textProperty() {
        return p -> p.text(t -> t);
    }
}
