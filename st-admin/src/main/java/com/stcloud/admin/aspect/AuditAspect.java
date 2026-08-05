package com.stcloud.admin.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.admin.entity.AuditLog;
import com.stcloud.admin.mapper.AuditLogMapper;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.utils.FileSizeUtil;
import com.stcloud.common.utils.IpUtils;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Component
public class AuditAspect {

    @Resource
    private AuditLogMapper auditLogMapper;

    @Resource
    private FileNodeMapper fileNodeMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService auditExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "audit-log-writer");
        t.setDaemon(true);
        return t;
    });

    /** 敏感参数名，记录时自动脱敏 */
    private static final Set<String> SENSITIVE_PARAMS = Set.of(
            "password", "oldPassword", "newPassword", "token", "secret", "credential", "code"
    );

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        Integer status = 1;
        String errorMsg = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            status = 0;
            log.error("审计操作异常: action={}", auditable.action(), e);
            errorMsg = "操作失败";
            throw e;
        } finally {
            try {
                AuditLog auditLog = buildAuditLog(joinPoint, auditable, status, errorMsg);
                // 异步写入，避免阻塞请求线程
                auditExecutor.execute(() -> {
                    try {
                        auditLogMapper.insert(auditLog);
                    } catch (Exception e) {
                        log.error("异步保存审计日志失败", e);
                    }
                });
            } catch (Exception e) {
                log.error("构建审计日志失败", e);
            }
        }
    }

    /**
     * 在请求线程中构建完整的 AuditLog（含 UserContext、请求信息），
     * 仅 DB insert 放入异步线程。
     */
    private AuditLog buildAuditLog(ProceedingJoinPoint joinPoint, Auditable auditable,
                                   Integer status, String errorMsg) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(UserContext.getUserId());
        auditLog.setTenantId(UserContext.getTenantId());
        auditLog.setUsername(UserContext.getUsername());
        auditLog.setAction(auditable.action());
        auditLog.setTargetType(auditable.targetType());
        auditLog.setStatus(status);

        // 提取参数名 + 值
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();
        Method method = sig.getMethod();
        Parameter[] parameters = method.getParameters();

        // 提取 targetId / targetName
        extractTargetInfo(auditLog, auditable, paramNames, args, parameters);

        // 构建结构化 detail (JSON)
        auditLog.setDetail(buildDetail(auditable, auditLog, paramNames, args, errorMsg));

        // 获取 IP 和 User-Agent
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                auditLog.setIpAddress(IpUtils.getClientIp(request));
                String ua = request.getHeader("User-Agent");
                if (ua != null && ua.length() > 500) {
                    ua = ua.substring(0, 500);
                }
                auditLog.setUserAgent(ua);
            }
        } catch (Exception e) {
            log.debug("获取请求信息失败", e);
        }

        return auditLog;
    }

    /**
     * 从方法参数中提取 targetId 和 targetName，
     * 优先使用注解指定的参数名，其次启发式匹配。
     */
    private void extractTargetInfo(AuditLog auditLog, Auditable auditable,
                                   String[] paramNames, Object[] args, Parameter[] parameters) {
        if (paramNames == null || args == null) return;

        String idParam = auditable.targetIdParam();
        String nameParam = auditable.targetNameParam();

        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            String pname = paramNames[i];
            String lower = pname.toLowerCase();

            // 显式指定的 targetIdParam
            if (!idParam.isEmpty() && pname.equals(idParam) && auditLog.getTargetId() == null) {
                auditLog.setTargetId(toLong(arg));
                continue;
            }
            // 显式指定的 targetNameParam
            if (!nameParam.isEmpty() && pname.equals(nameParam) && auditLog.getTargetName() == null) {
                auditLog.setTargetName(truncate(String.valueOf(arg), 255));
                continue;
            }
            // 启发式：参数名含 "id" 且为数字 -> targetId
            if (auditLog.getTargetId() == null && lower.endsWith("id") && !lower.equals("tenantid")) {
                Long lid = toLong(arg);
                if (lid != null) {
                    auditLog.setTargetId(lid);
                    continue;
                }
            }
            // 启发式：参数名含 "name" 且为字符串 -> targetName
            if (auditLog.getTargetName() == null && lower.contains("name") && arg instanceof String s) {
                auditLog.setTargetName(truncate(s, 255));
                continue;
            }
            // 尝试从 Request Body 对象反射取 id / name 字段
            if (auditLog.getTargetId() == null || auditLog.getTargetName() == null) {
                reflectTarget(auditLog, arg);
            }
        }
    }

    /** 尝试从对象中反射获取 id / name / folderName 等常见字段 */
    private void reflectTarget(AuditLog auditLog, Object obj) {
        if (obj == null) return;
        Class<?> cls = obj.getClass();
        // 跳过 JDK 类型、Request/Response、MultipartFile
        if (cls.getName().startsWith("java.") || cls.getName().startsWith("jakarta.")
                || cls.getName().startsWith("org.springframework.") || obj instanceof MultipartFile) {
            return;
        }
        try {
            if (auditLog.getTargetId() == null) {
                try {
                    java.lang.reflect.Field idField = findField(cls, "id");
                    if (idField != null) {
                        idField.setAccessible(true);
                        Long id = toLong(idField.get(obj));
                        if (id != null) auditLog.setTargetId(id);
                    }
                } catch (Exception ignored) {}
            }
            if (auditLog.getTargetName() == null) {
                for (String fname : new String[]{"name", "folderName", "fileName", "newName", "spaceName", "title"}) {
                    try {
                        java.lang.reflect.Field f = findField(cls, fname);
                        if (f != null) {
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            if (val instanceof String s && !s.isBlank()) {
                                auditLog.setTargetName(truncate(s, 255));
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /** 沿继承链查找字段 */
    private java.lang.reflect.Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    // ==================== 结构化 Detail 构建 ====================

    /**
     * 构建结构化 JSON detail，包含操作摘要 + 文件详情列表。
     * 格式：{"summary":"移动2个文件到「文档」","files":[{"name":"report.pdf","path":"/文档/report.pdf","size":1024,"type":"file"}],"targetPath":"/文档"}
     */
    private String buildDetail(Auditable auditable, AuditLog auditLog, String[] paramNames, Object[] args, String errorMsg) {
        String action = auditable.action();
        Object requestBody = findRequestBody(paramNames, args);

        Map<String, Object> detailMap = new LinkedHashMap<>();
        String summary;

        switch (action) {
            case "MOVE": {
                List<Long> nodeIds = getIdList(requestBody, "nodeIds");
                Long targetParentId = getLongField(requestBody, "targetParentId");
                List<FileNode> nodes = batchQueryNodes(nodeIds);
                FileNode targetParent = targetParentId != null && targetParentId > 0 ? fileNodeMapper.selectById(targetParentId) : null;
                String targetName = targetParent != null ? targetParent.getName() : "根目录";
                String targetPath = targetParent != null ? targetParent.getPath() : "/";
                summary = String.format("移动 %d 个文件到「%s」", nodes.size(), targetName);
                detailMap.put("summary", summary);
                detailMap.put("files", buildFileInfoList(nodes));
                detailMap.put("targetFolder", targetName);
                detailMap.put("targetPath", targetPath);
                break;
            }
            case "COPY": {
                List<Long> nodeIds = getIdList(requestBody, "nodeIds");
                Long targetParentId = getLongField(requestBody, "targetParentId");
                List<FileNode> nodes = batchQueryNodes(nodeIds);
                FileNode targetParent = targetParentId != null && targetParentId > 0 ? fileNodeMapper.selectById(targetParentId) : null;
                String targetName = targetParent != null ? targetParent.getName() : "根目录";
                String targetPath = targetParent != null ? targetParent.getPath() : "/";
                summary = String.format("复制 %d 个文件到「%s」", nodes.size(), targetName);
                detailMap.put("summary", summary);
                detailMap.put("files", buildFileInfoList(nodes));
                detailMap.put("targetFolder", targetName);
                detailMap.put("targetPath", targetPath);
                break;
            }
            case "DELETE": {
                List<Long> nodeIds = getIdList(requestBody, "nodeIds");
                List<FileNode> nodes = batchQueryNodes(nodeIds);
                summary = String.format("删除 %d 个文件至回收站", nodes.size());
                detailMap.put("summary", summary);
                detailMap.put("files", buildFileInfoList(nodes));
                break;
            }
            case "RESTORE": {
                List<Long> nodeIds = getIdList(requestBody, "nodeIds");
                List<FileNode> nodes = batchQueryNodes(nodeIds);
                summary = String.format("从回收站恢复 %d 个文件", nodes.size());
                detailMap.put("summary", summary);
                detailMap.put("files", buildFileInfoList(nodes));
                break;
            }
            case "PERMANENT_DELETE": {
                List<Long> nodeIds = getIdList(requestBody, "nodeIds");
                List<FileNode> nodes = batchQueryNodes(nodeIds);
                summary = String.format("永久删除 %d 个文件", nodes.size());
                detailMap.put("summary", summary);
                detailMap.put("files", buildFileInfoList(nodes));
                break;
            }
            case "EMPTY_RECYCLE": {
                summary = "清空回收站";
                detailMap.put("summary", summary);
                break;
            }
            case "RENAME": {
                Long nodeId = auditLog.getTargetId();
                String newName = getFieldValue(requestBody, "newName");
                String oldName = null;
                String oldPath = null;
                if (nodeId != null) {
                    FileNode node = fileNodeMapper.selectById(nodeId);
                    if (node != null) {
                        oldName = node.getName();
                        oldPath = node.getPath();
                    }
                }
                summary = String.format("重命名「%s」为「%s」", oldName != null ? oldName : "未知", newName != null ? newName : "未知");
                detailMap.put("summary", summary);
                detailMap.put("oldName", oldName);
                detailMap.put("newName", newName);
                detailMap.put("path", oldPath);
                break;
            }
            case "CREATE_FOLDER":
            case "TEAM_CREATE_FOLDER": {
                String folderName = getFieldValue(requestBody, "folderName");
                Long parentId = getLongField(requestBody, "parentId");
                FileNode parent = parentId != null && parentId > 0 ? fileNodeMapper.selectById(parentId) : null;
                String parentName = parent != null ? parent.getName() : "根目录";
                String parentPath = parent != null ? parent.getPath() : "/";
                summary = String.format("在「%s」下创建文件夹「%s」", parentName, folderName != null ? folderName : "未知");
                detailMap.put("summary", summary);
                detailMap.put("folderName", folderName);
                detailMap.put("parentFolder", parentName);
                detailMap.put("parentPath", parentPath);
                break;
            }
            case "UPLOAD": {
                // 合并上传完成，从返回结果中获取文件信息
                // 由于返回值在 proceed() 后已消费，这里用 targetId 查询
                Long nodeId = auditLog.getTargetId();
                if (nodeId != null) {
                    FileNode node = fileNodeMapper.selectById(nodeId);
                    if (node != null) {
                        summary = String.format("上传文件「%s」（%s）", node.getName(), FileSizeUtil.format(node.getFileSize()));
                        detailMap.put("summary", summary);
                        detailMap.put("fileName", node.getName());
                        detailMap.put("fileSize", node.getFileSize());
                        detailMap.put("path", node.getPath());
                        detailMap.put("contentType", node.getContentType());
                    } else {
                        summary = "上传文件";
                        detailMap.put("summary", summary);
                    }
                } else {
                    summary = "上传文件";
                    detailMap.put("summary", summary);
                }
                break;
            }
            case "ABORT_UPLOAD": {
                Long fileId = getLongParam(paramNames, args, "fileId");
                String uploadId = getStringParam(paramNames, args, "uploadId");
                FileNode node = fileId != null ? fileNodeMapper.selectById(fileId) : null;
                String fileName = node != null ? node.getName() : "未知";
                summary = String.format("中止上传「%s」", fileName);
                detailMap.put("summary", summary);
                detailMap.put("fileName", fileName);
                detailMap.put("uploadId", uploadId);
                break;
            }
            case "DOWNLOAD": {
                Long nodeId = auditLog.getTargetId();
                if (nodeId != null) {
                    FileNode node = fileNodeMapper.selectById(nodeId);
                    if (node != null) {
                        String dlType = "流式下载".equals(auditable.detail()) ? "单文件" : "ZIP批量";
                        summary = String.format("下载%s「%s」（%s）", dlType, node.getName(), FileSizeUtil.format(node.getFileSize()));
                        detailMap.put("summary", summary);
                        detailMap.put("fileName", node.getName());
                        detailMap.put("fileSize", node.getFileSize());
                        detailMap.put("path", node.getPath());
                    } else {
                        summary = "下载文件";
                        detailMap.put("summary", summary);
                    }
                } else {
                    // ZIP批量下载，从 BatchIdsRequest 获取 nodeIds
                    List<Long> nodeIds = getIdList(requestBody, "nodeIds");
                    List<FileNode> nodes = batchQueryNodes(nodeIds);
                    summary = String.format("ZIP批量下载 %d 个文件", nodes.size());
                    detailMap.put("summary", summary);
                    detailMap.put("files", buildFileInfoList(nodes));
                }
                break;
            }
            case "RESTORE_VERSION": {
                summary = "恢复文件历史版本";
                detailMap.put("summary", summary);
                break;
            }
            case "SYNC_ROOT_CREATE": {
                String localPath = getFieldValue(requestBody, "localPathHint");
                summary = "注册同步根" + (localPath != null ? "（" + localPath + "）" : "");
                detailMap.put("summary", summary);
                detailMap.put("localPath", localPath);
                break;
            }
            case "SYNC_ROOT_DELETE":
                summary = "注销同步根";
                detailMap.put("summary", summary);
                break;
            case "SYNC_ROOT_TOGGLE":
                summary = "暂停/恢复同步";
                detailMap.put("summary", summary);
                break;
            case "SHARE_CREATE":
                summary = "创建分享";
                detailMap.put("summary", summary);
                break;
            case "SHARE_UPDATE":
                summary = "更新分享设置";
                detailMap.put("summary", summary);
                break;
            case "SHARE_CANCEL":
                summary = "取消分享";
                detailMap.put("summary", summary);
                break;
            case "SHARE_ACCESS":
                summary = "访问分享链接";
                detailMap.put("summary", summary);
                break;
            case "REINDEX":
                summary = "重建全量索引";
                detailMap.put("summary", summary);
                break;
            case "REGISTER":
                summary = "用户注册";
                detailMap.put("summary", summary);
                break;
            case "LOGIN":
                summary = "用户登录";
                detailMap.put("summary", summary);
                break;
            case "LOGOUT":
                summary = "用户退出登录";
                detailMap.put("summary", summary);
                break;
            case "UPDATE_USER":
                summary = "更新用户信息";
                detailMap.put("summary", summary);
                break;
            case "DELETE_USER":
                summary = "删除用户";
                detailMap.put("summary", summary);
                break;
            case "TEAM_CREATE":
                summary = "创建团队空间";
                detailMap.put("summary", summary);
                break;
            case "TEAM_UPDATE":
                summary = "更新团队空间";
                detailMap.put("summary", summary);
                break;
            case "TEAM_DELETE":
                summary = "删除团队空间";
                detailMap.put("summary", summary);
                break;
            case "TEAM_INVITE":
                summary = "邀请团队成员";
                detailMap.put("summary", summary);
                break;
            case "TEAM_UPDATE_MEMBER":
                summary = "更新成员角色";
                detailMap.put("summary", summary);
                break;
            case "TEAM_REMOVE_MEMBER":
                summary = "移除团队成员";
                detailMap.put("summary", summary);
                break;
            default:
                summary = !auditable.detail().isEmpty() ? auditable.detail() : action;
                detailMap.put("summary", summary);
        }

        if (errorMsg != null) {
            detailMap.put("error", truncate(errorMsg, 500));
        }

        try {
            return objectMapper.writeValueAsString(detailMap);
        } catch (Exception e) {
            log.warn("序列化detail失败，降级为summary", e);
            return "{\"summary\":\"" + escapeJson(summary) + "\"}";
        }
    }

    // ==================== 文件信息查询辅助 ====================

    /** 批量查询文件节点，过滤掉 null */
    private List<FileNode> batchQueryNodes(List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) return Collections.emptyList();
        try {
            List<FileNode> nodes = fileNodeMapper.selectBatchIds(nodeIds);
            return nodes != null ? nodes : Collections.emptyList();
        } catch (Exception e) {
            log.warn("批量查询文件节点失败: {}", nodeIds, e);
            return Collections.emptyList();
        }
    }

    /** 将 FileNode 列表转为精简的 Map 列表 */
    private List<Map<String, Object>> buildFileInfoList(List<FileNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>(nodes.size());
        for (FileNode node : nodes) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", node.getName());
            info.put("path", node.getPath());
            info.put("size", node.getFileSize());
            info.put("type", node.isFolder() ? "folder" : "file");
            info.put("suffix", node.getSuffix());
            result.add(info);
        }
        return result;
    }

    /** 格式化文件大小 */

    // ==================== 参数提取辅助 ====================

    /** 从方法参数中查找业务请求体对象 */
    private Object findRequestBody(String[] paramNames, Object[] args) {
        if (paramNames == null || args == null) return null;
        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            Class<?> cls = arg.getClass();
            if (cls.getName().startsWith("java.") || cls.getName().startsWith("jakarta.")
                    || cls.getName().startsWith("org.springframework.") || arg instanceof MultipartFile) {
                continue;
            }
            return arg;
        }
        return null;
    }

    /** 通过反射获取对象的 String 字段值 */
    private String getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof String s && !s.isBlank()) return s;
                if (val != null) return String.valueOf(val);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 通过反射获取对象的 Long 字段值 */
    private Long getLongField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                return toLong(f.get(obj));
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 通过反射获取 List<Long> 字段值 */
    @SuppressWarnings("unchecked")
    private List<Long> getIdList(Object obj, String fieldName) {
        if (obj == null) return Collections.emptyList();
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof List<?> list) {
                    List<Long> result = new ArrayList<>();
                    for (Object item : list) {
                        Long id = toLong(item);
                        if (id != null) result.add(id);
                    }
                    return result;
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /** 从方法参数中按名称查找 Long 值 */
    private Long getLongParam(String[] paramNames, Object[] args, String name) {
        if (paramNames == null) return null;
        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            if (name.equals(paramNames[i])) return toLong(args[i]);
        }
        return null;
    }

    /** 从方法参数中按名称查找 String 值 */
    private String getStringParam(String[] paramNames, Object[] args, String name) {
        if (paramNames == null) return null;
        for (int i = 0; i < paramNames.length && i < args.length; i++) {
            if (name.equals(paramNames[i]) && args[i] instanceof String s) return s;
        }
        return null;
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Long l) return l;
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @PreDestroy
    public void shutdown() {
        auditExecutor.shutdown();
        try {
            if (!auditExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                auditExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            auditExecutor.shutdownNow();
        }
    }
}
