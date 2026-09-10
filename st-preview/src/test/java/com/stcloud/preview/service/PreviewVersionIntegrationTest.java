package com.stcloud.preview.service;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.preview.AbstractPreviewIntegrationTest;
import com.stcloud.preview.dto.PreviewResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史版本预览集成测试（TASK-20260910-version-preview-01）。
 * <p>
 * 覆盖：文本/视频/图片/Office 版本预览、版本归属校验、版本不存在、无权限、文件夹拒绝、当前版本回归。
 * 节点与版本走真实 H2 + MyBatis-Plus（验证表结构与租户隔离），S3/Storage/FileService 全部 Mock 隔离。
 */
class PreviewVersionIntegrationTest extends AbstractPreviewIntegrationTest {

    /**
     * 预览依赖的外部服务都是 Spring 单例 Mock，桩会在用例（甚至测试类）之间残留，
     * 例如别的用例桩过 headObject 404，会让本用例误判缩略图不存在。
     * 每个用例结束后统一重置，保证用例相互独立。
     */
    @AfterEach
    void resetMocks() {
        reset(fileService, s3Client, s3Presigner, storageService);
    }

    @Autowired
    private FileVersionMapper fileVersionMapper;

    @Autowired
    private StorageService storageService;

    @Autowired
    private FileService fileService;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Presigner s3Presigner;

    /** TC-01：文本历史版本返回该版本内容，不读当前对象 */
    @Test
    void previewVersion_text_returnsVersionContent() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "note.txt", "cur/note.txt", 0);
        FileVersion version = insertVersion(node.getId(), 1, "ver/note-v1.txt");
        when(storageService.downloadObject("ver/note-v1.txt"))
                .thenReturn(new ByteArrayInputStream("old content".getBytes(StandardCharsets.UTF_8)));

        PreviewResultVO vo = previewService.previewVersion(node.getId(), version.getId());

        assertEquals("text", vo.getType());
        assertEquals("old content", vo.getContent());
        assertEquals("txt", vo.getSuffix());
        verify(storageService, times(1)).downloadObject("ver/note-v1.txt");
    }

    /** TC-02：视频历史版本返回版本对象的预签名 URL */
    @Test
    void previewVersion_video_returnsVersionUrl() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "movie.mp4", "cur/movie.mp4", 0);
        FileVersion version = insertVersion(node.getId(), 1, "ver/movie-v1.mp4");
        when(storageService.generateDownloadUrl("ver/movie-v1.mp4"))
                .thenReturn("https://storage.example.com/ver/movie-v1.mp4");

        PreviewResultVO vo = previewService.previewVersion(node.getId(), version.getId());

        assertEquals("video", vo.getType());
        assertEquals("https://storage.example.com/ver/movie-v1.mp4", vo.getUrl());
    }

    /** TC-03：图片历史版本缩略图使用版本级 key，不覆盖当前版本缩略图 */
    @Test
    void previewVersion_image_usesVersionScopedThumbnailKey() throws Exception {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "pic.png", "cur/pic.png", 0);
        FileVersion version = insertVersion(node.getId(), 2, "ver/pic-v2.png");
        // 缩略图已存在：跳过生成，只验证 presign 使用的是版本级 key
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://preview.example.com/ver-thumb.jpg"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        PreviewResultVO vo = previewService.previewVersion(node.getId(), version.getId());

        assertEquals("image", vo.getType());
        assertTrue(vo.getUrl().contains("ver-thumb.jpg"));
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner, times(1)).presignGetObject(captor.capture());
        assertEquals("thumbnails/" + node.getId() + "/v2/lg.jpg",
                captor.getValue().getObjectRequest().key());
    }

    /** TC-04：版本不属于该文件节点 → 版本不存在 */
    @Test
    void previewVersion_belongsToOtherNode_throws() {
        setUpUser(100L, 1L);
        FileNode nodeA = insertFileNode(1L, 100L, "a.txt", "cur/a.txt", 0);
        FileNode nodeB = insertFileNode(1L, 100L, "b.txt", "cur/b.txt", 0);
        FileVersion versionOfB = insertVersion(nodeB.getId(), 1, "ver/b-v1.txt");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> previewService.previewVersion(nodeA.getId(), versionOfB.getId()));

        assertEquals(ResultCode.FILE_NOT_FOUND.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("版本不存在"));
    }

    /** TC-05：versionId 不存在 → 版本不存在 */
    @Test
    void previewVersion_versionNotFound_throws() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "c.txt", "cur/c.txt", 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> previewService.previewVersion(node.getId(), 99999999L));

        assertEquals(ResultCode.FILE_NOT_FOUND.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("版本不存在"));
    }

    /** TC-06：节点无访问权限 → 抛出无权限异常，不返回预览结果 */
    @Test
    void previewVersion_nodeNotAccessible_throws() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "d.txt", "cur/d.txt", 0);
        FileVersion version = insertVersion(node.getId(), 1, "ver/d-v1.txt");
        doThrow(new BusinessException(ResultCode.FORBIDDEN))
                .when(fileService).validateAccessible(anyLong());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> previewService.previewVersion(node.getId(), version.getId()));

        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
    }

    /** TC-07：Office 历史版本返回 unsupported */
    @Test
    void previewVersion_office_returnsUnsupported() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "doc.docx", "cur/doc.docx", 0);
        FileVersion version = insertVersion(node.getId(), 1, "ver/doc-v1.docx");

        PreviewResultVO vo = previewService.previewVersion(node.getId(), version.getId());

        assertEquals("unsupported", vo.getType());
        assertEquals("docx", vo.getSuffix());
    }

    /** TC-08：当前版本预览行为不变 */
    @Test
    void preview_currentVersion_stillWorks() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "cur.txt", "cur/cur.txt", 0);
        when(storageService.downloadObject("cur/cur.txt"))
                .thenReturn(new ByteArrayInputStream("current".getBytes(StandardCharsets.UTF_8)));

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("text", vo.getType());
        assertEquals("current", vo.getContent());
        verify(storageService, times(1)).downloadObject("cur/cur.txt");
    }

    /** TC-09：文件夹节点拒绝预览 */
    @Test
    void previewVersion_folderNode_rejected() {
        setUpUser(100L, 1L);
        FileNode folder = insertFolder(1L, 100L, "docs");
        FileVersion version = insertVersion(folder.getId(), 1, "ver/x.txt");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> previewService.previewVersion(folder.getId(), version.getId()));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("文件夹不支持预览"));
    }

    private FileVersion insertVersion(Long nodeId, int versionNum, String storagePath) {
        FileVersion version = new FileVersion();
        version.setTenantId(1L);
        version.setFileNodeId(nodeId);
        version.setVersionNum(versionNum);
        version.setFileSize(1024L);
        version.setFileMd5("md5-" + versionNum);
        version.setStoragePath(storagePath);
        version.setModifierId(100L);
        version.setModifierName("tester");
        version.setSource(0);
        version.setCreatedAt(LocalDateTime.now());
        fileVersionMapper.insert(version);
        return version;
    }

    private FileNode insertFolder(Long tenantId, Long ownerId, String name) {
        FileNode node = new FileNode();
        node.setTenantId(tenantId);
        node.setParentId(0L);
        node.setNodeType(0);
        node.setName(name);
        node.setPath("/" + name);
        node.setStatus(0);
        node.setUploaderId(ownerId);
        node.setOwnerId(ownerId);
        node.setVersion(0);
        fileNodeMapper.insert(node);
        return node;
    }
}
