package com.stcloud.core.service.impl;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.core.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件权限集成测试（TASK-006）。
 * 验证：用户级权限（所有者可访问/非所有者拒绝）、租户管理员跨用户访问、
 * 分享鉴权路径（ShareServiceImpl 依赖的 validateAccessible：回收即拒绝、恢复即放行）。
 * 使用 H2 + 真实 Mapper；外部协作以 Mock 隔离。
 */
@Import(FileServicePermissionIntegrationTest.PermissionTestConfig.class)
class FileServicePermissionIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class PermissionTestConfig {
        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher() {
            return Mockito.mock(ReliableEventPublisher.class);
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            return svc;
        }

        @Bean
        FileService fileService(FileNodeMapper fileNodeMapper, UserQuotaMapper userQuotaMapper,
                                com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper,
                                CloudStorageService cloudStorageService,
                                ReliableEventPublisher reliableEventPublisher,
                                FileObjectService fileObjectService) {
            FileServiceImpl svc = new FileServiceImpl();
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "cloudStorageService", cloudStorageService);
            ReflectionTestUtils.setField(svc, "reliableEventPublisher", reliableEventPublisher);
            ReflectionTestUtils.setField(svc, "fileObjectService", fileObjectService);
            return svc;
        }

        @Bean
        RecycleBinService recycleBinService(FileNodeMapper fileNodeMapper, UserQuotaMapper userQuotaMapper,
                                            com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper,
                                            FileService fileService, FileObjectService fileObjectService,
                                            ReliableEventPublisher reliableEventPublisher) {
            RecycleBinServiceImpl svc = new RecycleBinServiceImpl();
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "fileService", fileService);
            ReflectionTestUtils.setField(svc, "fileObjectService", fileObjectService);
            ReflectionTestUtils.setField(svc, "reliableEventPublisher", reliableEventPublisher);
            return svc;
        }
    }

    @Autowired
    private FileService fileService;

    @Autowired
    private RecycleBinService recycleBinService;

    @Autowired
    private FileNodeMapper fileNodeMapper;

    private static final Long OWNER = 3001L;
    private static final Long OTHER = 3002L;
    private static final Long TENANT = 1L;

    @BeforeEach
    void setUp() {
        setUpUser(OWNER, TENANT);
    }

    private FileNode fileOwnedBy(Long ownerId, String name) {
        FileNode n = new FileNode();
        n.setTenantId(TENANT);
        n.setParentId(0L);
        n.setNodeType(NodeType.FILE.getCode());
        n.setName(name);
        n.setPath("/" + name);
        n.setFileSize(10L);
        n.setFileMd5("md5-perm-" + name);
        n.setContentType("text/plain");
        n.setSuffix("txt");
        n.setStatus(NodeStatus.NORMAL.getCode());
        n.setUploadStatus(UploadStatus.COMPLETED.getCode());
        n.setOwnerId(ownerId);
        n.setUploaderId(ownerId);
        n.setRefCount(1);
        n.setVersion(0);
        fileNodeMapper.insert(n);
        return n;
    }

    private void switchUser(Long userId) {
        setUpUser(userId, TENANT);
    }

    @Test
    void ownerCanAccess_nonOwnerForbidden() {
        FileNode f = fileOwnedBy(OWNER, "mine.txt");
        switchUser(OWNER);
        assertDoesNotThrow(() -> fileService.getNodeByIdAndOwner(f.getId()));

        // 切换到其他用户：无权访问
        switchUser(OTHER);
        assertThrows(BusinessException.class, () -> fileService.getNodeByIdAndOwner(f.getId()));
    }

    @Test
    void tenantAdminCanAccessAcrossUsers() {
        FileNode f = fileOwnedBy(OWNER, "admin-see.txt");
        // 普通非所有者：拒绝
        switchUser(OTHER);
        assertThrows(BusinessException.class, () -> fileService.getNodeByIdAndOwner(f.getId()));

        // 租户级数据范围（dataScope=2）：可访问同租户其他用户文件
        TenantContext.setTenantId(TENANT);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(OTHER)
                .tenantId(TENANT)
                .username("tenant-admin")
                .dataScope(2)
                .build());
        assertDoesNotThrow(() -> fileService.getNodeByIdAndOwner(f.getId()));
    }

    @Test
    void shareAccessPath_recycledDenied_restoredAllowed() {
        FileNode f = fileOwnedBy(OWNER, "shared.txt");
        switchUser(OWNER);

        // 分享创建/访问的前置校验：正常态节点可访问
        assertDoesNotThrow(() -> fileService.validateAccessible(f.getId()));

        // 节点被回收：分享访问路径（validateAccessible）拒绝
        fileService.deleteToRecycleBin(List.of(f.getId()));
        assertThrows(BusinessException.class, () -> fileService.validateAccessible(f.getId()));

        // 恢复后：分享访问路径重新放行
        recycleBinService.restore(List.of(f.getId()));
        assertDoesNotThrow(() -> fileService.validateAccessible(f.getId()));
    }
}
