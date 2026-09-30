package com.stcloud.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.util.ObjectBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.StorageService;
import com.stcloud.search.service.SearchService;
import com.stcloud.search.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 显式 Spring 上下文与替身边界：验证真实重建编排，不访问开发 DB/ES。 */
@SpringJUnitConfig(SearchServiceImpl.class)
class ReindexIntegrationTest {
    @Autowired private SearchService searchService;
    @MockBean private FileNodeMapper fileNodeMapper;
    @MockBean private ElasticsearchClient client;
    @MockBean private StorageService storageService;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reindexAllUsesOnlyFixtureAndPreservesLargeId() throws Exception {
        FileNode folder = new FileNode();
        folder.setId(9007199254740993L);
        folder.setName("isolated-folder");
        folder.setNodeType(0);
        folder.setStatus(0);
        when(fileNodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(folder));
        List<IndexRequest<?>> requests = new ArrayList<>();
        doAnswer(invocation -> {
            Function<IndexRequest.Builder<Object>, ObjectBuilder<IndexRequest<Object>>> builder = invocation.getArgument(0);
            requests.add(builder.apply(new IndexRequest.Builder<>()).build());
            return null;
        }).when(client).index(any(Function.class));

        assertEquals(1, searchService.reindexAll());
        assertEquals(1, requests.size());
        assertEquals("9007199254740993", requests.get(0).id());
        assertEquals("isolated-folder", ((Map<?, ?>) requests.get(0).document()).get("fileName"));
        verify(fileNodeMapper).selectList(any(Wrapper.class));
        verifyNoInteractions(storageService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFixtureDoesNotWriteIndex() {
        when(fileNodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertEquals(0, searchService.reindexAll());
        verifyNoInteractions(client, storageService);
    }
}
