package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
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
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件流程集成测试（TASK-006）。
 * 验证：文件/文件夹移动（含自移动与移入子孙拒绝）、复制引用复用与引用计数、删除恢复（含子孙级联不可访问与恢复）。
 * 使用 H2 + 真实 Mapper；S3/事件/容量等外部协作以 Mock 隔离。
 */
@Import(FileServiceFlowIntegrationTest.FlowTestConfig.class)
class FileServiceFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReliableEventPublisher reliableEventPublisher;

    @TestConfiguration
    static class FlowTestConfig {
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
                                            FileService fileService, StorageService storageService,
                                            FileObjectService fileObjectService,
                                            ReliableEventPublisher reliableEventPublisher) {
            RecycleBinServiceImpl svc = new RecycleBinServiceImpl();
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "fileService", fileService);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
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
    private FileObjectService fileObjectService;

    @Autowired
    private FileNodeMapper fileNodeMapper;

    @Autowired
    private FileObjectMapper fileObjectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long USER = 2001L;
    private static final Long TENANT = 1L;

    @BeforeEach
    void setUp() {
        setUpUser(USER, TENANT);
        // 复制/配额路径依赖 sys_user 行（storage_quota NULL=不限）
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (2001, 1, 'flow-test', 'x', 1, 0, NULL, 0)");
    }

    // ---- 测试数据构建 ----

    private FileNode folder(String name, Long parentId, String path) {
        FileNode n = new FileNode();
        n.setTenantId(TENANT);
        n.setParentId(parentId == null ? 0L : parentId);
        n.setNodeType(NodeType.FOLDER.getCode());
        n.setName(name);
        n.setPath(path);
        n.setStatus(NodeStatus.NORMAL.getCode());
        n.setUploadStatus(UploadStatus.COMPLETED.getCode());
        n.setOwnerId(USER);
        n.setUploaderId(USER);
        n.setRefCount(0);
        n.setVersion(0);
        fileNodeMapper.insert(n);
        return n;
    }

    private FileNode file(String name, Long parentId, String path) {
        FileNode n = new FileNode();
        n.setTenantId(TENANT);
        n.setParentId(parentId == null ? 0L : parentId);
        n.setNodeType(NodeType.FILE.getCode());
        n.setName(name);
        n.setPath(path);
        n.setFileSize(1024L);
        n.setFileMd5("md5-flow-" + name);
        n.setContentType("text/plain");
        n.setSuffix("txt");
        n.setStatus(NodeStatus.NORMAL.getCode());
        n.setUploadStatus(UploadStatus.COMPLETED.getCode());
        n.setOwnerId(USER);
        n.setUploaderId(USER);
        n.setRefCount(1);
        n.setVersion(0);
        fileNodeMapper.insert(n);
        return n;
    }

    // ---- 文件移动 ----

    @Test
    void moveFile_persistsParentAndPath() {
        FileNode folder = folder("docs", 0L, "/docs");
        FileNode f = file("a.txt", 0L, "/a.txt");

        fileService.move(List.of(f.getId()), folder.getId());

        FileNode moved = fileNodeMapper.selectById(f.getId());
        assertEquals(folder.getId(), moved.getParentId(), "移动后父节点应更新");
        assertEquals("/docs/a.txt", moved.getPath(), "移动后路径应更新");
        // 移动后仍可访问（祖先链正常）
        assertDoesNotThrow(() -> fileService.getNodeDetail(f.getId()));
    }

    @Test
    void moveRejectsSelfAndIntoOwnDescendant() {
        FileNode folderA = folder("A", 0L, "/A");
        FileNode folderB = folder("B", folderA.getId(), "/A/B");

        // 移动到自身：拒绝
        assertThrows(BusinessException.class, () -> fileService.move(List.of(folderA.getId()), folderA.getId()));
        // 移动到自己的子孙：拒绝（防止成环）
        assertThrows(BusinessException.class, () -> fileService.move(List.of(folderA.getId()), folderB.getId()));
    }

    // ---- 复制 / 秒传引用 ----

    @Test
    void copyFile_reusesObjectAndIncrementsRefCount() {
        // 先建立物理对象（去重底座），再建引用它的文件节点
        FileObject obj = fileObjectService.acquire(TENANT, "md5-flow-copy", 100L, () -> "t1/md5-flow-copy");
        assertNotNull(obj);

        FileNode src = file("copy-src.txt", 0L, "/copy-src.txt");
        src.setFileMd5("md5-flow-copy");
        src.setFileSize(100L);
        src.setObjectId(obj.getId());
        src.setStoragePath(obj.getStoragePath());
        fileNodeMapper.updateById(src);

        fileService.copy(List.of(src.getId()), 0L);

        // 复制出的节点复用同一对象（不重复存储），对象引用计数 +1
        // 注：复制件会被重命名为 copy-src(1).txt，按 md5 精确匹配原文件 + 复制件
        List<FileNode> copies = fileNodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getFileMd5, "md5-flow-copy"));
        assertEquals(2, copies.size(), "应有原文件 + 1 个复制件");
        long sameObject = copies.stream().filter(c -> obj.getId().equals(c.getObjectId())).count();
        assertEquals(2L, sameObject, "复制件应复用同一对象");
        assertEquals(2, fileObjectMapper.getRefCount(obj.getId()), "对象引用计数应为 2");
        // 配额扣减：已用空间增加 100
        Long used = jdbcTemplate.queryForObject("SELECT storage_used FROM sys_user WHERE id = 2001", Long.class);
        assertEquals(100L, used.longValue());
    }

    // ---- 删除恢复 ----

    @Test
    void recycleFile_thenRestoreRecoversAccess() {
        FileNode f = file("r.txt", 0L, "/r.txt");

        fileService.deleteToRecycleBin(List.of(f.getId()));
        FileNode recycled = fileNodeMapper.selectById(f.getId());
        assertEquals(NodeStatus.RECYCLED.getCode(), recycled.getStatus(), "回收后状态应为 RECYCLED");
        // 回收后不可访问（节点自身非正常态）
        assertThrows(BusinessException.class, () -> fileService.getNodeDetail(f.getId()));

        recycleBinService.restore(List.of(f.getId()));
        FileNode restored = fileNodeMapper.selectById(f.getId());
        assertEquals(NodeStatus.NORMAL.getCode(), restored.getStatus(), "恢复后状态应为 NORMAL");
        // 恢复后重新可访问
        assertDoesNotThrow(() -> fileService.getNodeDetail(f.getId()));
    }

    @Test
    void recycleFolder_descendantBecomesInaccessible_thenRestoreRecovers() {
        FileNode folder = folder("sub", 0L, "/sub");
        FileNode child = file("child.txt", folder.getId(), "/sub/child.txt");

        fileService.deleteToRecycleBin(List.of(folder.getId()));
        // 只产生文件夹自身事件（索引+同步各 1 条），不逐子孙发布
        verify(reliableEventPublisher, times(1)).publishFileIndex(
                argThat(n -> n != null && n.getId().equals(folder.getId())),
                eq(FileIndexEvent.ActionType.DELETE));
        verify(reliableEventPublisher, times(1)).publishSyncChange(
                argThat(n -> n != null && n.getId().equals(folder.getId())),
                eq(SyncChangeEvent.ChangeType.DELETE));
        verify(reliableEventPublisher, never()).publishFileIndex(
                argThat(n -> n != null && n.getId().equals(child.getId())), any());
        verify(reliableEventPublisher, never()).publishSyncChange(
                argThat(n -> n != null && n.getId().equals(child.getId())), any());
        assertEquals(NodeStatus.RECYCLED.getCode(),
                fileNodeMapper.selectById(folder.getId()).getStatus());
        // 子孙自身仍是 NORMAL，但祖先被回收 → 不可访问
        assertEquals(NodeStatus.NORMAL.getCode(),
                fileNodeMapper.selectById(child.getId()).getStatus());
        assertThrows(BusinessException.class, () -> fileService.getNodeDetail(child.getId()));

        // 恢复目录后子孙重新可访问（回收/恢复双向级联失效缓存）
        recycleBinService.restore(List.of(folder.getId()));
        assertEquals(NodeStatus.NORMAL.getCode(),
                fileNodeMapper.selectById(folder.getId()).getStatus());
        assertDoesNotThrow(() -> fileService.getNodeDetail(child.getId()));
    }

    // ---- 锁定字段 VO 下发 ----

    @Test
    void vo_returnsLockFieldsForLockedNode() {
        FileNode f = file("locked.txt", 0L, "/locked.txt");
        f.setHidden(0);
        f.setLockedBy(USER);
        f.setLockedAt(LocalDateTime.now());
        f.setLockExpireAt(LocalDateTime.now().plusHours(2));
        fileNodeMapper.updateById(f);

        // 个人文件详情 VO 下发锁定字段
        FileNodeVO detail = fileService.getNodeDetail(f.getId());
        assertEquals(USER, detail.getLockedBy());
        assertNotNull(detail.getLockedAt());
        assertNotNull(detail.getLockExpireAt());

        // 个人文件列表（分页）同样下发
        IPage<FileNodeVO> page = fileService.listDirectory(0L, 1, 10);
        FileNodeVO listed = page.getRecords().stream()
                .filter(v -> f.getId().equals(v.getId())).findFirst().orElseThrow();
        assertEquals(USER, listed.getLockedBy());
        assertNotNull(listed.getLockedAt());
        assertNotNull(listed.getLockExpireAt());
    }
}
