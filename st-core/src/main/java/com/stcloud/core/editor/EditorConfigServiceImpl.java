package com.stcloud.core.editor;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.common.utils.JwtUtils;
import com.stcloud.core.editor.dto.EditorConfigResponse;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.VersionService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OnlyOffice 编辑器配置生成实现。
 * <ul>
 *   <li>document.url 使用短期 editor 下载令牌（5 分钟，绑定 nodeId，不单次消费，端点收敛由过滤器保证）</li>
 *   <li>document.key = nodeId:最新版本号（会话内稳定，外部变更后变化，驱动 OnlyOffice 重新加载）</li>
 *   <li>config 整体以 HS256 签名（STCLOUD_ONLYOFFICE_SECRET），token 随 config 下发</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditorConfigServiceImpl implements EditorConfigService {

    private final EditorProperties editorProperties;
    private final EditorPermissionService editorPermissionService;
    private final EditorLockService editorLockService;
    private final JwtUtils jwtUtils;
    private final FileNodeMapper fileNodeMapper;
    private final VersionService versionService;

    @Override
    public EditorConfigResponse generateConfig(Long nodeId, boolean canEdit, boolean canDownload, boolean canPrint) {
        UserContext.CurrentUser user = UserContext.getCurrentUser();
        Long userId = user != null ? user.getUserId() : null;
        String username = user != null ? user.getUsername() : null;
        return generateConfig(nodeId, canEdit, canDownload, canPrint, userId, username,
                userId != null ? String.valueOf(userId) : null);
    }

    @Override
    public EditorConfigResponse generateConfig(Long nodeId, boolean canEdit, boolean canDownload, boolean canPrint,
                                               Long userId, String username, String editorUserId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (node.isFolder()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅文件支持在线编辑");
        }
        if (node.getUploadStatus() == null || node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件尚未上传完成");
        }
        editorPermissionService.assertSupported(node);

        String secret = editorProperties.getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            log.error("OnlyOffice JWT 密钥未配置或长度不足 32 字节（STCLOUD_ONLYOFFICE_SECRET）");
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "编辑服务未配置签名密钥");
        }

        // 编辑标记：仅可编辑时登记（只读打开不占编辑位，避免误拦删除/移动/重命名）
        if (canEdit) {
            editorLockService.markEditing(nodeId, editorUserId);
        }

        // 短期 editor 下载令牌（绑定 nodeId，仅可访问 stream 端点，不单次消费）
        String downloadToken = buildEditorDownloadToken(node, userId, username);
        String documentUrl = editorProperties.getPublicBaseUrl() + "/api/file/" + nodeId + "/stream?token="
                + URLEncoder.encode(downloadToken, StandardCharsets.UTF_8);

        // document.key 用 file_node.version（乐观锁版本）：保存回调 updateById 时 @Version 自动 +1，
        // key 随之变化 → OnlyOffice 强制重新加载文档，避免"内容已变但 key 不变"复用旧缓存；
        // 多人同时打开同一版本时 key 相同，协同编辑不受影响。
        // 注意：OnlyOffice 对 key 字符集有硬性限制（仅 0-9 a-z A-Z . - _ =），分隔符用下划线，
        // 禁止使用冒号（实测 docservice 拒绝含冒号的 key，表现为编辑器打不开/无权限）
        int latestVersion = node.getVersion() != null ? node.getVersion() : 0;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "desktop");
        // 显式设置嵌入尺寸：DocEditor 按此渲染 iframe，避免容器高度塌缩导致只显示工具栏头部
        config.put("width", "100%");
        config.put("height", "100%");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", node.getSuffix().toLowerCase());
        document.put("key", nodeId + "_" + latestVersion);
        document.put("title", node.getName());
        document.put("url", documentUrl);
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("edit", canEdit);
        permissions.put("download", canDownload);
        permissions.put("print", canPrint);
        document.put("permissions", permissions);
        config.put("document", document);
        config.put("documentType", documentType(node.getSuffix()));

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", canEdit ? "edit" : "view");
        editorConfig.put("callbackUrl",
                editorProperties.getPublicBaseUrl() + "/api/file/" + nodeId + "/editor/callback");
        editorConfig.put("lang", "zh-CN");
        if (editorUserId != null) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", editorUserId);
            user.put("name", username != null ? username : "访客");
            editorConfig.put("user", user);
        }
        // forcesave=true：用户点击「保存」立即回调落盘（而非 OnlyOffice 内部缓存），
        // 让编辑保存即时触发同步/版本，避免用户以为已保存但实际等关闭才回调（同步延迟大）
        editorConfig.put("customization", Map.of("autosave", true, "forcesave", true));
        config.put("editorConfig", editorConfig);

        // 整体签名：OnlyOffice JWT 模式要求 config（不含 token）作为 payload，token 随 config 下发
        config.put("token", signPayload(config, secret));

        log.info("生成在线编辑配置: nodeId={}, canEdit={}, editorUser={}", nodeId, canEdit, editorUserId);
        return new EditorConfigResponse(
                editorProperties.getUrl(), config);
    }

    /** 生成绑定 nodeId 的短期下载令牌（5 分钟，不单次消费；补齐 file:preview 权限以访问 stream） */
    private String buildEditorDownloadToken(FileNode node, Long userId, String username) {
        if (userId == null) {
            // 匿名（分享访客）场景：基于文件 owner 生成最小下载令牌，仅限该文件 stream
            userId = node.getOwnerId();
            username = "editor-guest";
        }
        List<String> permissions = new ArrayList<>();
        permissions.add("file:preview");
        return jwtUtils.generateEditorToken(userId, node.getTenantId(), username,
                List.of(), permissions, 1, node.getId());
    }

    /** 文档类型映射：docx-&gt;word / xlsx-&gt;cell / pptx-&gt;slide */
    private String documentType(String suffix) {
        return switch (suffix.toLowerCase()) {
            case "xlsx" -> "cell";
            case "pptx" -> "slide";
            default -> "word";
        };
    }

    /** 以 OnlyOffice JWT 密钥对 config payload 签名（HS256，1 小时有效） */
    private String signPayload(Map<String, Object> payload, String secret) {
        try {
            return Jwts.builder()
                    .claims(payload)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3600_000))
                    .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .compact();
        } catch (Exception e) {
            log.error("OnlyOffice config 签名失败", e);
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "编辑配置签名失败");
        }
    }
}
