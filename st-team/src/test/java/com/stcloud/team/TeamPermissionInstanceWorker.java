package com.stcloud.team;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.search.init.SearchIndexInitializer;
import com.stcloud.search.service.impl.TeamSearchServiceImpl;
import com.stcloud.team.service.TeamFileAccessPolicyImpl;
import com.stcloud.team.service.TeamService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 进程A权限展示缓存预热后，反复用真实主库授权与搜索复核。 */
public class TeamPermissionInstanceWorker {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        try(var app=new SpringApplicationBuilder(TeamTestApplication.class,TeamFileAccessPolicyImpl.class).profiles("test")
                .run("--spring.datasource.url="+args[0],"--spring.sql.init.mode=never","--spring.main.web-application-type=none")) {
            long space=Long.parseLong(args[1]),nodeId=Long.parseLong(args[2]);
            TenantContext.setTenantId(1L);TenantContext.setTenantMode("SAAS");
            UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(200L).tenantId(1L).username("process-member").build());
            var mapper=app.getBean(FileNodeMapper.class);var node=mapper.selectById(nodeId);var client=mock(ElasticsearchClient.class);
            Map<String,Object> source=Map.of(SearchIndexInitializer.FIELD_FILE_ID,Long.toString(nodeId),SearchIndexInitializer.FIELD_FILE_NAME,node.getName(),
                SearchIndexInitializer.FIELD_PATH,node.getPath(),SearchIndexInitializer.FIELD_FILE_MD5,node.getFileMd5(),SearchIndexInitializer.FIELD_NODE_TYPE,1,
                SearchIndexInitializer.FIELD_TENANT_ID,1L,SearchIndexInitializer.FIELD_SPACE_ID,space);
            Hit<Map> hit=Hit.of(h->h.index("isolated").id(Long.toString(nodeId)).source(source).sort(s->s.stringValue(Long.toString(nodeId))));
            SearchResponse<Map> response=SearchResponse.of(r->r.took(1).timedOut(false).shards(s->s.total(1).successful(1).failed(0)).hits(h->h.hits(List.of(hit))));
            doReturn(response).when(client).search(any(Function.class),eq(Map.class));
            var search=new TeamSearchServiceImpl(client,mapper,app.getBean(TeamFileAccessPolicyImpl.class));
            ReflectionTestUtils.setField(search,"cursorSecret","isolated-team-process-secret");var team=app.getBean(TeamService.class);
            var input=new BufferedReader(new InputStreamReader(System.in));System.out.println("PROBE READY");
            for(String line;(line=input.readLine())!=null;) {
                if(line.equals("STOP"))break;
                boolean allowed=true;int results;
                try {team.requirePermissions(space,nodeId,"view");}catch(BusinessException rejected){allowed=false;}
                try {results=search.search(1L,200L,space,null,"doc",10,null,null,null,null,null,null,null).getRecords().size();}
                catch(BusinessException rejected){if(rejected.getCode()!=4605)throw rejected;results=0;}
                System.out.println("PROBE RESULT "+allowed+" "+results);
            }
        } finally {UserContext.clear();TenantContext.clear();}
    }
}
