package com.stcloud.team;

import com.stcloud.team.controller.TeamController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.dto.MoveRequest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.service.*;
import com.stcloud.core.service.impl.FileObjectServiceImpl;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.TeamTestApplication;
import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamRole;
import com.stcloud.team.util.TeamActivityHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 复制入口、真实权限与数据库事务串联；存储隔离，引用与Outbox使用生产实现。 */
@SpringBootTest(classes=TeamCopyDatabaseIntegrationTest.CopyConfig.class)
@org.springframework.test.context.jdbc.Sql("/copy-object-fixture.sql")
class TeamCopyDatabaseIntegrationTest extends AbstractTeamIntegrationTest {
    @Configuration static class CopyConfig extends TeamTestApplication {
        @Autowired EventLogMapper events;
        @Autowired ApplicationEventPublisher publisher;
        @Override @Bean public FileObjectService fileObjectService() { return new FileObjectServiceImpl(); }
        @Override @Bean public ReliableEventPublisher reliableEventPublisher() {
            return new ReliableEventPublisher(events,publisher,new ObjectMapper().findAndRegisterModules());
        }
        @Bean StorageService storageService() { return mock(StorageService.class); }
    }
    @Autowired FileObjectMapper objects;
    @Autowired EventLogMapper events;
    @Autowired TeamActivityHelper activity;
    @Autowired StorageService storage;
    Long space,member;
    FileNode f1,f2,target;
    TeamController controller;
    @BeforeEach void setupCopy() {
        setUpUser(100L,1L);insertUser(100L,1L,"copy-owner");insertUser(200L,1L,"copy-member");
        var request=new CreateSpaceRequest();request.setSpaceName("copy-db");request.setStorageQuota(1000000L);
        space=teamService.createSpace(request).getData().getId();
        var invite=new InviteMemberRequest();invite.setUserId(200L);member=teamService.inviteMember(space,invite).getData().getId();
        f1=file("one.txt",0L);f2=file("two.txt",0L);target=insertFileNode(1L,100L,space,"target",0,0);
        controller=new TeamController(teamService,fileService,mock(NewFileService.class),activity,
                mock(com.stcloud.team.util.ActiveTracker.class),mock(com.stcloud.core.editor.EditorConfigService.class),
                mock(com.stcloud.core.text.TextFileService.class));
        clearInvocations(activity,storage);
    }
    FileNode file(String name,Long parent) {
        var node=insertFileNode(1L,100L,space,name,1,0);
        String hash=String.format("%032x",node.getId());
        var object=new FileObject();object.setMd5(hash);object.setSize(1024L);object.setStoragePath("fixture/"+hash);
        object.setRefCount(1);object.setStatus(0);objects.insert(object);
        node.setParentId(parent);node.setFileMd5(hash);node.setObjectId(object.getId());node.setStoragePath(object.getStoragePath());
        if(parent!=0L)node.setPath(fileNodeMapper.selectById(parent).getPath()+"/"+name);
        fileNodeMapper.updateById(node);return node;
    }
    void acl(Long node,String permissions) {
        setUpUser(100L,1L);var rule=new FolderPermissionRequest.PermissionRule();rule.setSubjectType("member");
        rule.setSubjectId("200");rule.setPermissions(permissions);var request=new FolderPermissionRequest();request.setRules(List.of(rule));
        teamService.setFolderPermissions(space,node,request);setUpUser(200L,1L);
    }
    void role(long id,String permissions,int status) {
        setUpUser(100L,1L);var role=new TeamRole();role.setId(id);role.setSpaceId(space);role.setName("copy-role");
        role.setPermissions(permissions);role.setStatus(1);teamRoleMapper.insert(role);teamService.updateMemberRole(space,member,id);
        role.setStatus(status);teamRoleMapper.updateById(role);setUpUser(200L,1L);
    }
    void copy(List<Long> ids,Long parent) { var request=new MoveRequest();request.setNodeIds(ids);request.setTargetParentId(parent);controller.copyFiles(space,request); }
    List<Object> snapshot() {
        return List.of(fileNodeMapper.selectCount(null),objects.selectList(null).stream().map(o->o.getId()+":"+o.getRefCount()).sorted().toList(),
                teamSpaceMapper.selectById(space).getStorageUsed(),events.selectCount(null),mockingDetails(activity).getInvocations().size());
    }
    void rejected(List<Long> ids,Long parent) {
        var before=snapshot();assertThrows(BusinessException.class,()->copy(ids,parent));assertEquals(before,snapshot());verifyNoInteractions(storage);
    }
    @Test void tc0301NoTargetUploadHasNoDatabaseSideEffects() {setUpUser(200L,1L);rejected(List.of(f1.getId()),target.getId());}
    @Test void tc0308PermissionRevokedAfterOpeningDialogAndRepeatedSubmission() {
        acl(target.getId(),"{\"upload\":true}");
        assertDoesNotThrow(()->teamService.requirePermissions(space,target.getId(),"upload"));
        acl(target.getId(),"{\"view\":true}");
        rejected(List.of(f1.getId()),target.getId());rejected(List.of(f1.getId()),target.getId());
    }
    @Test void tc0302ViewWithoutDownloadCopiesAndAccountsExactly() {
        acl(target.getId(),"{\"upload\":true}");
        assertThrows(BusinessException.class,()->teamService.requirePermissions(space,f1.getId(),"download"));
        long count=fileNodeMapper.selectCount(null),used=teamSpaceMapper.selectById(space).getStorageUsed(),outbox=events.selectCount(null);
        copy(List.of(f1.getId()),target.getId());assertEquals(count+1,fileNodeMapper.selectCount(null));
        assertEquals(2,objects.selectById(f1.getObjectId()).getRefCount());assertEquals(used+1024,teamSpaceMapper.selectById(space).getStorageUsed());
        assertEquals(outbox+2,events.selectCount(null));verify(activity).log(space,"FILE_COPY","FILE",f1.getId(),null);verifyNoInteractions(storage);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void tc0303BothRootFormsEnforceUpload(boolean nullRoot) {
        Long root=nullRoot?null:0L;setUpUser(200L,1L);rejected(List.of(f1.getId()),root);
        acl(0L,"{\"upload\":true}");long count=fileNodeMapper.selectCount(null);copy(List.of(f1.getId()),root);
        assertEquals(count+1,fileNodeMapper.selectCount(null));assertEquals(2,objects.selectById(f1.getObjectId()).getRefCount());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void tc0304EntireBatchRejectedForEitherOrdering(boolean reverse) {
        role(9007199254740993L,"{}",1);acl(f1.getId(),"{\"view\":true}");acl(target.getId(),"{\"upload\":true}");
        rejected(reverse?List.of(f2.getId(),f1.getId()):List.of(f1.getId(),f2.getId()),target.getId());
    }
    @ParameterizedTest @ValueSource(strings={"preset","custom","empty","disabled"})
    void tc0309RoleValidityBeforeAcl(String variant) {
        if(!variant.equals("preset"))role(9007199254740993L,variant.equals("custom")?"{\"view\":true}":"{}",variant.equals("disabled")?0:1);
        acl(f1.getId(),"{\"view\":true}");acl(target.getId(),"{\"upload\":true}");
        if(variant.equals("disabled"))rejected(List.of(f1.getId()),target.getId());
        else {copy(List.of(f1.getId()),target.getId());assertEquals(2,objects.selectById(f1.getObjectId()).getRefCount());}
    }
    @ParameterizedTest @ValueSource(strings={"single","batch","tree"})
    void tc0307SuccessfulCopiesMatchReferencesCapacityAndEventSnapshots(String variant) throws Exception {
        FileNode folder=insertFileNode(1L,100L,space,"tree",0,0);
        FileNode sub=insertFileNode(1L,100L,space,"sub",0,0);sub.setParentId(folder.getId());sub.setPath("/tree/sub");fileNodeMapper.updateById(sub);
        FileNode deep=file("deep.txt",sub.getId());
        FileNode collision=insertFileNode(1L,100L,space,variant.equals("tree")?"tree":"one.txt",variant.equals("tree")?0:1,0);
        collision.setParentId(target.getId());collision.setPath("/target/"+collision.getName());fileNodeMapper.updateById(collision);
        var sources=switch(variant) {case "single"->List.of(f1.getId());case "batch"->List.of(f1.getId(),f2.getId());default->List.of(folder.getId());};
        var prior=fileNodeMapper.selectList(null).stream().map(FileNode::getId).toList();
        var priorEvents=events.selectList(null).stream().map(e->e.getId()).toList();
        long used=teamSpaceMapper.selectById(space).getStorageUsed();
        acl(target.getId(),"{\"upload\":true}");copy(sources,target.getId());
        var added=fileNodeMapper.selectList(null).stream().filter(n->!prior.contains(n.getId())).toList();
        int expected=variant.equals("tree")?3:variant.equals("batch")?2:1;
        assertEquals(expected,added.size());assertTrue(added.stream().anyMatch(n->n.getName().contains("(1)")));
        int files=variant.equals("batch")?2:1;
        assertEquals(used+files*1024,teamSpaceMapper.selectById(space).getStorageUsed());
        for(var node:added) {
            assertEquals(space,node.getSpaceId());assertEquals(1L,node.getTenantId());
            if(node.isFile())assertEquals(2,objects.selectById(node.getObjectId()).getRefCount());
            if(!node.getParentId().equals(target.getId()))assertTrue(added.stream().anyMatch(p->p.getId().equals(node.getParentId())&&node.getPath().equals(p.getPath()+"/"+node.getName())));
        }
        assertEquals(variant.equals("tree")?2:1,objects.selectById(deep.getObjectId()).getRefCount());
        var addedEvents=events.selectList(null).stream().filter(e->!priorEvents.contains(e.getId())).toList();
        assertEquals(expected*2,addedEvents.size());var json=new ObjectMapper();
        for(var node:added) {
            var matching=new java.util.ArrayList<String>();
            for(var event:addedEvents) {
                var payload=json.readTree(event.getPayload());var snapshot=payload.path("fileNode");
                if(snapshot.path("id").asLong()==node.getId()) {
                    matching.add(event.getEventType());assertEquals(node.getPath(),snapshot.path("path").asText());
                    assertEquals(node.getParentId().longValue(),snapshot.path("parentId").asLong());
                    assertEquals(node.getSpaceId().longValue(),snapshot.path("spaceId").asLong());
                }
            }
            assertEquals(java.util.Set.of("FILE_INDEX","SYNC_CHANGE"),java.util.Set.copyOf(matching));assertEquals(2,matching.size());
        }
        verifyNoInteractions(storage);
    }
}
