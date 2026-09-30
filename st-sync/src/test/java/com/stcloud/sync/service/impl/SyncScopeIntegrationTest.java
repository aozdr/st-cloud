package com.stcloud.sync.service.impl;

import com.stcloud.common.context.*;
import com.stcloud.common.config.*;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.*;
import com.stcloud.core.service.impl.FileServiceImpl;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.sync.dto.*;
import com.stcloud.sync.entity.SyncChangeLog;
import com.stcloud.sync.mapper.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes=SyncScopeIntegrationTest.App.class,properties={
    "spring.main.web-application-type=none","spring.datasource.url=jdbc:h2:mem:sync-scope;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:sync-scope-fixture.sql","mybatis-plus.configuration.map-underscore-to-camel-case=true",
    "mybatis-plus.global-config.db-config.logic-delete-field=deleted"})
@Transactional
class SyncScopeIntegrationTest {
    @Configuration @EnableAutoConfiguration
    @MapperScan({"com.stcloud.core.mapper","com.stcloud.sync.mapper"})
    @Import({MyBatisPlusConfig.class,MyMetaObjectHandler.class,SyncServiceImpl.class,FileServiceImpl.class})
    static class App {
        @Bean CloudStorageService cloudStorageService(){return mock(CloudStorageService.class);}
        @Bean FileObjectService fileObjectService(){return mock(FileObjectService.class);}
        @Bean ReliableEventPublisher reliableEventPublisher(){return mock(ReliableEventPublisher.class);}
    }
    @Autowired SyncServiceImpl sync;
    @Autowired FileService files;
    @Autowired FileNodeMapper nodes;
    @Autowired SyncRootMapper roots;
    @Autowired SyncExclusionMapper exclusions;
    @Autowired SyncChangeLogMapper logs;
    void identity(long user,long tenant){TenantContext.setTenantId(tenant);TenantContext.setTenantMode("SAAS");UserContext.setCurrentUser(UserContext.CurrentUser.builder().userId(user).tenantId(tenant).build());}
    @AfterEach void clear(){TenantContext.clear();UserContext.clear();}
    FileNode folder(long id,String path) {
        var node=new FileNode();node.setId(id);node.setOwnerId(UserContext.getUserId());node.setUploaderId(UserContext.getUserId());node.setParentId(0L);
        node.setNodeType(0);node.setName(path.substring(1));node.setPath(path);node.setStatus(0);node.setUploadStatus(2);node.setFileSize(0L);nodes.insert(node);return node;
    }
    Long root(FileNode folder){var request=new CreateSyncRootRequest();request.setCloudFolderNodeId(folder.getId());return Long.valueOf(sync.createRoot(request).getData().getId());}
    @Test void tc0411RootsExclusionsNodesAndDeltaAreUserAndTenantScoped() {
        identity(101,1);var own=folder(9007199254740993L,"/own");Long ownRoot=root(own);Long secondRoot=root(folder(9007199254740995L,"/second"));
        var request=new AddExclusionRequest();request.setRelativePath("/private");Long exclude=Long.valueOf(sync.addExclusion(secondRoot,request).getData().getId());
        var event=new SyncChangeLog();event.setUserId(101L);event.setFileNodeId(own.getId());event.setChangeType("CREATE");event.setPath("/own/file");event.setName("file");event.setNodeType(1);logs.insert(event);
        assertEquals(1,sync.delta(ownRoot,0L,1).getData().getChanges().size());
        for(long[] attacker:new long[][]{{102,1},{101,2},{102,2}}) {
            identity(attacker[0],attacker[1]);assertTrue(sync.listRoots().getData().isEmpty());
            assertThrows(BusinessException.class,()->sync.delta(ownRoot,0L,1));assertThrows(BusinessException.class,()->sync.addExclusion(ownRoot,request));
            assertThrows(BusinessException.class,()->sync.listExclusions(secondRoot));assertThrows(BusinessException.class,()->sync.removeExclusion(secondRoot,exclude));
            assertThrows(BusinessException.class,()->sync.deleteRoot(ownRoot));assertThrows(BusinessException.class,()->files.getNodeByIdAndOwner(own.getId()));
        }
        identity(101,1);assertThrows(BusinessException.class,()->sync.delta(9223372036854775806L,0L,1));
        sync.removeExclusion(ownRoot,exclude);assertNotNull(exclusions.selectById(exclude));assertEquals(secondRoot,exclusions.selectById(exclude).getSyncRootId());
        assertEquals(2,roots.selectCount(null));assertEquals(0L,roots.selectById(ownRoot).getSyncCursor());assertEquals(1,exclusions.selectCount(null));
        assertEquals(1,sync.delta(ownRoot,0L,1).getData().getChanges().size());
    }
}
