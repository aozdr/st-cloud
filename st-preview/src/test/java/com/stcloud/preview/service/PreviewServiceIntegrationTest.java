package com.stcloud.preview.service;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.StorageService;
import com.stcloud.preview.AbstractPreviewIntegrationTest;
import com.stcloud.preview.dto.PreviewResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * st-preview 主路径集成测试（H7 Code Review 补测试）。
 * <p>
 * 覆盖：图片缩略图（S3 全 Mock 隔离）、文本预览、视频预览、不支持类型、非图片缩略图回退原图。
 * 文件节点走真实 H2 + MyBatis-Plus，验证 SQL/表结构/租户隔离。
 */
class PreviewServiceIntegrationTest extends AbstractPreviewIntegrationTest {

    @Autowired
    private StorageService storageService;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Presigner s3Presigner;

    @Test
    void previewImage_generatesThumbnailAndReturnsImageUrl() throws Exception {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "photo.png", "files/photo.png", 0);

        // 缩略图不存在（headObject 404）→ 触发生成 → putObject 上传 → presigner 返回预览 URL
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not Found").build());
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://preview.example.com/thumb.jpg"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
        // 真实 1x1 PNG，ImageIO 可解码
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        when(storageService.downloadObject("files/photo.png"))
                .thenReturn(new ByteArrayInputStream(baos.toByteArray()));

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("image", vo.getType());
        assertNotNull(vo.getUrl());
        assertTrue(vo.getUrl().contains("thumb.jpg"));
        // 缩略图生成链路完整走通：探测不存在 → 生成上传 → 返回预览 URL
        verify(s3Client, times(1)).headObject(any(HeadObjectRequest.class));
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void previewTextFile_returnsContentAndSuffix() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "notes.md", "files/notes.md", 0);
        // PreviewServiceImpl 按平台默认字符集解码（new String(bytes)），
        // 用 ASCII 内容保证测试与运行平台无关
        String content = "st-cloud preview test\nsecond line";
        when(storageService.downloadObject("files/notes.md"))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("text", vo.getType());
        assertEquals(content, vo.getContent());
        assertEquals("md", vo.getSuffix());
        verify(storageService, times(1)).downloadObject("files/notes.md");
    }

    @Test
    void previewVideo_returnsDownloadUrl() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "movie.mp4", "files/movie.mp4", 0);
        when(storageService.generateDownloadUrl("files/movie.mp4"))
                .thenReturn("https://storage.example.com/movie.mp4");

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("video", vo.getType());
        assertEquals("https://storage.example.com/movie.mp4", vo.getUrl());
    }

    @Test
    void previewUnsupportedType_returnsUnsupported() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "archive.exe", "files/archive.exe", 0);

        PreviewResultVO vo = previewService.preview(node.getId());

        assertEquals("unsupported", vo.getType());
        assertEquals("exe", vo.getSuffix());
    }

    @Test
    void getThumbnailUrl_nonImage_returnsOriginalUrl() {
        setUpUser(100L, 1L);
        FileNode node = insertFileNode(1L, 100L, "doc.pdf", "files/doc.pdf", 0);
        when(storageService.generateDownloadUrl("files/doc.pdf"))
                .thenReturn("https://storage.example.com/doc.pdf");

        String url = previewService.getThumbnailUrl(node.getId(), "md");

        assertEquals("https://storage.example.com/doc.pdf", url);
        // 非图片不触发 S3 缩略图探测
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }
}
