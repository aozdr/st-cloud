package com.stcloud.preview.service.impl;

import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.ThumbnailRenderer;
import com.stcloud.preview.dto.PreviewResultVO;
import com.stcloud.preview.service.PreviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

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

    @Resource
    private ThumbnailRenderer thumbnailRenderer;

    /**
     * 团队文件访问策略：st-preview 只依赖 st-core/st-common，st-team 在运行时提供实现。
     * 使用 ObjectProvider 是为了在缺少 st-team（例如本模块单测上下文）时构造器不失败；
     * 一旦遇到团队节点而策略缺失，按拒绝处理（fail-closed），绝不放行。
     */
    @Autowired
    private ObjectProvider<TeamFileAccessPolicy> teamFileAccessPolicyProvider;

    @Override
    public PreviewResultVO preview(Long nodeId) {
        FileNode node = getFileNode(nodeId);
        String suffix = normalizeSuffix(node.getSuffix());

        if (IMAGE_TYPES.contains(suffix)) {
            if (!ThumbnailRenderer.FORMATS.contains(suffix)) {
                return PreviewResultVO.of("image", storageService.generateDownloadUrl(node.getStoragePath()));
            }
            try { return PreviewResultVO.of("image", getThumbnailUrl(nodeId, "lg")); }
            catch (BusinessException e) {
                if (e.getCode() == ResultCode.BAD_REQUEST.getCode()) return PreviewResultVO.unsupported(suffix);
                throw e;
            }
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
            if (!ThumbnailRenderer.FORMATS.contains(suffix)) {
                return PreviewResultVO.of("image", storageService.generateDownloadUrl(version.getStoragePath()));
            }
            // 历史版本缩略图使用版本级命名空间，避免覆盖当前版本缩略图缓存
            String thumbKey = "thumbnails/" + nodeId + "/v" + version.getVersionNum() + "/lg.jpg";
            try {
                if (!doesPreviewObjectExist(thumbKey)) {
                    generateThumbnail(version.getStoragePath(), suffix, thumbKey, "lg");
                }
            } catch (BusinessException e) {
                if (e.getCode() == ResultCode.BAD_REQUEST.getCode()) return PreviewResultVO.unsupported(suffix);
                throw e;
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

        if (!ThumbnailRenderer.FORMATS.contains(suffix)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成缩略图：图片格式不支持");
        }

        // 检查缩略图是否已生成
        String thumbKey = "thumbnails/" + nodeId + "/" + size + ".jpg";
        if (!doesPreviewObjectExist(thumbKey)) {
            // 生成缩略图
            generateThumbnail(node.getStoragePath(), suffix, thumbKey, size);
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

    private void generateThumbnail(String storagePath, String suffix, String thumbKey, String size) {
        int maxDim = switch (size) {
            case "sm" -> 150;
            case "md" -> 400;
            default -> 1200;
        };
        try {
            byte[] bytes = thumbnailRenderer.render(storagePath, suffix, maxDim);
            // 上传到preview bucket
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(s3StorageConfig.getPreviewBucket())
                    .key(thumbKey)
                    .contentType("image/jpeg")
                    .build();
            s3Client.putObject(putReq, RequestBody.fromBytes(bytes));
            log.info("缩略图生成成功: size={}, key={}", size, thumbKey);
        } catch (BusinessException e) {
            throw e;
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
            if (e.statusCode() == 404) return false;
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "检查缩略图失败");
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

    /**
     * 预览入口的统一资源鉴权：存在性 → 归属核权 → 可访问性（祖先链）→ 排除文件夹。
     *
     * <p>预览接口对外只有登录态（isAuthenticated），nodeId 又直接来自请求路径，
     * 因此不能只校验"节点存在且状态正常"：个人节点必须是当前用户自己的，
     * 团队节点必须交给团队访问策略即时核权（成员有效性、祖先链、文件夹权限）。
     * 团队策略缺失（例如只装配了 st-common/st-core）时按拒绝处理，避免放行。</p>
     */
    private FileNode getFileNode(Long nodeId) {
        Long tenantId = TenantContext.getTenantIdOrNull();
        Long userId = UserContext.getUserId();
        if (nodeId == null || tenantId == null || userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || !Integer.valueOf(0).equals(node.getStatus())
                || !tenantId.equals(node.getTenantId())) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (node.getSpaceId() != null && node.getSpaceId() > 0) {
            TeamFileAccessPolicy policy = teamFileAccessPolicyProvider.getIfAvailable();
            if (policy == null || !policy.canView(tenantId, userId, node.getSpaceId(), nodeId)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权预览该文件");
            }
        } else if (!userId.equals(node.getOwnerId())) {
            // 个人节点：预览与下载口径一致，只允许文件所有者
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权预览该文件");
        }
        fileService.validateAccessible(nodeId);
        if (node.isFolder()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件夹不支持预览");
        }
        return node;
    }
}
