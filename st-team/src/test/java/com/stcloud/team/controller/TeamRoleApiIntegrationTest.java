package com.stcloud.team.controller;

import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 实际MVC JSON/query边界与团队服务/H2，不以DTO独测冒充持久化契约。 */
class TeamRoleApiIntegrationTest extends AbstractTeamIntegrationTest {
    MockMvc mvc;
    ObjectMapper json = new ObjectMapper();
    Long space, member;
    com.stcloud.core.service.UploadService uploads=mock(com.stcloud.core.service.UploadService.class);
    @BeforeEach void setupApi() {
        setUpUser(100L,1L);
        insertUser(100L,1L,"api-owner"); insertUser(200L,1L,"api-new"); insertUser(300L,1L,"api-member");
        var request=new CreateSpaceRequest(); request.setSpaceName("api-role-space"); request.setStorageQuota(100000L);
        space=teamService.createSpace(request).getData().getId();
        var invite=new InviteMemberRequest(); invite.setUserId(300L);
        member=teamService.inviteMember(space,invite).getData().getId();
        var controller=new TeamController(teamService,fileService,mock(com.stcloud.core.service.NewFileService.class),
                mock(com.stcloud.team.util.TeamActivityHelper.class),mock(com.stcloud.team.util.ActiveTracker.class),
                mock(com.stcloud.core.editor.EditorConfigService.class),mock(com.stcloud.core.text.TextFileService.class));
        var builder=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new com.stcloud.common.config.JacksonConfig().longToStringCustomizer().customize(builder);
        mvc=MockMvcBuilders.standaloneSetup(controller,new TeamUploadController(teamService,uploads))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(builder.build()))
                .setControllerAdvice(new com.stcloud.common.exception.GlobalExceptionHandler()).build();
    }
    JsonNode postJson(String path,String body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse().getContentAsString());
    }
    void success(JsonNode response) { assertEquals(200,response.path("code").asInt(),response.toString()); }
    @ParameterizedTest @ValueSource(strings={"0","1","2","\"0\"","\"1\"","\"2\""})
    void tc0204PresetNumberAndStringCompatibility(String raw) throws Exception {
        String expected=raw.replace("\"","");
        var direct=postJson("/api/team/"+space+"/member","{\"userId\":200,\"role\":"+raw+"}"); success(direct);
        assertTrue(direct.path("data").path("role").isTextual()); assertEquals(expected,direct.path("data").path("role").asText());
        var link=postJson("/api/team/"+space+"/invite","{\"role\":"+raw+"}"); success(link);
        assertTrue(link.path("data").path("role").isTextual()); assertEquals(expected,link.path("data").path("role").asText());
        var update=json.readTree(mvc.perform(put("/api/team/"+space+"/member/"+member).param("role",expected)).andReturn().getResponse().getContentAsString()); success(update);
        assertEquals(Long.valueOf(expected),teamMemberMapper.selectById(member).getRole());
    }
    @Test void tc0204OmittedRoleIsViewer() throws Exception {
        var direct=postJson("/api/team/"+space+"/member","{\"userId\":200}"); success(direct);
        assertEquals("2",direct.path("data").path("role").asText());
        var link=postJson("/api/team/"+space+"/invite","{}"); success(link);
        assertEquals("2",link.path("data").path("role").asText());
    }
    @ParameterizedTest @ValueSource(strings={"null","\"\"","-1","1.5","1e2","true","9223372036854775808","9007199254740993","\"-1\"","\"1.5\"","\"1e2\"","\"true\"","\"9223372036854775808\""})
    void tc0205InvalidJsonAndQueryHaveNoSideEffects(String raw) throws Exception {
        long beforeMembers=teamMemberMapper.selectCount(null),beforeInvites=teamInviteMapper.selectCount(null);
        assertNotEquals(200,postJson("/api/team/"+space+"/member","{\"userId\":200,\"role\":"+raw+"}").path("code").asInt());
        assertNotEquals(200,postJson("/api/team/"+space+"/invite","{\"role\":"+raw+"}").path("code").asInt());
        // Query大ID十进制字符串本身合法；无此角色也必须拒绝，而非舍入命中其他角色。
        var update=json.readTree(mvc.perform(put("/api/team/"+space+"/member/"+member).param("role",raw.replace("\"",""))).andReturn().getResponse().getContentAsString());
        assertNotEquals(200,update.path("code").asInt());
        assertEquals(beforeMembers,teamMemberMapper.selectCount(null)); assertEquals(beforeInvites,teamInviteMapper.selectCount(null));
        assertEquals(2L,teamMemberMapper.selectById(member).getRole());
    }
    @Test void tc0202LargeRoleSurvivesInviteAcceptanceAndJson() throws Exception {
        long id=9007199254740993L;
        var role=new TeamRole(); role.setId(id); role.setSpaceId(space); role.setName("精确角色"); role.setPermissions("{\"view\":true,\"upload\":true}"); role.setStatus(1); teamRoleMapper.insert(role);
        var link=postJson("/api/team/"+space+"/invite","{\"role\":\""+id+"\"}"); success(link);
        assertTrue(link.path("data").path("role").isTextual()); assertEquals(String.valueOf(id),link.path("data").path("role").asText());
        setUpUser(200L,1L);
        success(postJson("/api/team/invite/"+link.path("data").path("inviteCode").asText(),"{}"));
        var joined=teamMemberMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.stcloud.team.entity.TeamMember>().eq(com.stcloud.team.entity.TeamMember::getSpaceId,space).eq(com.stcloud.team.entity.TeamMember::getUserId,200L));
        assertEquals(id,joined.getRole());
        assertDoesNotThrow(()->teamService.requirePermissions(space,0L,"upload"));
        setUpUser(100L,1L);
        var members=json.readTree(mvc.perform(get("/api/team/"+space+"/members")).andReturn().getResponse().getContentAsString()); success(members);
        assertTrue(members.path("data").path("records").findValues("role").stream().anyMatch(value->value.isTextual()&&value.asText().equals(String.valueOf(id))));
    }
    @Test void tc0201DirectInvitePreservesLargeRole() throws Exception {
        long id=9007199254740993L;
        var role=new TeamRole();role.setId(id);role.setSpaceId(space);role.setName("精确角色");
        role.setPermissions("{\"view\":true}");role.setStatus(1);teamRoleMapper.insert(role);
        var response=postJson("/api/team/"+space+"/member","{\"userId\":200,\"role\":\""+id+"\"}");success(response);
        assertTrue(response.path("data").path("role").isTextual());assertEquals(String.valueOf(id),response.path("data").path("role").asText());
        Long created=Long.valueOf(response.path("data").path("id").asText());
        assertEquals(id,teamMemberMapper.selectById(created).getRole());
    }
    @Test void tc0207ViewOnlyUploadDeniedUntilScopedAclGrant() throws Exception {
        long id=9007199254740993L;
        var role=new TeamRole();role.setId(id);role.setSpaceId(space);role.setName("仅查看");
        role.setPermissions("{\"view\":true}");role.setStatus(1);teamRoleMapper.insert(role);
        teamService.updateMemberRole(space,member,id);
        var allowed=insertFileNode(1L,100L,space,"allowed",0,0);
        var denied=insertFileNode(1L,100L,space,"denied",0,0);
        long nodes=fileNodeMapper.selectCount(null),used=teamSpaceMapper.selectById(space).getStorageUsed();
        setUpUser(300L,1L);
        success(json.readTree(mvc.perform(get("/api/team/"+space+"/files")).andReturn().getResponse().getContentAsString()));
        String upload="{\"fileName\":\"test.txt\",\"fileSize\":3,\"fileMd5\":\"900150983cd24fb0d6963f7d28e17f72\",\"totalChunks\":1,\"chunkSize\":3,\"parentId\":\"%s\"}";
        assertNotEquals(200,postJson("/api/team/"+space+"/files/upload/init",upload.formatted(allowed.getId())).path("code").asInt());
        org.mockito.Mockito.verifyNoInteractions(uploads);
        assertEquals(nodes,fileNodeMapper.selectCount(null));assertEquals(used,teamSpaceMapper.selectById(space).getStorageUsed());
        setUpUser(100L,1L);
        var rule=new FolderPermissionRequest.PermissionRule();rule.setSubjectType("role");rule.setSubjectId(String.valueOf(id));rule.setPermissions("{\"view\":true,\"upload\":true}");
        var request=new FolderPermissionRequest();request.setRules(java.util.List.of(rule));teamService.setFolderPermissions(space,allowed.getId(),request);
        setUpUser(300L,1L);
        success(postJson("/api/team/"+space+"/files/upload/init",upload.formatted(allowed.getId())));
        assertNotEquals(200,postJson("/api/team/"+space+"/files/upload/init",upload.formatted(denied.getId())).path("code").asInt());
        org.mockito.Mockito.verify(uploads).initTeamChunkedUpload(org.mockito.ArgumentMatchers.eq(space),org.mockito.ArgumentMatchers.argThat(r->allowed.getId().equals(r.getParentId())));
        org.mockito.Mockito.verifyNoMoreInteractions(uploads);
    }
}
