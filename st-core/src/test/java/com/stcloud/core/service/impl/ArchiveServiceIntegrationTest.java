package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.ArchiveProgressReporter;
import com.stcloud.core.service.ArchiveService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线解压集成测试（H2 + 真实 Mapper；S3 以 Mock 隔离）。
 * <p>
 * 覆盖：
 * 1. 解压到根目录：ZIP 条目逐个调用 S3 上传（key/内容/大小校验）+ file_node 正确落库（含嵌套文件夹）；
 * 2. 解压到用户自己的子目录：产物挂在目标目录下；
 * 3. 安全校验：他人目录 -> FORBIDDEN；目标不是文件夹 -> FILE_TYPE_NOT_ALLOWED；目标不存在 -> FILE_NOT_FOUND。
 */
@Import(ArchiveServiceIntegrationTest.ArchiveTestConfig.class)
class ArchiveServiceIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class ArchiveTestConfig {
        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        FileObjectService fileObjectService() {
            return new FileObjectServiceImpl();
        }

        @Bean
        ArchiveService archiveService() {
            return new ArchiveServiceImpl();
        }
    }

    @Resource
    private ArchiveService archiveService;
    @Resource
    private StorageService storageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FileObjectMapper fileObjectMapper;
    @Autowired
    private UserQuotaMapper userQuotaMapper;

    @BeforeEach
    void resetStorageMock() {
        Mockito.reset(storageService);
    }

    /** 为用户创建配额行（storage_used / storage_quota；quota 传 null 表示不限制） */
    private void setUpUserQuota(Long userId, Long used, Long quota) {
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, status, storage_used, storage_quota, deleted) "
                + "VALUES (?, 1, ?, 'x', 1, ?, ?, 0)", userId, "user-" + userId, used, quota);
    }

    /** 构造 ZIP 字节：条目格式 "a.txt:hello" 或 "dir/"（目录） */
    private byte[] buildZip(String... specs) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String spec : specs) {
                int idx = spec.indexOf(':');
                String name = idx >= 0 ? spec.substring(0, idx) : spec;
                String content = idx >= 0 ? spec.substring(idx + 1) : "";
                if (name.endsWith("/")) {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(content.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        }
        return bos.toByteArray();
    }

    private FileNode insertZipNode(Long tenantId, Long ownerId, Long parentId, String name) {
        FileNode node = new FileNode();
        node.setTenantId(tenantId);
        node.setParentId(parentId);
        node.setNodeType(1);
        node.setName(name);
        node.setPath("/" + name);
        node.setFileSize(1024L);
        node.setContentType("application/zip");
        node.setSuffix("zip");
        node.setStatus(0);
        node.setUploadStatus(2);
        node.setUploaderId(ownerId);
        node.setOwnerId(ownerId);
        node.setStoragePath("files/" + tenantId + "/test/" + name);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }

    private FileNode insertFolderNode(Long tenantId, Long ownerId, Long parentId, String name) {
        FileNode folder = new FileNode();
        folder.setTenantId(tenantId);
        folder.setParentId(parentId);
        folder.setNodeType(0);
        folder.setName(name);
        folder.setPath("/" + name);
        folder.setStatus(0);
        folder.setOwnerId(ownerId);
        folder.setUploaderId(ownerId);
        folder.setVersion(0);
        fileNodeMapper.insert(folder);
        return folder;
    }

    private byte[] readAll(InputStream is) {
        try (is) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void extractToRoot_uploadsAllEntriesToS3AndCreatesNodes() throws Exception {
        setUpUser(100L, 1L);
        setUpUserQuota(100L, 0L, null);
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");
        byte[] zipData = buildZip("a.txt:hello", "folder/b.txt:world", "empty/");
        when(storageService.downloadObject(zip.getStoragePath())).thenAnswer(inv -> new ByteArrayInputStream(zipData));

        int count = archiveService.extractArchive(zip.getId(), 0L);

        assertEquals(2, count);
        // S3 上传两次（a.txt + b.txt），key 落在 files/1/ 下，内容与 zip 条目一致
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(storageService, times(2))
                .uploadObject(keyCaptor.capture(), streamCaptor.capture(), sizeCaptor.capture(), anyString());
        assertTrue(keyCaptor.getAllValues().stream().allMatch(k -> k.startsWith("1/")));
        List<byte[]> contents = streamCaptor.getAllValues().stream().map(this::readAll).toList();
        assertEquals("hello", new String(contents.get(0), StandardCharsets.UTF_8));
        assertEquals("world", new String(contents.get(1), StandardCharsets.UTF_8));
        assertEquals(5L, sizeCaptor.getAllValues().get(0));
        assertEquals(5L, sizeCaptor.getAllValues().get(1));
        // file_node 落库：a.txt 在根目录，folder 目录下挂 b.txt
        FileNode a = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, 0L).eq(FileNode::getName, "a.txt").eq(FileNode::getNodeType, 1));
        assertNotNull(a);
        assertTrue(a.getStoragePath().startsWith("1/"));
        assertNotNull(a.getObjectId());
        assertEquals(DigestUtil.md5Hex("hello".getBytes(StandardCharsets.UTF_8)), a.getFileMd5());
        assertEquals("/a.txt", a.getPath());
        FileNode folder = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, 0L).eq(FileNode::getName, "folder").eq(FileNode::getNodeType, 0));
        assertNotNull(folder);
        assertEquals("/folder", folder.getPath());
        FileNode b = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, folder.getId()).eq(FileNode::getName, "b.txt"));
        assertNotNull(b);
        assertEquals("/folder/b.txt", b.getPath());
        // 配额按解压内容扣减：5B + 5B
        assertEquals(10L, userQuotaMapper.getUserQuota(100L).getUsed());
    }

    @Test
    void extractToUserSubFolder_createsNodesUnderTarget() throws Exception {
        setUpUser(100L, 1L);
        setUpUserQuota(100L, 0L, null);
        FileNode target = insertFolderNode(1L, 100L, 0L, "target");
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");
        byte[] zipData = buildZip("a.txt:hello");
        when(storageService.downloadObject(zip.getStoragePath())).thenAnswer(inv -> new ByteArrayInputStream(zipData));

        archiveService.extractArchive(zip.getId(), target.getId());

        FileNode a = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, target.getId()).eq(FileNode::getName, "a.txt"));
        assertNotNull(a);
        assertEquals("/target/a.txt", a.getPath());
    }

    @Test
    void extractToOtherUsersFolder_rejected() throws Exception {
        setUpUser(100L, 1L);
        FileNode otherFolder = insertFolderNode(1L, 200L, 0L, "other");
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> archiveService.extractArchive(zip.getId(), otherFolder.getId()));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void extractToNonFolderTarget_rejected() throws Exception {
        setUpUser(100L, 1L);
        FileNode fileNode = insertFileNode(1L, 100L, "note.txt", 0);
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> archiveService.extractArchive(zip.getId(), fileNode.getId()));
        assertEquals(ResultCode.FILE_TYPE_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void extractToMissingFolder_rejected() throws Exception {
        setUpUser(100L, 1L);
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> archiveService.extractArchive(zip.getId(), 999999L));
        assertEquals(ResultCode.FILE_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void extractWithDedupHit_reusesObjectAndIncrementsRef() throws Exception {
        setUpUser(100L, 1L);
        setUpUserQuota(100L, 0L, null);
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");
        byte[] zipData = buildZip("a.txt:hello");
        String md5 = DigestUtil.md5Hex("hello".getBytes(StandardCharsets.UTF_8));
        // 预置同租户同 md5 的 file_object（模拟云盘已存在相同内容）
        FileObject existing = new FileObject();
        existing.setTenantId(1L);
        existing.setMd5(md5);
        existing.setSize(5L);
        existing.setStoragePath("1/" + md5);
        existing.setRefCount(1);
        existing.setStatus(0);
        fileObjectMapper.insert(existing);
        when(storageService.downloadObject(zip.getStoragePath())).thenAnswer(inv -> new ByteArrayInputStream(zipData));

        archiveService.extractArchive(zip.getId(), 0L);

        // 去重命中：不重复上传物理对象，仅 +1 引用
        verify(storageService, never()).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());
        FileObject after = fileObjectMapper.selectById(existing.getId());
        assertEquals(2, after.getRefCount());
        FileNode a = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, 0L).eq(FileNode::getName, "a.txt"));
        assertNotNull(a);
        assertEquals(existing.getId(), a.getObjectId());
        assertEquals("1/" + md5, a.getStoragePath());
    }

    @Test
    void extractWhenQuotaInsufficient_rejectedBeforeAnyUpload() throws Exception {
        setUpUser(100L, 1L);
        setUpUserQuota(100L, 0L, 5L); // 配额仅 5B
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");
        byte[] zipData = buildZip("a.txt:hello!"); // 6B > 5B
        when(storageService.downloadObject(zip.getStoragePath())).thenAnswer(inv -> new ByteArrayInputStream(zipData));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> archiveService.extractArchive(zip.getId(), 0L));

        assertEquals(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), ex.getCode());
        // 预检失败：未发生任何 S3 上传，未扣配额，未建节点
        verify(storageService, never()).uploadObject(anyString(), any(InputStream.class), anyLong(), anyString());
        assertEquals(0L, userQuotaMapper.getUserQuota(100L).getUsed());
        assertEquals(0L, fileNodeMapper.selectCount(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getName, "a.txt")));
    }

    @Test
    void extractWithSoftDeletedObject_revivesTombstoneAndSucceeds() throws Exception {
        setUpUser(100L, 1L);
        setUpUserQuota(100L, 0L, null);
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");
        byte[] zipData = buildZip("a.txt:hello");
        String md5 = DigestUtil.md5Hex("hello".getBytes(StandardCharsets.UTF_8));
        // 预置同 md5 的软删除 file_object（去重墓碑：uk_tenant_md5 唯一键保留但 status/deleted 查询不可见）
        jdbcTemplate.update("INSERT INTO file_object (tenant_id, md5, size, storage_path, ref_count, status, created_at, updated_at, deleted) "
                + "VALUES (1, ?, 5, '1/" + md5 + "', 1, 1, NOW(), NOW(), 1)", md5);
        when(storageService.downloadObject(zip.getStoragePath())).thenAnswer(inv -> new ByteArrayInputStream(zipData));

        archiveService.extractArchive(zip.getId(), 0L);

        // 物理对象重新上传一次，墓碑记录被恢复并 +1 引用
        verify(storageService, times(1)).uploadObject(eq("1/" + md5), any(InputStream.class), anyLong(), anyString());
        FileObject revived = fileObjectMapper.selectByTenantAndMd5(1L, md5);
        assertNotNull(revived);
        assertEquals(0, revived.getStatus());
        assertEquals(1, revived.getRefCount());
        FileNode a = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getParentId, 0L).eq(FileNode::getName, "a.txt"));
        assertNotNull(a);
        assertEquals(revived.getId(), a.getObjectId());
    }

    @Test
    void extractReportsProgressViaReporter() throws Exception {
        setUpUser(100L, 1L);
        setUpUserQuota(100L, 0L, null);
        FileNode zip = insertZipNode(1L, 100L, 0L, "archive.zip");
        byte[] zipData = buildZip("a.txt:hello", "folder/b.txt:world");
        when(storageService.downloadObject(zip.getStoragePath())).thenAnswer(inv -> new ByteArrayInputStream(zipData));

        AtomicInteger total = new AtomicInteger(-1);
        AtomicInteger done = new AtomicInteger();
        int count = archiveService.extractArchive(zip.getId(), 0L, new ArchiveProgressReporter() {
            @Override
            public void begin(int t) {
                total.set(t);
            }

            @Override
            public void onFileExtracted() {
                done.incrementAndGet();
            }
        });

        assertEquals(2, count);
        assertEquals(2, total.get());
        assertEquals(2, done.get());
    }
}
