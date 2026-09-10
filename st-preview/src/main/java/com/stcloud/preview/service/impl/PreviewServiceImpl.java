package com.stcloud.preview.service.impl;

import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.preview.dto.PreviewResultVO;
import com.stcloud.preview.service.PreviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Service
public class PreviewServiceImpl implements PreviewService {

    private static final Set<String> IMAGE_TYPES = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg");
    private static final Set<String> VIDEO_TYPES = Set.of(
            "mp4", "avi", "mkv", "mov", "webm", "flv", "wmv");
    private static final Set<String> AUDIO_TYPES = Set.of(
            "mp3", "wav", "flac", "aac", "ogg", "m4a");
    private static final Set<String> TEXT_TYPES = Set.of(
            "txt", "md", "log", "json", "xml", "yml", "yaml", "csv",
            "js", "ts", "tsx", "jsx", "py", "java", "go", "rs", "c", "cpp", "h",
            "html", "htm", "css", "scss", "less", "sql", "sh", "bat", "ini", "conf", "toml", "rtf",
            // 与前端 isText 白名单对齐：普通预览走前端自行取文本，版本预览依赖后端，缺这些会误报"不支持"
            "markdown", "properties", "rb", "php", "vue", "svelte");
    private static final Set<String> OFFICE_TYPES = Set.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    @Resource
    private FileNodeMapper fileNodeMapper;

    @Resource
    private FileVersionMapper fileVersionMapper;

    @Resource
    private FileService fileService;

    @Resource
    private StorageService storageService;

    @Resource
    private S3Client s3Client;

    @Resource
    private S3Presigner s3Presigner;

    @Resource
    private S3StorageConfig s3StorageConfig;

    @Override
    public PreviewResultVO preview(Long nodeId) {
        FileNode node = getFileNode(nodeId);
        String suffix = normalizeSuffix(node.getSuffix());

        if (IMAGE_TYPES.contains(suffix)) {
            return PreviewResultVO.of("image", getThumbnailUrl(nodeId, "lg"));
        }
        return dispatchByStorage(node.getStoragePath(), suffix);
    }

    @Override
    public PreviewResultVO previewVersion(Long nodeId, Long versionId) {
        // 先校验节点：不存在/已删除/无权限/文件夹在此被拦截
        FileNode node = getFileNode(nodeId);
        FileVersion version = fileVersionMapper.selectById(versionId);
        // 越权防护：只凭 versionId 不能取对象，必须确认版本属于该文件节点
        if (version == null || !nodeId.equals(version.getFileNodeId())) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND.getCode(), "版本不存在");
        }
        String suffix = normalizeSuffix(node.getSuffix());

        if (IMAGE_TYPES.contains(suffix)) {
            // 历史版本缩略图使用版本级命名空间，避免覆盖当前版本缩略图缓存
            String thumbKey = "thumbnails/" + nodeId + "/v" + version.getVersionNum() + "/lg.jpg";
            if (!doesPreviewObjectExist(thumbKey)) {
                generateThumbnail(version.getStoragePath(), thumbKey, "lg");
            }
            return PreviewResultVO.of("image", generatePreviewUrl(thumbKey));
        }
        return dispatchByStorage(version.getStoragePath(), suffix);
    }

    /**
     * 按后缀分派预览：当前版本与历史版本共用，只依赖对象存储路径
     */
    private PreviewResultVO dispatchByStorage(String storagePath, String suffix) {
        if (VIDEO_TYPES.contains(suffix)) {
            return PreviewResultVO.of("video", storageService.generateDownloadUrl(storagePath));
        }
        if (AUDIO_TYPES.contains(suffix)) {
            return PreviewResultVO.of("audio", storageService.generateDownloadUrl(storagePath));
        }
        if ("pdf".equals(suffix)) {
            return PreviewResultVO.of("pdf", storageService.generateDownloadUrl(storagePath));
        }
        if (TEXT_TYPES.contains(suffix)) {
            return getTextPreview(storagePath, suffix);
        }
        if (OFFICE_TYPES.contains(suffix)) {
            return PreviewResultVO.unsupported(suffix);
        }
        return PreviewResultVO.unsupported(suffix);
    }

    private String normalizeSuffix(String suffix) {
        return suffix != null ? suffix.toLowerCase() : "";
    }

    @Override
    public String getThumbnailUrl(Long nodeId, String size) {
        FileNode node = getFileNode(nodeId);
        String suffix = node.getSuffix() != null ? node.getSuffix().toLowerCase() : "";

        // 非图片文件直接返回原图URL
        if (!IMAGE_TYPES.contains(suffix)) {
            return storageService.generateDownloadUrl(node.getStoragePath());
        }

        // 检查缩略图是否已生成
        String thumbKey = "thumbnails/" + nodeId + "/" + size + ".jpg";
        if (!doesPreviewObjectExist(thumbKey)) {
            // 生成缩略图
            generateThumbnail(node.getStoragePath(), thumbKey, size);
        }

        return generatePreviewUrl(thumbKey);
    }

    @Override
    public PreviewResultVO getVideoPreview(Long nodeId) {
        FileNode node = getFileNode(nodeId);
        // 直接返回原始视频URL供前端播放（HLS转码后续增强）
        String url = storageService.generateDownloadUrl(node.getStoragePath());
        return PreviewResultVO.of("video", url);
    }

    private PreviewResultVO getTextPreview(String storagePath, String suffix) {
        try (InputStream is = storageService.downloadObject(storagePath)) {
            String content = new String(is.readAllBytes());
            if (content.length() > 500_000) {
                content = content.substring(0, 500_000) + "\n\n... (内容已截断，仅显示前500KB)";
            }
            return PreviewResultVO.text(content, suffix);
        } catch (Exception e) {
            log.error("读取文本文件失败: storagePath={}", storagePath, e);
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "读取文件内容失败");
        }
    }

    private void generateThumbnail(String storagePath, String thumbKey, String size) {
        int maxDim = switch (size) {
            case "sm" -> 150;
            case "md" -> 400;
            default -> 1200;
        };
        try (InputStream is = storageService.downloadObject(storagePath)) {
            BufferedImage original = ImageIO.read(is);
            if (original == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "无法读取图片");
            }
            int w = original.getWidth();
            int h = original.getHeight();
            if (w > maxDim || h > maxDim) {
                double scale = (double) maxDim / Math.max(w, h);
                w = (int) (w * scale);
                h = (int) (h * scale);
            }
            BufferedImage thumbnail = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumbnail.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, w, h, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpg", baos);
            byte[] bytes = baos.toByteArray();

            // 上传到preview bucket
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(s3StorageConfig.getPreviewBucket())
                    .key(thumbKey)
                    .contentType("image/jpeg")
                    .build();
            s3Client.putObject(putReq, RequestBody.fromBytes(bytes));
            log.info("缩略图生成成功: size={}, key={}", size, thumbKey);
        } catch (Exception e) {
            log.error("缩略图生成失败: key={}", thumbKey, e);
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "缩略图生成失败");
        }
    }

    private boolean doesPreviewObjectExist(String key) {
        try {
            HeadObjectRequest req = HeadObjectRequest.builder()
                    .bucket(s3StorageConfig.getPreviewBucket())
                    .key(key)
                    .build();
            s3Client.headObject(req);
            return true;
        } catch (S3Exception e) {
            return e.statusCode() != 404;
        }
    }

    private String generatePreviewUrl(String key) {
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(g -> g.bucket(s3StorageConfig.getPreviewBucket()).key(key))
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignReq);
        return presigned.url().toString();
    }

    private FileNode getFileNode(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() != 0) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(nodeId);
        if (node.getNodeType() == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件夹不支持预览");
        }
        return node;
    }
}
