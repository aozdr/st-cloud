package com.stcloud.team.service;

import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamRole;
import com.stcloud.common.exception.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.stcloud.search.service.impl.TeamSearchServiceImpl;
import com.stcloud.search.init.SearchIndexInitializer;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真正团队授权/搜索服务与H2；ES仅返回受控候选，不替代最终权限判断。 */
@Import(TeamFileAccessPolicyImpl.class)
class TeamAccessRevocationIntegrationTest extends AbstractTeamIntegrationTest {
    @Autowired TeamFileAccessPolicyImpl policy;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @ParameterizedTest @ValueSource(strings={"expired","external-off","removed","internal-disabled-role","external-disabled-role"})
    @SuppressWarnings("unchecked")
    void tc0215And21EveryEntryRejectsInvalidMembership(String scenario) throws Exception {
        setUpUser(100L,1L);insertUser(100L,1L,"revoke-owner");insertUser(200L,1L,"revoke-member");
        var create=new CreateSpaceRequest();create.setSpaceName("revoke-space");create.setStorageQuota(100000L);
        Long space=teamService.createSpace(create).getData().getId();
        var role=new TeamRole();role.setId(9007199254740993L);role.setSpaceId(space);role.setName("权限角色");
        role.setPermissions("{\"view\":true,\"upload\":true}");role.setStatus(1);teamRoleMapper.insert(role);
        var invite=new InviteMemberRequest();invite.setUserId(200L);invite.setRole(role.getId());
        Long member=teamService.inviteMember(space,invite).getData().getId();
        teamService.setExternalConfig(space,true);
        if(!scenario.startsWith("internal")) {
            var external=new ExternalMemberRequest();external.setMemberType(1);teamService.setExternalMember(space,member,external);
        }
        var node=insertFileNode(1L,100L,space,"doc.txt",1,0);node.setFileMd5("test-md5");fileNodeMapper.updateById(node);
        var client=mock(ElasticsearchClient.class);
        Map<String,Object> source=Map.of(SearchIndexInitializer.FIELD_FILE_ID,node.getId().toString(),
                SearchIndexInitializer.FIELD_FILE_NAME,node.getName(),SearchIndexInitializer.FIELD_PATH,node.getPath(),
                SearchIndexInitializer.FIELD_FILE_MD5,"test-md5",SearchIndexInitializer.FIELD_NODE_TYPE,1,
                SearchIndexInitializer.FIELD_TENANT_ID,1L,SearchIndexInitializer.FIELD_SPACE_ID,space);
        Hit<Map> hit=Hit.of(h->h.index("isolated").id(node.getId().toString()).source(source).sort(s->s.stringValue(node.getId().toString())));
        SearchResponse<Map> response=SearchResponse.of(r->r.took(1).timedOut(false).shards(s->s.total(1).successful(1).failed(0)).hits(h->h.hits(List.of(hit))));
        doReturn(response).when(client).search(any(Function.class),eq(Map.class));
        var search=new TeamSearchServiceImpl(client,fileNodeMapper,policy);ReflectionTestUtils.setField(search,"cursorSecret","isolated-team-test-secret");
        setUpUser(200L,1L);
        teamService.requirePermissions(space,node.getId(),"view","upload");assertTrue(policy.canView(1L,200L,space,node.getId()));
        assertEquals(1,search.search(1L,200L,space,null,"doc",10,null,null,null,null,null,null,null).getRecords().size());
        setUpUser(100L,1L);
        switch(scenario) {
            case "expired" -> {
                var changed=teamMemberMapper.selectById(member);changed.setExpireAt(java.time.LocalDateTime.now().minusSeconds(1));teamMemberMapper.updateById(changed);
            }
            case "external-off" -> teamService.setExternalConfig(space,false);
            case "removed" -> teamService.removeMember(space,member);
            default -> {role.setStatus(0);teamRoleMapper.updateById(role);}
        }
        long nodes=fileNodeMapper.selectCount(null);long used=teamSpaceMapper.selectById(space).getStorageUsed();
        setUpUser(200L,1L);
        assertAll(
                ()->assertThrows(BusinessException.class,()->teamService.checkPermission(space,2)),
                ()->assertThrows(BusinessException.class,()->teamService.requirePermissions(space,node.getId(),"view")),
                ()->assertThrows(BusinessException.class,()->teamService.requirePermissions(space,node.getId(),"upload")),
                ()->assertThrows(BusinessException.class,()->teamService.getFolderPermissions(space,0L)),
                ()->assertFalse(policy.canView(1L,200L,space,node.getId())),
                ()->{try {assertTrue(search.search(1L,200L,space,null,"doc",10,null,null,null,null,null,null,null).getRecords().isEmpty());}
                     catch(BusinessException rejected) {assertEquals(4605,rejected.getCode());}}
        );
        assertEquals(nodes,fileNodeMapper.selectCount(null));assertEquals(used,teamSpaceMapper.selectById(space).getStorageUsed());
    }
}
