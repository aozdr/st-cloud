package com.stcloud.core.editor;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadCommitManager;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OnlyOffice 保存回调处理实现。
 * <p>
 * 流程：验签 → 文件归属复核 → 保存锁串行 → 幂等 → 下载（大小上限 + SSRF 白名单）→
 * 去重落盘（FileObject）→ 更新 file_node（md5/size/storagePath/objectId）→ 配额差值 → 事件 →
 * status 6/7 生成版本（source=1）并裁剪 + 移除编辑标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditorCallbackServiceImpl implements EditorCallbackService {

    /** 自动保存（覆盖当前内容，不生成版本） */
    private static final int STATUS_AUTOSAVE = 2;
    /** 关闭并保存（生成版本 source=1 + 移除编辑标记） */
    private static final int STATUS_CLOSED = 6;
    /** 强制保存（同关闭保存） */
    private static final int STATUS_FORCE_SAVE = 7;

    private final EditorProperties editorProperties;
    private final EditorLockService editorLockService;
    private final FileNodeMapper fileNodeMapper;
    private final FileObjectService fileObjectService;
    private final StorageService storageService;
    private final CloudStorageService cloudStorageService;
    private final UserQuotaMapper userQuotaMapper;
    private final TeamStorageMapper teamStorageMapper;
    private final VersionService versionService;
    private final ReliableEventPublisher reliableEventPublisher;
    @Resource
    private UploadCommitManager uploadCommitManager;
    @Resource
    private UploadStorageManager uploadStorageManager;

    @Override
    public void handleCallback(Long nodeId, OnlyOfficeCallbackRequest request) {
        // 1. 验签：JWT 签名缺失/无效 → 拒绝并审计（TC-09）
        String secret = editorProperties.getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            log.error("OnlyOffice 回调验签失败：JWT 密钥未配置（nodeId={}）", nodeId);
            throw new EditorCallbackRejectedException(500, "编辑服务未配置签名密钥");
        }
        Claims claims = verifyToken(request.getToken(), secret, nodeId);
        // 签名字段与请求体一致性复核（防 body 与签名分离篡改）
        if (claims != null) {
            Object claimKey = claims.get("key");
            if (claimKey != null && request.getKey() != null && !claimKey.toString().equals(request.getKey())) {
                throw new EditorCallbackRejectedException(403, "回调 key 与签名不一致");
            }
            Object claimStatus = claims.get("status");
            if (claimStatus instanceof Number n
                    && request.getStatus() != null && n.intValue() != request.getStatus()) {
                throw new EditorCallbackRejectedException(403, "回调 status 与签名不一致");
            }
        }

        Integer status = request.getStatus();
        if (status == null
                || (status != STATUS_AUTOSAVE && status != STATUS_CLOSED && status != STATUS_FORCE_SAVE)) {
            // 其它状态（0/1/3/4/5 等）无需落盘，直接确认
            log.info("OnlyOffice 回调状态无需处理: nodeId={}, status={}", nodeId, status);
            return;
        }
        if (!StringUtils.hasText(request.getUrl())) {
            throw new EditorCallbackRejectedException(400, "回调缺少下载地址");
        }

        // 2. 文件归属复核：节点存在、文件、正常、已完成（防越权覆盖，TC-09）
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() == null || node.getStatus() != 0) {
            throw new EditorCallbackRejectedException(404, "文件不存在或不可用");
        }
        if (node.isFolder()
                || node.getUploadStatus() == null || node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new EditorCallbackRejectedException(400, "仅已完成文件接受保存回调");
        }
        // key 前缀归属复核：防伪造回调把 A 文件内容写入 B 文件
        if (request.getKey() != null && !request.getKey().startsWith(nodeId + "_")) {
            throw new EditorCallbackRejectedException(403, "回调 key 与文件不匹配");
        }

        String dedupKey = DigestUtil.sha1Hex(nodeId + "|" + status + "|"
                + (request.getKey() == null ? "" : request.getKey()) + "|" + request.getUrl());

        // 3. 保存锁：同文件保存串行化（SETNX + 10s TTL），并发回调轮询等待
        if (!acquireSaveLockWithRetry(nodeId)) {
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "文件正在保存中，请稍后重试");
        }
        try {
            // 4. 幂等：加锁后二次检查，同一 key+status+url 只落盘一次（TC-10）
            if (editorLockService.isSaveDeduped(dedupKey)) {
                log.info("OnlyOffice 重复回调已跳过: nodeId={}, status={}", nodeId, status);
                return;
            }
            doSave(node, request, status);
            editorLockService.markSaveDedup(dedupKey, status == STATUS_AUTOSAVE ? 60 : 600);
        } finally {
            editorLockService.releaseSaveLock(nodeId);
        }
    }

    /**
     * 保存落盘主流程（F5）：回调内容下载与 S3 上传在事务外执行，
     * DB 写（对象归属 + 节点 + 配额 + 事件 + 版本）收敛进 UploadCommitManager 独立事务，异常整体失败。
     */
    private void doSave(FileNode node, OnlyOfficeCallbackRequest request, Integer status) {
        Long nodeId = node.getId();
        Path tempFile = downloadToTemp(node, request.getUrl());
        try {
            long newSize;
            try {
                newSize = Files.size(tempFile);
            } catch (IOException e) {
                throw new EditorCallbackRejectedException(500, "回调临时文件读取失败");
            }
            if (newSize <= 0 || newSize > editorProperties.getMaxSaveSize()) {
                log.warn("回调内容大小不合法，拒绝落盘: nodeId={}, size={}", nodeId, newSize);
                throw new EditorCallbackRejectedException(400, "回调内容大小超出限制");
            }
            String md5;
            try {
                try (InputStream is = Files.newInputStream(tempFile)) {
                    md5 = DigestUtil.md5Hex(is);
                }
            } catch (IOException e) {
                throw new EditorCallbackRejectedException(500, "回调内容读取失败");
            }
            if (!StringUtils.hasText(md5)) {
                throw new EditorCallbackRejectedException(400, "回调内容校验失败");
            }

            long oldSize = node.getFileSize() == null ? 0 : node.getFileSize();
            long delta = newSize - oldSize;
            // 增大时先校验配额与云盘容量（与既有上传/版本恢复口径一致，事务外预检，TC-14）
            if (delta > 0) {
                checkQuotaBeforeWrite(node, delta);
                cloudStorageService.checkCapacity(delta);
            }

            // 去重预查（事务外）：同租户同 md5 复用对象 +1 引用，否则上传新物理对象（S3 在事务外，TC-07/12）
            Long tenantId = node.getTenantId();
            String contentType = node.getContentType() != null ? node.getContentType() : "application/octet-stream";
            FileObject existing = fileObjectService.findByTenantAndMd5(tenantId, md5);
            String storagePath;
            boolean uploadedNew = false;
            if (existing != null) {
                storagePath = existing.getStoragePath();
            } else {
                storagePath = tenantId + "/" + md5;
                try (InputStream is = Files.newInputStream(tempFile)) {
                    storageService.uploadObject(storagePath, is, newSize, contentType);
                } catch (IOException e) {
                    throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, "回调内容上传失败");
                }
                uploadedNew = true;
            }

            try {
                // 事务内落库：acquireByPath（对象归属）+ 节点 + 配额 + 事件 + （关闭/强制保存）版本
                uploadCommitManager.commitEditorSave(node, status, md5, newSize, storagePath, delta,
                        editorProperties.getEditorVersionLimit());
            } catch (RuntimeException e) {
                // 事务失败清理：仅当本次实际上传过新对象且无记录/引用归零时才删除，避免误删并发复用对象
                if (uploadedNew) {
                    cleanupOrphanUpload(tenantId, md5, storagePath);
                }
                throw e;
            }

            // 关闭/强制保存：提交成功后移除编辑标记（Redis 调用，事务外，TC-08/20）
            if (status == STATUS_CLOSED || status == STATUS_FORCE_SAVE) {
                if (request.getUsers() != null && !request.getUsers().isEmpty()) {
                    for (String uid : request.getUsers()) {
                        editorLockService.removeEditingUser(nodeId, uid);
                    }
                } else {
                    // 部分 OnlyOffice 版本关闭回调不携带 users 列表：按文档关闭语义清空整个编辑会话标记，
                    // 避免「保存/关闭已成功但编辑中标记残留」导致后续删除/移动/重命名被误拦
                    editorLockService.clearEditing(nodeId);
                }
            }
            log.info("OnlyOffice 回调落盘成功: nodeId={}, status={}, size={}, md5={}",
                    nodeId, status, newSize, md5);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // 临时文件清理失败不影响主流程
            }
        }
    }

    /**
     * 回调落库事务失败后的孤儿对象清理（F5，与简单上传口径一致）：
     * 仅当当前无对象记录（本次 insertIgnore 已随事务回滚）或记录引用归零且路径一致时才删除物理对象；
     * 删除失败不阻断主流程，交由定时任务兜底。
     */
    private void cleanupOrphanUpload(Long tenantId, String md5, String storagePath) {
        try {
            FileObject current = fileObjectService.findByTenantAndMd5(tenantId, md5);
            boolean noRecord = current == null;
            boolean unreferenced = current != null
                    && current.getRefCount() != null && current.getRefCount() <= 0
                    && storagePath.equals(current.getStoragePath());
            if (noRecord || unreferenced) {
                uploadStorageManager.deleteObjectQuietly(storagePath);
                log.warn("已尽力清理回调落库失败产生的孤儿对象: md5={}, storagePath={}", md5, storagePath);
            }
        } catch (Exception e) {
            // 清理失败不阻断主流程，交由定时任务兜底
            log.warn("回调落库失败清理孤儿对象异常（交由定时任务兜底）: md5={}", md5, e);
        }
    }

    /** 下载回调内容到临时文件：大小上限（Content-Length + 流式计数）与 SSRF 主机白名单（逐跳复核） */
    private Path downloadToTemp(FileNode node, String url) {
        String currentUrl = url;
        try {
            // 手动跟随重定向（最多 5 跳），每一跳都重新校验 scheme 与主机白名单，防重定向绕过 SSRF 防护
            HttpURLConnection conn = null;
            for (int hop = 0; hop <= 5; hop++) {
                URL parsed = URI.create(currentUrl).toURL();
                // SSRF 防护：仅 http/https，且主机必须命中白名单
                String scheme = parsed.getProtocol() == null ? "" : parsed.getProtocol().toLowerCase();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    throw new EditorCallbackRejectedException(400, "仅支持 http/https 下载地址");
                }
                String host = parsed.getHost();
                if (host == null || !isAllowedCallbackHost(host)) {
                    log.warn("回调下载主机不在白名单，拒绝: nodeId={}, host={}", node.getId(), host);
                    throw new EditorCallbackRejectedException(403, "回调下载地址不在白名单");
                }
                if (conn != null) {
                    conn.disconnect();
                }
                conn = (HttpURLConnection) parsed.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(60_000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (!StringUtils.hasText(location)) {
                        throw new EditorCallbackRejectedException(400, "回调下载重定向缺少地址");
                    }
                    // 相对地址基于当前 URL 解析
                    currentUrl = parsed.toURI().resolve(location).toASCIIString();
                    continue;
                }
                long contentLength = conn.getContentLengthLong();
                if (contentLength > editorProperties.getMaxSaveSize()) {
                    conn.disconnect();
                    throw new EditorCallbackRejectedException(400, "回调内容超出大小限制");
                }
                Path temp = Files.createTempFile("editor-save-", ".tmp");
                long total = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > editorProperties.getMaxSaveSize()) {
                            throw new EditorCallbackRejectedException(400, "回调内容超出大小限制");
                        }
                        out.write(buf, 0, n);
                    }
                } finally {
                    conn.disconnect();
                }
                return temp;
            }
            throw new EditorCallbackRejectedException(400, "回调下载重定向次数超限");
        } catch (EditorCallbackRejectedException e) {
            throw e;
        } catch (IOException | java.net.URISyntaxException e) {
            log.warn("回调内容下载失败: nodeId={}, url={}, err={}", node.getId(), url, e.getMessage());
            throw new EditorCallbackRejectedException(500, "回调内容下载失败");
        }
    }

    /** 回调下载主机白名单：显式配置优先；否则默认 onlyoffice 服务名/配置 url 主机 + localhost */
    private boolean isAllowedCallbackHost(String host) {
        List<String> allowed = new ArrayList<>();
        for (String h : editorProperties.getAllowedCallbackHosts()) {
            if (h != null && !h.isBlank()) {
                allowed.add(h.trim());
            }
        }
        if (allowed.isEmpty()) {
            try {
                String configuredHost = URI.create(editorProperties.getUrl()).getHost();
                if (configuredHost != null) {
                    allowed.add(configuredHost);
                }
            } catch (Exception ignored) {
                // 配置不合法时忽略，继续使用默认白名单
            }
            allowed.add("localhost");
            allowed.add("127.0.0.1");
            allowed.add("onlyoffice");
        }
        String h = host.toLowerCase();
        for (String a : allowed) {
            String candidate = a.toLowerCase();
            if (h.equals(candidate) || h.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    /** 保存锁轮询获取：最多等待约 3 秒 */
    private boolean acquireSaveLockWithRetry(Long nodeId) {
        for (int i = 0; i < 10; i++) {
            if (editorLockService.tryAcquireSaveLock(nodeId)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    /** 增大写入前的配额预检（与版本恢复/上传口径一致） */
    private void checkQuotaBeforeWrite(FileNode node, long delta) {
        if (node.getSpaceId() != null && node.getSpaceId() > 0) {
            StorageInfoVO q = teamStorageMapper.getTeamSpaceQuota(node.getSpaceId());
            if (q != null && q.getQuota() != null && q.getQuota() > 0) {
                long used = q.getUsed() == null ? 0 : q.getUsed();
                if (used + delta > q.getQuota()) {
                    throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), "团队空间存储配额不足");
                }
            }
        } else {
            StorageInfoVO q = userQuotaMapper.getUserQuota(node.getOwnerId());
            if (q != null && q.getQuota() != null && q.getQuota() > 0) {
                long used = q.getUsed() == null ? 0 : q.getUsed();
                if (used + delta > q.getQuota()) {
                    throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
                }
            }
        }
    }

    /** OnlyOffice JWT 验签：缺失/无效抛 401/403（审计由调用方日志记录） */
    private Claims verifyToken(String token, String secret, Long nodeId) {
        if (!StringUtils.hasText(token)) {
            log.warn("OnlyOffice 回调缺少签名令牌，拒绝: nodeId={}", nodeId);
            throw new EditorCallbackRejectedException(401, "回调签名缺失");
        }
        try {
            return Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("OnlyOffice 回调签名校验失败，拒绝: nodeId={}, err={}", nodeId, e.getMessage());
            throw new EditorCallbackRejectedException(403, "回调签名无效");
        }
    }
}
