package com.stcloud.core.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.common.utils.JwtUtils;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.editor.EditorProperties;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.NewFileService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件格式转换实现（OnlyOffice ConvertService.ashx）。
 * <p>
 * 流程：源文件校验 → 生成绑定 nodeId 的 editor 下载令牌 → 调 OnlyOffice 转换（XML 响应，轮询）→
 * 下载转换结果（主机白名单 + 大小上限）→ 复用 NewFileService.createCompletedFile 落库
 * （重名自动序号/配额/去重/事件与新建一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileConvertServiceImpl implements FileConvertService {

    /** Word 可转 PDF 的后缀 */
    private static final Set<String> WORD_SUFFIXES = Set.of("doc", "docx");
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_POLL_TRIES = 12;
    private static final long POLL_INTERVAL_MS = 2000;

    private static final Pattern FILE_URL_PATTERN = Pattern.compile("<FileUrl>(.*?)</FileUrl>");
    private static final Pattern END_CONVERT_PATTERN = Pattern.compile("<EndConvert>(.*?)</EndConvert>");
    private static final Pattern ERROR_PATTERN = Pattern.compile("<Error>(.*?)</Error>");

    private final FileNodeMapper fileNodeMapper;
    private final FileService fileService;
    private final NewFileService newFileService;
    private final EditorProperties editorProperties;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public FileNodeVO convert(Long nodeId, String fileName) {
        // 1. 源文件校验：存在、文件、正常、已完成
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (node.isFolder()
                || node.getUploadStatus() == null || node.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅已完成文件支持转换");
        }
        fileService.validateAccessible(nodeId);

        // 2. 转换方向：Word(doc/docx) -> pdf；pdf -> docx
        String sourceSuffix = node.getSuffix() == null ? "" : node.getSuffix().toLowerCase();
        String targetSuffix;
        if (WORD_SUFFIXES.contains(sourceSuffix)) {
            targetSuffix = "pdf";
        } else if ("pdf".equals(sourceSuffix)) {
            targetSuffix = "docx";
        } else {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "仅支持 Word/PDF 文件互转");
        }

        String secret = editorProperties.getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            log.error("OnlyOffice 转换失败：JWT 密钥未配置（STCLOUD_ONLYOFFICE_SECRET）");
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换服务未配置签名密钥");
        }

        // 3. 源文件下载 URL（editor 令牌，绑定 nodeId，5 分钟有效）
        Long userId = UserContext.getUserId();
        String editorToken = jwtUtils.generateEditorToken(userId, node.getTenantId(),
                UserContext.getUsername(), List.of(), List.of(), 1, nodeId);
        String sourceUrl = editorProperties.getPublicBaseUrl() + "/api/file/" + nodeId + "/stream?token="
                + URLEncoder.encode(editorToken, StandardCharsets.UTF_8);

        // 4. 调 OnlyOffice 转换并轮询拿结果地址
        String convertedUrl = requestConversion(sourceUrl, sourceSuffix, targetSuffix, nodeId, secret);

        // 5. 下载转换结果（SSRF 白名单 + 大小上限）
        byte[] content = downloadConverted(convertedUrl);

        // 6. 落库：重名自动序号等与云盘新建完全一致（NewFileService.createCompletedFile）
        String defaultName = defaultTargetName(node, targetSuffix);
        return newFileService.createCompletedFile(
                StringUtils.hasText(fileName) ? fileName : defaultName,
                targetSuffix, content, node.getParentId(), node.getSpaceId());
    }

    /** 调 OnlyOffice 转换接口；XML 响应，EndConvert=false 时按相同 key 轮询 */
    private String requestConversion(String sourceUrl, String fileType, String outputType,
                                     Long nodeId, String secret) {
        String key = nodeId + "_conv_" + System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", sourceUrl);
        payload.put("filetype", fileType);
        payload.put("outputtype", outputType);
        payload.put("title", "convert-" + outputType);
        payload.put("key", key);
        // OnlyOffice JWT 模式：对请求体（不含 token 字段）签名，token 随请求体下发
        payload.put("token", signPayload(payload, secret));

        String endpoint = editorProperties.getUrl().replaceAll("/+$", "") + "/ConvertService.ashx";
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换请求序列化失败");
        }

        for (int i = 0; i < MAX_POLL_TRIES; i++) {
            String xml = postJson(endpoint, body);
            String error = extract(xml, ERROR_PATTERN);
            if (StringUtils.hasText(error)) {
                log.warn("OnlyOffice 转换失败: nodeId={}, error={}", nodeId, error);
                throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "文件转换失败: " + error);
            }
            String fileUrl = extract(xml, FILE_URL_PATTERN);
            boolean endConvert = "True".equalsIgnoreCase(extract(xml, END_CONVERT_PATTERN));
            if (endConvert && StringUtils.hasText(fileUrl)) {
                return unescapeXml(fileUrl);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "文件转换超时，请稍后重试");
    }

    /** POST JSON 并返回响应文本（HttpURLConnection，超时保护） */
    private String postJson(String endpoint, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (var os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            try (InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (is == null) {
                    throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换服务无响应");
                }
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("OnlyOffice 转换请求失败: endpoint={}", endpoint, e);
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换服务不可用");
        }
    }

    /** 下载转换结果：仅允许 OnlyOffice 服务自身主机，且限制大小（防 SSRF/超大文件） */
    private byte[] downloadConverted(String fileUrl) {
        try {
            URL url = new URI(fileUrl).toURL();
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
            String allowedHost = new URI(editorProperties.getUrl()).getHost();
            boolean allowed = host.equals(allowedHost)
                    || "localhost".equals(host) || "127.0.0.1".equals(host);
            if (!allowed) {
                log.warn("OnlyOffice 转换结果主机不在白名单: host={}", host);
                throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换结果来源不合法");
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int status = conn.getResponseCode();
            if (status >= 400) {
                throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换结果下载失败");
            }
            long max = editorProperties.getMaxSaveSize();
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > max) {
                        throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换结果超出大小限制");
                    }
                    bos.write(buf, 0, n);
                }
                if (bos.size() <= 0) {
                    throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换结果为空");
                }
                return bos.toByteArray();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("OnlyOffice 转换结果下载失败: url={}", fileUrl, e);
            throw new BusinessException(ResultCode.EDITOR_SERVICE_ERROR, "转换结果下载失败");
        }
    }

    /** 默认目标文件名：原文件名（去后缀）-转换.目标后缀 */
    private String defaultTargetName(FileNode node, String targetSuffix) {
        String name = node.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "-转换." + targetSuffix;
    }

    /** 以 OnlyOffice JWT 密钥对 payload 签名（HS256，1 小时有效） */
    private String signPayload(Map<String, Object> payload, String secret) {
        return Jwts.builder()
                .claims(payload)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private String extract(String xml, Pattern pattern) {
        if (xml == null) return "";
        Matcher m = pattern.matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    /** XML 实体反转义（FileUrl 中的 &amp; 等） */
    private String unescapeXml(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
