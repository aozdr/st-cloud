package com.stcloud.core.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.NewFileService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 新建空白文件服务实现。
 * <p>
 * 流程：类型白名单校验 → 归属/权限校验 → 重名命名 → 模板字节 → 配额/容量预检 →
 * S3 落盘 + file_object 去重 → 创建 file_node（创建即完成）→ 事件发布 → 原子扣减配额。
 * 核心逻辑（权限/命名/配额/事件）均在此实现并附中文注释。
 */
@Slf4j
@Service
public class NewFileServiceImpl implements NewFileService {

    /** 类型白名单：type -> 默认文件名（仅允许 txt/docx/xlsx/pptx，防任意后缀） */
    private static final Map<String, String> DEFAULT_NAMES = Map.of(
            "txt", "新建文本文档.txt",
            "docx", "新建文档.docx",
            "xlsx", "新建表格.xlsx",
            "pptx", "新建演示.pptx");

    /** 空白模板 classpath 前缀：templates/blank.{type} */
    private static final String TEMPLATE_PREFIX = "templates/blank.";

    /** 引用计数：新建文件对 file_object 的初始单引用（去重对象引用 +1） */
    private static final int REF_COUNT_INITIAL = 1;

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private UserQuotaMapper userQuotaMapper;
    @Resource
    private TeamStorageMapper teamStorageMapper;
    @Resource
    private CloudStorageService cloudStorageService;
    @Resource
    private StorageService storageService;
    @Resource
    private FileObjectService fileObjectService;
    @Resource
    private FileService fileService;
    @Resource
    private ReliableEventPublisher reliableEventPublisher;

    @Override
    @Transactional
    public FileNodeVO createBlankFile(String type, Long parentId, Long spaceId, String fileName) {
        // 1. 类型白名单校验：非法类型直接拒绝（TC-09）
        String normalized = normalizeType(type);
        byte[] content = loadTemplate(normalized);
        String baseName = normalizeFileName(fileName, normalized);
        // 2~9. 公共落库流程（归属/重名/配额/去重/建节点/事件/扣配额）
        return createCompletedFile(baseName, normalized, content, parentId, spaceId);
    }

    @Override
    @Transactional
    public FileNodeVO createCompletedFile(String fileName, String suffix, byte[] content,
                                          Long parentId, Long spaceId) {
        // 1. 上下文与归属校验：团队路径校验父目录属于该空间；个人路径校验父目录 owner 为当前用户（TC-05/06）
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        Long effectiveParentId = parentId == null ? 0L : parentId;
        if (spaceId != null && spaceId > 0) {
            fileService.validateTeamNode(spaceId, effectiveParentId);
        } else {
            checkPersonalOwner(effectiveParentId, userId);
        }

        // 2. 父目录路径 + 重名自动序号（复用 FileService.resolveNameConflict，TC-04）
        String parentPath = fileService.validateAndGetParentPath(effectiveParentId);
        String baseName = normalizeFileName(fileName, suffix);
        String nodeName = fileService.resolveNameConflict(effectiveParentId, baseName);

        // 3. 配额预检 + 云盘总容量校验（TC-07）：并发安全由第 7 步原子扣减兜底
        checkQuota(userId, spaceId, content.length);
        cloudStorageService.checkCapacity(content.length);

        // 4. S3 落盘 + file_object 去重（同租户同 md5 复用对象，不重复上传）
        String md5 = DigestUtil.md5Hex(new ByteArrayInputStream(content));
        FileObject object = fileObjectService.acquire(tenantId, md5, content.length, () -> {
            String key = tenantId + "/" + md5;
            storageService.uploadObject(key, new ByteArrayInputStream(content), content.length,
                    fileService.guessContentType(nodeName));
            return key;
        });

        // 5. 创建 file_node：新建即完成（status=NORMAL、uploadStatus=COMPLETED），owner/space 归属（TC-10）
        FileNode node = new FileNode();
        node.setParentId(effectiveParentId);
        node.setNodeType(NodeType.FILE.getCode());
        node.setName(nodeName);
        node.setPath(parentPath + "/" + nodeName);
        node.setFileSize((long) content.length);
        node.setFileMd5(md5);
        node.setContentType(fileService.guessContentType(nodeName));
        node.setSuffix(fileService.extractSuffix(nodeName));
        node.setStoragePath(object.getStoragePath());
        node.setObjectId(object.getId());
        node.setStatus(NodeStatus.NORMAL.getCode());
        node.setUploadStatus(UploadStatus.COMPLETED.getCode());
        node.setOwnerId(userId);
        node.setUploaderId(userId);
        node.setSpaceId(spaceId);
        node.setRefCount(REF_COUNT_INITIAL);
        node.setVersion(0);
        fileNodeMapper.insert(node);

        // 6. 事件链路：发布 FileIndexEvent(INDEX) + SyncChangeEvent(CREATE)（TC-08）
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
        reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.CREATE);

        // 7. 原子扣减配额：并发超配额时 update 返回 0，抛异常回滚本次新建，不产生半成品节点
        consumeQuota(userId, spaceId, content.length);

        return fileService.toVO(node);
    }

    /** 类型白名单校验并归一化（小写） */
    private String normalizeType(String type) {
        if (type == null || !DEFAULT_NAMES.containsKey(type.trim().toLowerCase())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不支持的文件类型: " + type);
        }
        return type.trim().toLowerCase();
    }

    /** 规范化用户输入的文件名：空用默认名；有输入则校验非法字符；无后缀自动补对应类型后缀 */
    private String normalizeFileName(String fileName, String type) {
        if (fileName == null || fileName.isBlank()) {
            return DEFAULT_NAMES.getOrDefault(type, "新建文件." + type);
        }
        String name = fileName.trim();
        // Windows 非法字符：\ / : * ? " < > |
        if (name.matches(".*[\\\\/:*?\"<>|].*")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "文件名包含非法字符");
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            // 无后缀或结尾为点：自动补对应类型后缀（如「周报」→「周报.docx」）
            name = name + "." + type;
        }
        return name;
    }

    /** 个人新建：父目录必须属于当前用户（租户管理员直通） */
    private void checkPersonalOwner(Long parentId, Long userId) {
        if (parentId == null || parentId <= 0) {
            return;
        }
        FileNode parent = fileNodeMapper.selectById(parentId);
        if (parent != null && !parent.getOwnerId().equals(userId) && !UserContext.canAccessTenant()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /** 加载空白内容：txt 空字节；其余读取 classpath 模板（缺失视为服务端配置错误） */
    private byte[] loadTemplate(String type) {
        if ("txt".equals(type)) {
            return new byte[0];
        }
        String path = TEMPLATE_PREFIX + type;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.error("空白模板缺失: path={}", path, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED.getCode(), "空白模板缺失: " + path);
        }
    }

    /** 配额预检：团队空间走团队配额，否则走个人配额；size<=0 跳过 */
    private void checkQuota(Long userId, Long spaceId, long size) {
        if (size <= 0) {
            return;
        }
        if (spaceId != null && spaceId > 0) {
            StorageInfoVO quota = teamStorageMapper.getTeamSpaceQuota(spaceId);
            if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
                long used = quota.getUsed() == null ? 0 : quota.getUsed();
                if (used + size > quota.getQuota()) {
                    throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED.getCode(), "团队空间存储配额不足");
                }
            }
        } else {
            StorageInfoVO quota = userQuotaMapper.getUserQuota(userId);
            if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
                long used = quota.getUsed() == null ? 0 : quota.getUsed();
                if (used + size > quota.getQuota()) {
                    throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
                }
            }
        }
    }

    /** 原子扣减配额：条件 UPDATE 保证 used+delta 不超 quota 且不为负；正向扣减失败即超限 */
    private void consumeQuota(Long userId, Long spaceId, long size) {
        if (size <= 0) {
            return;
        }
        int rows;
        if (spaceId != null && spaceId > 0) {
            rows = teamStorageMapper.updateTeamStorageUsed(spaceId, size);
        } else {
            rows = userQuotaMapper.updateStorageUsed(userId, size);
        }
        if (rows <= 0) {
            throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
        }
    }
}
