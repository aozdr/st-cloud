package com.stcloud.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.search.init.SearchIndexInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 独立搜索应用进程：生产Spring配置注入与游标代码，ES及元数据为确定性候选。 */
public class SearchInstanceWorker {
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var client=mock(ElasticsearchClient.class);var mapper=mock(FileNodeMapper.class);
        var policy=mock(TeamFileAccessPolicy.class);var nodes=new LinkedHashMap<Long,FileNode>();
        for(long id=100;id<106;id++) {
            var node=new FileNode();node.setId(id);node.setTenantId(1L);node.setSpaceId(10L);node.setParentId(0L);
            node.setName("file"+id+".txt");node.setPath("/file"+id+".txt");node.setFileMd5("md5-"+id);
            node.setNodeType(1);node.setStatus(0);node.setUploadStatus(2);node.setFileSize(100L);node.setSuffix("txt");nodes.put(id,node);
        }
        when(policy.isActiveMember(anyLong(),anyLong(),anyLong())).thenReturn(true);
        when(policy.canView(anyLong(),anyLong(),anyLong(),anyLong())).thenReturn(true);
        when(mapper.selectBatchIds(anyCollection())).thenAnswer(inv->((Collection<?>)inv.getArgument(0)).stream().map(id->nodes.get(((Number)id).longValue())).filter(Objects::nonNull).toList());
        when(client.search(any(Function.class),eq(Map.class))).thenAnswer(inv->{
            Function<SearchRequest.Builder,co.elastic.clients.util.ObjectBuilder<SearchRequest>> build=inv.getArgument(0);
            var request=build.apply(new SearchRequest.Builder()).build();
            long after=request.searchAfter().isEmpty()?0:Long.parseLong(request.searchAfter().get(0).stringValue());
            var hits=new ArrayList<Hit<Map>>();
            for(var node:nodes.values())if(node.getId()>after) {
                Map<String,Object> source=new HashMap<>();source.put(SearchIndexInitializer.FIELD_FILE_ID,node.getId().toString());
                source.put(SearchIndexInitializer.FIELD_FILE_NAME,node.getName());source.put(SearchIndexInitializer.FIELD_PATH,node.getPath());
                source.put(SearchIndexInitializer.FIELD_FILE_MD5,node.getFileMd5());source.put(SearchIndexInitializer.FIELD_NODE_TYPE,1);
                source.put(SearchIndexInitializer.FIELD_FILE_SIZE,100L);source.put(SearchIndexInitializer.FIELD_SUFFIX,"txt");
                source.put(SearchIndexInitializer.FIELD_SPACE_ID,10L);source.put(SearchIndexInitializer.FIELD_TENANT_ID,1L);
                hits.add(Hit.of(h->h.id(node.getId().toString()).index(SearchIndexInitializer.INDEX_NAME).source((Map)source).sort(FieldValue.of(node.getId().toString()))));
            }
            return SearchResponse.<Map>of(r->r.took(1).timedOut(false).shards(s->s.total(1).successful(1).failed(0)).hits(h->h.hits(hits)));
        });
        try(var app=new AnnotationConfigApplicationContext()) {
            app.registerBean(ElasticsearchClient.class,()->client);app.registerBean(FileNodeMapper.class,()->mapper);
            app.registerBean(TeamFileAccessPolicy.class,()->policy);app.registerBean(TeamSearchServiceImpl.class);app.refresh();
            System.out.println("PROBE READY");var input=new BufferedReader(new InputStreamReader(System.in));
            for(String line;(line=input.readLine())!=null;) {
                if(line.equals("STOP"))break;var q=json.readTree(line);clearInvocations(client);
                Map<String,Object> result=new LinkedHashMap<>();
                try {
                    var page=app.getBean(TeamSearchServiceImpl.class).search(q.path("tenant").asLong(1),q.path("user").asLong(5),q.path("space").asLong(10),
                        q.has("folder")?q.path("folder").asLong():null,q.path("keyword").asText("file"),q.path("size").asInt(2),q.path("cursor").asText(null),
                        q.has("nodeType")?q.path("nodeType").asInt():null,q.has("suffixes")?List.of(q.path("suffixes").asText()):null,
                        q.has("sizeMin")?q.path("sizeMin").asLong():null,q.has("sizeMax")?q.path("sizeMax").asLong():null,
                        q.has("dateFrom")?q.path("dateFrom").asLong():null,q.has("dateTo")?q.path("dateTo").asLong():null);
                    result.put("code",200);result.put("page",page);
                } catch(BusinessException rejected) {result.put("code",rejected.getCode());result.put("message",rejected.getMessage());}
                result.put("esCalls",mockingDetails(client).getInvocations().size());System.out.println("PROBE RESULT "+json.writeValueAsString(result));
            }
        }
    }
}
