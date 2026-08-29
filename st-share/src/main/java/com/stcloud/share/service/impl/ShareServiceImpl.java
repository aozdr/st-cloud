package com.stcloud.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.common.sysconfig.SysConfigService;
import com.stcloud.common.utils.IpUtils;
import com.stcloud.core.editor.EditorConfigService;
import com.stcloud.core.editor.dto.EditorConfigResponse;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.share.dto.*;
import com.stcloud.share.entity.FileShare;
import com.stcloud.share.enums.ShareStatus;
import com.stcloud.share.mapper.FileShareMapper;
import com.stcloud.share.service.ShareService;
import com.stcloud.share.service.ShareBruteForceGuard;
import com.stcloud.share.service.ShareCaptchaService;
import com.stcloud.team.service.TeamService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShareServiceImpl implements ShareService {

    // S-14 分享标识不可枚举：57 字符集（排除易混 0/O/1/I/l），长度默认 12（可后台配置，夹紧 8~16）
    private static final String SHARE_CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final int SHARE_CODE_DEFAULT_LENGTH = 12;
    private static final int SHARE_CODE_MIN_LENGTH = 8;
    private static final int SHARE_CODE_MAX_LENGTH = 16;
    private static final int SHARE_CODE_MAX_RETRY = 8;
    private static final SecureRandom SHARE_CODE_RANDOM = new SecureRandom();

    // S-09 分享流式传输默认限速：5MB/s
    private static final long STREAM_RATE_BYTES_PER_SEC = 5 * 1024 * 1024L;
    /** 复制/保存文件时对 file_object 的初始引用计数 */
    private static final int REF_COUNT_INITIAL = 1;

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource
    private FileNodeMapper fileNodeMapper;

    @Resource
    private FileService fileService;

    @Resource
    private StorageService storageService;

    @Resource
    private TeamService teamService;
    @Resource
    private EditorConfigService editorConfigService;

    @Resource
    private ShareBruteForceGuard shareBruteForceGuard;

    @Resource
    private ShareCaptchaService shareCaptchaService;

    @Resource
    private SysConfigService sysConfigService;

    @Resource
    private FileObjectService fileObjectService;

    @Resource
    private UserQuotaMapper userQuotaMapper;

    @Resource
    private CloudStorageService cloudStorageService;

    @Resource
    private ReliableEventPublisher reliableEventPublisher;

    @Override
    @Transactional
    public Result<ShareVO> createShare(CreateShareRequest request) {
        FileNode fileNode = fileNodeMapper.selectById(request.getFileNodeId());
        if (fileNode == null || fileNode.getStatus() != 0) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(request.getFileNodeId());
        // S-01 资源级归属校验 + 权限模型分享前置/上限：
        // 个人文件必须是本人（或租户管理员）；团队文件先 requirePermissions("share")（share 权限点前置），
        // 再 resolveMyPermissions 得到用户对该文件的有效权限集（分享权限上限）
        Long currentUserId = UserContext.getUserId();
        Set<String> myPerms;
        if (fileNode.getSpaceId() == null || fileNode.getSpaceId() <= 0) {
            if (!fileNode.getOwnerId().equals(currentUserId) && !UserContext.canAccessTenant()) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权分享他人文件");
            }
            // 个人文件：拥有者有效权限 = {view, download}（与 effective-permissions 接口口径一致；
            // 个人分享权限上限统一为 view+download，超权（upload/delete 等）由服务端拒绝）
            myPerms = PERSONAL_EFFECTIVE_PERMS;
        } else {
            // 团队文件：share 权限点前置（成员校验 + 权限集校验）
            teamService.requirePermissions(fileNode.getSpaceId(), fileNode.getId(), "share");
            myPerms = teamService.resolveMyPermissions(fileNode.getSpaceId(), fileNode.getId());
            if (myPerms == null || myPerms.isEmpty()) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "分享权限不能超过你的权限");
            }
        }

        FileShare share = new FileShare();
        share.setShareCode(generateShareCode());
        share.setFileNodeId(request.getFileNodeId());
        share.setCreatorId(UserContext.getUserId());
        share.setShareType(request.getShareType() != null ? request.getShareType() : 0);
        if (share.getShareType() == 1) {
            String pwd = request.getPassword();
            if (pwd == null || pwd.isBlank()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "私密分享必须设置密码");
            }
            share.setPassword(pwd);
        }
        // 过期时间校验：非空时必须晚于当前时间（Asia/Shanghai 本地墙钟），否则拒绝创建，避免创建即过期
        if (request.getExpireAt() != null && !request.getExpireAt().isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "过期时间必须晚于当前时间");
        }
        share.setExpireAt(request.getExpireAt());
        // 分享权限集解析：permissions JSON 优先 → permission 单值映射 → 默认=用户有效权限（个人文件默认 view+download）
        Set<String> sharePerms = resolveSharePermissionSet(request, myPerms, fileNode);
        // 分享权限上限：请求权限集必须 ⊆ 用户对该文件的有效权限集，否则拒绝
        if (!myPerms.containsAll(sharePerms)) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "分享权限不能超过你的权限");
        }
        share.setPermissions(toPermissionsJson(sharePerms));
        // 兼容旧单值字段：显式传入保留，未传按权限集推导（download→1，upload→2，管理操作→3，仅查看→0）
        share.setPermission(request.getPermission() != null
                ? request.getPermission()
                : legacyPermissionFromPerms(sharePerms));
        // allow_download 联动：权限集含 download → 1，与显式 allowDownload 取交集（两者都允许才 1）
        share.setAllowDownload(resolveAllowDownload(sharePerms, request.getAllowDownload()));
        share.setDownloadLimit(request.getDownloadLimit());
        share.setDownloadCount(0);
        share.setViewCount(0);
        // 新建分享默认有效
        share.setStatus(ShareStatus.ACTIVE.getCode());
        fileShareMapper.insert(share);

        log.info("用户{}创建分享: fileNodeId={}, shareCode={}", UserContext.getUserId(), request.getFileNodeId(), share.getShareCode());

        return Result.success(toVO(share, fileNode.getName()));
    }

    @Override
    public Result<IPage<ShareVO>> listShares(int page, int size) {
        Long userId = UserContext.getUserId();
        Page<FileShare> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(FileShare::getCreatorId, userId)
                .orderByDesc(FileShare::getCreatedAt);
        IPage<FileShare> sharePage = fileShareMapper.selectPage(pageParam, wrapper);

        IPage<ShareVO> voPage = sharePage.convert(share -> {
            FileNode node = fileNodeMapper.selectById(share.getFileNodeId());
            return toVO(share, node != null ? node.getName() : "未知文件");
        });
        return Result.success(voPage);
    }

    @Override
    public Result<Map<String, Boolean>> effectivePermissions(Long fileNodeId) {
        // 未登录 → 空集（接口位于需认证区，防御性兜底）
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            return Result.success(Collections.emptyMap());
        }
        FileNode fileNode = fileNodeMapper.selectById(fileNodeId);
        if (fileNode == null || fileNode.getStatus() != 0) {
            return Result.success(Collections.emptyMap());
        }
        Set<String> perms;
        if (fileNode.getSpaceId() == null || fileNode.getSpaceId() <= 0) {
            // 个人文件：仅本人拥有 view+download 有效权限；非本人 → 空集
            if (!currentUserId.equals(fileNode.getOwnerId())) {
                return Result.success(Collections.emptyMap());
            }
            perms = PERSONAL_EFFECTIVE_PERMS;
        } else {
            // 团队文件：resolveMyPermissions 并集（非成员/无权限 → 空集，不抛出）
            try {
                perms = teamService.resolveMyPermissions(fileNode.getSpaceId(), fileNode.getId());
            } catch (BusinessException e) {
                log.warn("查询团队文件有效权限失败，返回空集: fileNodeId={}, err={}", fileNodeId, e.getMessage());
                return Result.success(Collections.emptyMap());
            }
            if (perms == null || perms.isEmpty()) {
                return Result.success(Collections.emptyMap());
            }
        }
        Map<String, Boolean> permMap = new TreeMap<>();
        for (String p : perms) {
            permMap.put(p, true);
        }
        return Result.success(permMap);
    }

    @Override
    public Result<EditorConfigResponse> editorConfig(String shareCode, Long nodeId, String password,
                                                      String captchaId, String captchaCode) {
        FileShare share = validateShareAccess(shareCode, password, captchaId, captchaCode);
        Long targetNodeId = nodeId != null ? nodeId : share.getFileNodeId();
        FileNode targetNode = fileNodeMapper.selectById(targetNodeId);
        if (targetNode == null || targetNode.getStatus() != 0 || targetNode.getNodeType() != 1) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(targetNodeId);
        // S-03 子文件归属校验：path 必须落在分享根路径边界内（防同名前缀越权）
        if (nodeId != null) {
            FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
            if (root == null || !isWithinShare(root, targetNode)) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权访问该文件");
            }
        }
        // 编辑判定：分享权限集含 edit（新增权限点，2026-08-15）；旧数据兼容 upload；下载/打印按 download 权限
        Set<String> perms = parsePermissions(share.getPermissions());
        if (perms.isEmpty()) {
            perms = legacyPermissionSet(share.getPermission());
        }
        boolean canEdit = perms.contains("edit") || perms.contains("upload");
        boolean canDownload = perms.contains("download");
        // 分享访客无登录态：使用分享码生成编辑器用户标识（关闭回调据此移除编辑标记）
        String guestId = "share:" + share.getShareCode();
        EditorConfigResponse response = editorConfigService.generateConfig(
                targetNodeId, canEdit, canDownload, canDownload, null, "访客", guestId);
        return Result.success(response);
    }

    @Override
    @Transactional
    public Result<Void> cancelShare(Long shareId) {
        FileShare share = checkOwnership(shareId);
        // 取消分享置为已取消
        share.setStatus(ShareStatus.CANCELLED.getCode());
        fileShareMapper.updateById(share);
        log.info("用户{}取消分享: shareId={}", UserContext.getUserId(), shareId);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> updateShare(Long shareId, UpdateShareRequest request) {
        FileShare share = checkOwnership(shareId);

        // 权限模型：更新同样执行 share 前置 + 分享权限 ⊆ 用户有效权限（与 createShare 对齐）
        FileNode fileNode = fileNodeMapper.selectById(share.getFileNodeId());
        if (fileNode == null || fileNode.getStatus() != 0) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        Set<String> myPerms = resolveMyEffectivePerms(fileNode);

        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FileShare::getId, shareId);
        // 记录是否有实际变更字段，避免无 set 子句的非法 UPDATE（MyBatis-Plus/MySQL 均不接受）
        boolean hasChanges = false;
        if (request.getShareType() != null) {
            wrapper.set(FileShare::getShareType, request.getShareType());
            hasChanges = true;
        }
        if (request.getPassword() != null) {
            wrapper.set(FileShare::getPassword, request.getPassword());
            hasChanges = true;
        }
        // 过期时间状态流转：clearExpireAt=true 时清除过期（设为永久），优先级高于 expireAt；
        // 否则修改过期时间同样要求晚于当前时间，避免更新后立即失效
        if (Boolean.TRUE.equals(request.getClearExpireAt())) {
            wrapper.set(FileShare::getExpireAt, null);
            hasChanges = true;
        } else if (request.getExpireAt() != null) {
            if (!request.getExpireAt().isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "过期时间必须晚于当前时间");
            }
            wrapper.set(FileShare::getExpireAt, request.getExpireAt());
            hasChanges = true;
        }
        if (request.getPermissions() != null) {
            if (request.getPermissions().isBlank()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "permissions 不能为空");
            }
            Set<String> sharePerms = parsePermissions(request.getPermissions());
            if (sharePerms.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "permissions 不能为空");
            }
            // 分享权限上限：请求权限集 ⊆ 用户有效权限，否则拒绝
            if (!myPerms.containsAll(sharePerms)) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "分享权限不能超过你的权限");
            }
            wrapper.set(FileShare::getPermissions, toPermissionsJson(sharePerms));
            // 兼容单值字段：按权限集推导
            wrapper.set(FileShare::getPermission, legacyPermissionFromPerms(sharePerms));
            // allow_download 联动：权限集含 download，与显式 allowDownload 取交集
            wrapper.set(FileShare::getAllowDownload, resolveAllowDownload(sharePerms, request.getAllowDownload()));
            hasChanges = true;
        } else if (request.getPermission() != null) {
            // 旧单值兼容：按 DB 迁移映射转权限集后同样走上限校验与 allow_download 联动
            Set<String> sharePerms = legacyPermissionSet(request.getPermission());
            if (!myPerms.containsAll(sharePerms)) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "分享权限不能超过你的权限");
            }
            wrapper.set(FileShare::getPermission, request.getPermission());
            wrapper.set(FileShare::getPermissions, toPermissionsJson(sharePerms));
            wrapper.set(FileShare::getAllowDownload, resolveAllowDownload(sharePerms, request.getAllowDownload()));
            hasChanges = true;
        }
        if (request.getAllowDownload() != null
                && request.getPermissions() == null
                && request.getPermission() == null) {
            wrapper.set(FileShare::getAllowDownload, request.getAllowDownload());
            hasChanges = true;
        }
        if (request.getDownloadLimit() != null) {
            wrapper.set(FileShare::getDownloadLimit, request.getDownloadLimit());
            hasChanges = true;
        }
        if (request.getStatus() != null) {
            wrapper.set(FileShare::getStatus, request.getStatus());
            hasChanges = true;
        }
        if (hasChanges) {
            fileShareMapper.update(null, wrapper);
        }
        log.info("用户{}修改分享: shareId={}", UserContext.getUserId(), shareId);
        return Result.success();
    }

    @Override
    public Result<Map<String, String>> getCaptcha() {
        ShareCaptchaService.CaptchaIssue issue = shareCaptchaService.issue();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("captchaId", issue.captchaId());
        data.put("imageBase64", issue.imageBase64());
        return Result.success(data);
    }

    @Override
    @Transactional
    public Result<ShareAccessVO> accessShare(ShareAccessRequest request) {
        FileShare share = validateShareAccess(request.getShareCode(), request.getPassword(),
                request.getCaptchaId(), request.getCaptchaCode());

        // 增加访问次数
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId())
                .setSql("view_count = view_count + 1"));

        FileNode node = fileNodeMapper.selectById(share.getFileNodeId());
        if (node == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(share.getFileNodeId());

        ShareAccessVO vo = new ShareAccessVO();
        vo.setFileName(node.getName());
        vo.setFileType(node.getNodeType());
        vo.setSuffix(node.getSuffix());
        vo.setSize(node.getFileSize());
        vo.setPermission(share.getPermission());
        vo.setPermissions(share.getPermissions());
        vo.setFileNodeId(share.getFileNodeId());
        vo.setShareType(share.getShareType());
        return Result.success(vo);
    }

    @Override
    // F1-1 只读方法去事务：本方法仅读分享/节点 + 生成预签名 URL + 单条原子 UPDATE（下载计数），
    // 无多写一致性需求，不开启 DB 事务，避免无效事务占用连接（设计文档 F1-1）
    public Result<String> getDownloadUrl(String shareCode, Long nodeId, String password,
                                          String captchaId, String captchaCode) {
        FileShare share = validateShareAccess(shareCode, password, captchaId, captchaCode);

        // S-02 权限校验：allow_download=0 统一禁止下载 URL（permission==0 双保险保留）
        if (share.getAllowDownload() == null || share.getAllowDownload() == 0) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "该分享不可下载");
        }
        // S-02 权限校验：permission=0（仅查看）不允许生成下载 URL
        if (share.getPermission() == null || share.getPermission() == 0) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "仅查看不可下载");
        }
        // S-02 分享权限集校验（新模型双保险）：权限集含 download 才允许
        if (!shareAllowsDownload(share)) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "该分享不可下载");
        }

        Long targetNodeId = nodeId != null ? nodeId : share.getFileNodeId();
        FileNode targetNode = fileNodeMapper.selectById(targetNodeId);
        if (targetNode == null || targetNode.getStatus() != 0) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        // 保留 DownloadServiceImpl 的下载前置约束：文件夹不可单文件下载、未完成上传不可下载
        if (targetNode.getNodeType() == 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件夹不支持单文件下载，请使用ZIP下载");
        }
        if (targetNode.getUploadStatus() == null || targetNode.getUploadStatus() != UploadStatus.COMPLETED.getCode()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "文件尚未上传完成");
        }
        fileService.validateAccessible(targetNodeId);
        // S-03 子文件下载校验归属：path 必须落在分享根路径边界内（含 "/" 边界，防同名前缀越权）
        if (nodeId != null) {
            FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
            if (root == null || !isWithinShare(root, targetNode)) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权访问该文件");
            }
        }

        // S-07 下载次数原子条件更新：下载限额为空或未达上限才递增，
        // 消除"先查后增"的 TOCTOU 竞态，并发下由数据库保证计数不超限
        int updated = fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId())
                .and(w -> w.isNull(FileShare::getDownloadLimit)
                        .or().apply("download_count < download_limit"))
                .setSql("download_count = download_count + 1"));
        if (updated == 0) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "下载次数已达上限");
        }

        // S-02 分享链路直接基于 storagePath 生成预签名 URL，不再走 DownloadServiceImpl 的个人 owner 校验（消除匿名 NPE）
        String url = storageService.generateDownloadUrl(targetNode.getStoragePath());
        return Result.success(url);
    }

    @Override
    public Result<List<FileNodeVO>> listShareFiles(String shareCode, Long parentId, String password,
                                                   String captchaId, String captchaCode) {
        FileShare share = validateShareAccess(shareCode, password, captchaId, captchaCode);

        FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
        if (root == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (root.getNodeType() != 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只能浏览文件夹分享");
        }

        Long queryParentId = parentId != null ? parentId : share.getFileNodeId();
        fileService.validateAccessible(queryParentId);

        // S-03 子目录归属校验：parentId 节点的 path 必须落在分享根路径边界内（含 "/" 边界，防同名前缀越权）
        if (parentId != null && !parentId.equals(share.getFileNodeId())) {
            FileNode parent = fileNodeMapper.selectById(parentId);
            if (parent == null || parent.getStatus() != 0) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND);
            }
            if (!isWithinShare(root, parent)) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权访问该目录");
            }
        }

        List<FileNode> children = fileNodeMapper.selectList(
                new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getParentId, queryParentId)
                        .eq(FileNode::getStatus, 0)
                        .orderByAsc(FileNode::getNodeType)
                        .orderByAsc(FileNode::getName));

        List<FileNodeVO> voList = children.stream().map(this::toFileNodeVO).collect(Collectors.toList());
        return Result.success(voList);
    }

    @Override
    public void streamShareFile(String shareCode, Long nodeId, String password,
                                String captchaId, String captchaCode, HttpServletResponse response) {
        // 分享码+提取码验证即为认证，无需用户登录 token
        FileShare share = validateShareAccess(shareCode, password, captchaId, captchaCode);

        // S-02 下载开关校验：allow_download=0 禁止流式预览/下载（堵住绕过 getDownloadUrl 的流式链路）
        if (share.getAllowDownload() == null || share.getAllowDownload() == 0) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "该分享不可下载");
        }
        // 分享权限集校验（新模型双保险）：分享权限集含 download 才允许流式/下载
        if (!shareAllowsDownload(share)) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "该分享不可下载");
        }

        // S-02 下载次数限制快速失败（可选）：与 getDownloadUrl 同口径，达到上限直接拒绝；
        // 并发下的最终闸门为流式成功后的原子条件更新（S-07）
        if (share.getDownloadLimit() != null && share.getDownloadCount() >= share.getDownloadLimit()) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "下载次数已达上限");
        }

        Long targetNodeId = nodeId != null ? nodeId : share.getFileNodeId();
        FileNode targetNode = fileNodeMapper.selectById(targetNodeId);
        if (targetNode == null || targetNode.getStatus() != 0 || targetNode.getNodeType() != 1) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(targetNodeId);
        // S-03 子文件流式预览校验归属：path 必须落在分享根路径边界内（含 "/" 边界，防同名前缀越权）
        if (nodeId != null) {
            FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
            if (root == null || !isWithinShare(root, targetNode)) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权访问该文件");
            }
        }

        response.setContentType(targetNode.getContentType() != null
                ? targetNode.getContentType() : "application/octet-stream");
        try {
            String encodedName = URLEncoder.encode(targetNode.getName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setHeader("Content-Disposition",
                    "inline; filename=\"" + encodedName + "\"");
            if (targetNode.getFileSize() != null) {
                response.setContentLengthLong(targetNode.getFileSize());
            }
        } catch (Exception e) {
            log.warn("设置响应头失败: {}", e.getMessage());
        }

        boolean streamCompleted = false;
        long streamStartNanos = System.nanoTime();
        long totalBytes = 0L;
        try (InputStream is = storageService.downloadObject(targetNode.getStoragePath());
             OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
                totalBytes += len;
                // S-09 流式限速：每写一块按累计字节校准节奏，保证平均速率不超过 5MB/s
                paceStream(totalBytes, streamStartNanos);
            }
            os.flush();
            streamCompleted = true;
        } catch (IOException e) {
            log.warn("分享文件流式传输中断: shareCode={}, nodeId={}, err={}",
                    shareCode, targetNodeId, e.getMessage());
        }
        // S-07/S-02 流式预览/下载成功后同样消耗下载次数：原子条件更新，下载限额为空或未达上限才递增，
        // 并发下由数据库保证计数不超限（与 getDownloadUrl 统一口径，仅成功后计数）
        if (streamCompleted) {
            int updated = fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                    .eq(FileShare::getId, share.getId())
                    .and(w -> w.isNull(FileShare::getDownloadLimit)
                            .or().apply("download_count < download_limit"))
                    .setSql("download_count = download_count + 1"));
            if (updated == 0) {
                log.warn("分享流式下载次数已达上限: shareCode={}, nodeId={}", shareCode, targetNodeId);
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "下载次数已达上限");
            }
        }
    }

    /**
     * S-09 分享流式传输限速：按已传输字节计算理论最短耗时（totalBytes / 5MB/s），
     * 实际耗时未达标时休眠补齐，保证平均速率不超过默认上限。
     */
    private void paceStream(long totalBytes, long streamStartNanos) throws IOException {
        long expectedNanos = totalBytes * 1_000_000_000L / STREAM_RATE_BYTES_PER_SEC;
        long sleepNanos = expectedNanos - (System.nanoTime() - streamStartNanos);
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("流式分享限速休眠被中断", e);
            }
        }
    }

    private FileShare validateShareAccess(String shareCode, String password,
                                          String captchaId, String captchaCode) {
        FileShare share = fileShareMapper.selectOne(
                new LambdaQueryWrapper<FileShare>().eq(FileShare::getShareCode, shareCode));
        // S-14 分享不存在/已取消：直抛，不浪费资源，不计频控（用户决策 P3）
        if (share == null || share.getStatus() == ShareStatus.CANCELLED.getCode()) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }
        // 过期校验：已过期分享直接拒绝，且不计频控
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))) {
            throw new BusinessException(ResultCode.SHARE_EXPIRED);
        }
        if (share.getShareType() == 1) {
            String ip = resolveClientIp();
            // 锁定检查：单分享码或 IP 任一命中直接拒绝
            if (shareBruteForceGuard.isLocked(ip, shareCode)) {
                throw new BusinessException(ResultCode.SHARE_ATTEMPT_LIMIT);
            }
            // 失败达阈值后要求图形验证码，通过才继续校验提取码
            if (shareBruteForceGuard.needsCaptcha(shareCode)) {
                if (!shareCaptchaService.verify(captchaId, captchaCode)) {
                    shareBruteForceGuard.recordCaptchaFailure(ip);
                    throw new BusinessException(ResultCode.SHARE_CAPTCHA_REQUIRED);
                }
            }
            if (password == null || !password.equals(share.getPassword())) {
                shareBruteForceGuard.recordFailure(ip, shareCode);
                throw new BusinessException(ResultCode.SHARE_PASSWORD_ERROR);
            }
            // 提取码正确：清除该分享码失败计数与锁定
            shareBruteForceGuard.clearFailure(shareCode);
        }
        return share;
    }

    private String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? IpUtils.getClientIp(attrs.getRequest()) : null;
    }

    private FileNodeVO toFileNodeVO(FileNode node) {
        FileNodeVO vo = new FileNodeVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setNodeType(node.getNodeType());
        vo.setName(node.getName());
        vo.setPath(node.getPath());
        vo.setFileSize(node.getFileSize());
        vo.setSuffix(node.getSuffix());
        vo.setContentType(node.getContentType());
        vo.setStatus(node.getStatus());
        vo.setThumbnailPath(node.getThumbnailPath());
        vo.setCreatedAt(node.getCreatedAt());
        vo.setUpdatedAt(node.getUpdatedAt());
        return vo;
    }

    private FileShare checkOwnership(Long shareId) {
        FileShare share = fileShareMapper.selectById(shareId);
        if (share == null) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }
        if (!share.getCreatorId().equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED);
        }
        return share;
    }

    /**
     * S-06 生成 4 位数字字母分享码：SecureRandom 从 32 字符集（排除易混字符 0/O/1/I）抽取；
     * 与 uk_share_code 唯一索引冲突时重新生成，最多重试 8 次，仍冲突抛出业务异常
     * （避免 DuplicateKeyException 直接 500）。
     */
    private String generateShareCode() {
        int length = resolveShareCodeLength();
        for (int attempt = 0; attempt <= SHARE_CODE_MAX_RETRY; attempt++) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(SHARE_CODE_CHARS.charAt(SHARE_CODE_RANDOM.nextInt(SHARE_CODE_CHARS.length())));
            }
            String code = sb.toString();
            Long exists = fileShareMapper.selectCount(
                    new LambdaQueryWrapper<FileShare>().eq(FileShare::getShareCode, code));
            if (exists == null || exists == 0) {
                return code;
            }
        }
        throw new BusinessException(ResultCode.BUSINESS_ERROR, "分享码生成失败，请重试");
    }

    /** 分享码长度从全局配置读取并夹紧到 8~16，避免超长溢出 VARCHAR(32) */
    private int resolveShareCodeLength() {
        int len = sysConfigService.getInt("share.brute_force.shareCodeLength", SHARE_CODE_DEFAULT_LENGTH);
        if (len < SHARE_CODE_MIN_LENGTH) {
            return SHARE_CODE_MIN_LENGTH;
        }
        if (len > SHARE_CODE_MAX_LENGTH) {
            return SHARE_CODE_MAX_LENGTH;
        }
        return len;
    }

    /**
     * S-03 分享子树路径边界判断：节点路径必须等于根路径，或以「根路径 + /」为前缀，
     * 避免同名前缀（如 /a.txt 匹配 /a.txt2、/doc 匹配 /documents）导致的越权访问。
     */
    private boolean isWithinShare(FileNode root, FileNode node) {
        return node.getPath() != null && root.getPath() != null
                && (node.getPath().equals(root.getPath())
                    || node.getPath().startsWith(root.getPath() + "/"));
    }

    // ==================== 权限模型：分享权限集解析/上限校验 ====================

    /** 个人文件默认分享权限：view + download（与设计/验收一致） */
    private static final Set<String> PERSONAL_DEFAULT_SHARE_PERMS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("view", "download")));

    /** 个人文件拥有者对该文件的有效权限：view + download + edit（edit 用于分享在线编辑文档，2026-08-15 新增） */
    private static final Set<String> PERSONAL_EFFECTIVE_PERMS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("view", "download", "edit")));

    /** 分享权限 JSON 序列化（简单键值对象，无需 Spring 注入，便于单元测试） */
    private static final ObjectMapper PERMISSION_OBJECT_MAPPER = new ObjectMapper();

    /**
     * 解析分享权限 JSON：支持 {"view":true,"download":true} 布尔对象或 ["view","download"] 数组；
     * 隐含补全 upload/download → view（与团队权限集语义一致）。
     */
    private Set<String> parsePermissions(String json) {
        if (json == null || json.isBlank()) {
            return new HashSet<>();
        }
        try {
            String trimmed = json.trim();
            Set<String> perms = new LinkedHashSet<>();
            if (trimmed.startsWith("[")) {
                List<String> list = PERMISSION_OBJECT_MAPPER.readValue(
                        trimmed, new TypeReference<List<String>>() { });
                for (String p : list) {
                    if (p != null && !p.isBlank()) {
                        perms.add(p.trim());
                    }
                }
            } else {
                Map<String, Boolean> map = PERMISSION_OBJECT_MAPPER.readValue(
                        trimmed, new TypeReference<Map<String, Boolean>>() { });
                for (Map.Entry<String, Boolean> entry : map.entrySet()) {
                    if (Boolean.TRUE.equals(entry.getValue())) {
                        perms.add(entry.getKey());
                    }
                }
            }
            if (perms.contains("upload") || perms.contains("download") || perms.contains("edit")) {
                perms.add("view");
            }
            return perms;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "permissions 格式错误");
        }
    }

    /** 权限集序列化为 {"view":true,"download":true}（TreeMap 保证键序稳定） */
    private String toPermissionsJson(Set<String> perms) {
        Map<String, Boolean> map = new TreeMap<>();
        for (String p : perms) {
            map.put(p, true);
        }
        try {
            return PERMISSION_OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分享权限序列化失败");
        }
    }

    /** 旧单值 permission → 权限集（与 35_file_share_permissions.sql 历史映射一致） */
    private Set<String> legacyPermissionSet(Integer permission) {
        switch (permission == null ? 0 : permission) {
            case 1:
                return new LinkedHashSet<>(Arrays.asList("view", "download"));
            case 2:
                return new LinkedHashSet<>(Arrays.asList("view", "upload"));
            case 3:
                return new LinkedHashSet<>(Arrays.asList("view", "upload", "download", "delete", "rename", "move", "edit"));
            default:
                return new LinkedHashSet<>(Collections.singletonList("view"));
        }
    }

    /** 权限集 → 兼容旧单值 permission（download→1，upload→2，管理操作→3，仅查看→0） */
    private Integer legacyPermissionFromPerms(Set<String> perms) {
        if (perms.contains("download")) {
            return 1;
        }
        if (perms.contains("upload")) {
            return 2;
        }
        if (perms.contains("delete") || perms.contains("rename") || perms.contains("move")) {
            return 3;
        }
        return 0;
    }

    /**
     * 解析创建分享的权限集：permissions JSON 优先 → permission 单值映射 → 默认=用户有效权限
     * （个人文件默认 view+download，团队文件默认=resolveMyPermissions 结果）
     */
    private Set<String> resolveSharePermissionSet(CreateShareRequest request, Set<String> myPerms, FileNode fileNode) {
        if (request.getPermissions() != null && !request.getPermissions().isBlank()) {
            Set<String> perms = parsePermissions(request.getPermissions());
            if (perms.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "permissions 不能为空");
            }
            return perms;
        }
        if (request.getPermission() != null) {
            return legacyPermissionSet(request.getPermission());
        }
        if (fileNode.getSpaceId() == null || fileNode.getSpaceId() <= 0) {
            return PERSONAL_DEFAULT_SHARE_PERMS;
        }
        return myPerms;
    }

    /** 用户对分享文件的有效权限集（分享上限）：个人文件={view,download}，团队文件=share 前置 + resolveMyPermissions */
    private Set<String> resolveMyEffectivePerms(FileNode fileNode) {
        if (fileNode.getSpaceId() == null || fileNode.getSpaceId() <= 0) {
            // 个人文件：有效权限上限 {view, download}（与 effective-permissions 接口口径一致）
            return PERSONAL_EFFECTIVE_PERMS;
        }
        teamService.requirePermissions(fileNode.getSpaceId(), fileNode.getId(), "share");
        Set<String> perms = teamService.resolveMyPermissions(fileNode.getSpaceId(), fileNode.getId());
        if (perms == null || perms.isEmpty()) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "分享权限不能超过你的权限");
        }
        return perms;
    }

    /** allow_download 联动：权限集含 download → 1，与显式 allowDownload 取交集（两者都允许才 1） */
    private Integer resolveAllowDownload(Set<String> sharePerms, Integer explicitAllowDownload) {
        int computed = sharePerms.contains("download") ? 1 : 0;
        if (explicitAllowDownload != null) {
            return (computed == 1 && explicitAllowDownload == 1) ? 1 : 0;
        }
        return computed;
    }

    /** 分享是否允许下载：权限集含 download；旧数据（permissions 为空）按单值 permission>=1 兼容 */
    private boolean shareAllowsDownload(FileShare share) {
        Set<String> perms = parsePermissions(share.getPermissions());
        if (!perms.isEmpty()) {
            return perms.contains("download");
        }
        return share.getPermission() != null && share.getPermission() >= 1;
    }

    @Override
    @Transactional
    public Result<SaveShareVO> saveShare(SaveShareRequest request) {
        // 1. 校验分享访问（匿名可访问：提取码/验证码由 validateShareAccess 处理）
        FileShare share = validateShareAccess(request.getShareCode(), request.getPassword(),
                request.getCaptchaId(), request.getCaptchaCode());

        // 2. 保存本质是“下载并复制到我的云盘”，要求分享具备下载权限
        if (share.getAllowDownload() == null || share.getAllowDownload() == 0 || !shareAllowsDownload(share)) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "该分享不可下载，无法保存到云盘");
        }

        // 3. 分享根节点必须存在且可访问
        FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
        if (root == null || root.getStatus() != NodeStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }

        // 4. 保存目标必须是当前用户的个人云盘目录（不允许保存到他人/团队空间）
        Long currentUserId = UserContext.getUserId();
        if (currentUserId == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请先登录后再保存");
        }
        Long effectiveParentId = request.getTargetParentId() == null ? 0L : request.getTargetParentId();
        if (effectiveParentId != 0L) {
            FileNode parent = fileNodeMapper.selectById(effectiveParentId);
            if (parent == null || !parent.isFolder()
                    || (parent.getSpaceId() != null && parent.getSpaceId() > 0)
                    || !parent.getOwnerId().equals(currentUserId)) {
                throw new BusinessException(ResultCode.FORBIDDEN, "保存目标文件夹无效");
            }
        }
        String targetPath = fileService.validateAndGetParentPath(effectiveParentId);

        // 5. 递归保存分享边界内内容（仅从分享根节点向下遍历，绝不越界保存分享外文件）
        Long currentTenantId = UserContext.getTenantId();
        SaveShareVO vo = new SaveShareVO();
        List<Long> nodeIds = new ArrayList<>();
        vo.setSourceName(root.getName());
        vo.setNodeIds(nodeIds);

        FileNode rootCopy = saveShareNodeRecursive(root, effectiveParentId, targetPath,
                currentUserId, currentTenantId, vo);
        vo.setRootNodeId(rootCopy.getId());
        vo.setSavedCount(nodeIds.size());
        log.info("用户{}保存分享到云盘: shareCode={}, targetParentId={}, savedCount={}",
                currentUserId, request.getShareCode(), effectiveParentId, nodeIds.size());
        return Result.success(vo);
    }

    /**
     * 递归在目标目录下生成分享节点的副本：文件夹递归，文件按 MD5 去重复用物理对象。
     * <p>
     * 只沿分享根节点的 parentId 子链向下遍历，任何分享边界外的节点都不会被纳入。
     */
    private FileNode saveShareNodeRecursive(FileNode source, Long targetParentId, String targetPath,
                                            Long currentUserId, Long currentTenantId, SaveShareVO vo) {
        String newName = fileService.resolveNameConflict(targetParentId, source.getName());
        String newPath = targetPath == null || targetPath.isEmpty()
                ? "/" + newName
                : targetPath + "/" + newName;

        FileNode copy = new FileNode();
        copy.setParentId(targetParentId);
        copy.setNodeType(source.getNodeType());
        copy.setName(newName);
        copy.setPath(newPath);
        copy.setFileSize(source.getFileSize());
        copy.setFileMd5(source.getFileMd5());
        copy.setContentType(source.getContentType());
        copy.setSuffix(source.getSuffix());
        copy.setStoragePath(source.getStoragePath());
        copy.setStatus(NodeStatus.NORMAL.getCode());
        copy.setUploadStatus(UploadStatus.COMPLETED.getCode());
        copy.setOwnerId(currentUserId);
        copy.setUploaderId(currentUserId);
        copy.setRefCount(REF_COUNT_INITIAL);
        copy.setVersion(0);
        copy.setThumbnailPath(source.getThumbnailPath());
        // 保存到个人云盘：清空团队空间标识
        copy.setSpaceId(null);

        // 文件复制：按 MD5 去重复用物理对象（引用计数由 file_object 管理；S3 无网络调用）
        if (source.isFile() && source.getFileMd5() != null && source.getObjectId() != null) {
            Long tenantId = source.getTenantId() != null ? source.getTenantId() : currentTenantId;
            long size = source.getFileSize() == null ? 0 : source.getFileSize();
            FileObject object = fileObjectService.acquire(tenantId, source.getFileMd5(), size,
                    () -> source.getStoragePath());
            if (object != null) {
                copy.setObjectId(object.getId());
                copy.setStoragePath(object.getStoragePath());
            }
        }
        fileNodeMapper.insert(copy);

        if (source.isFile() && source.getFileMd5() != null) {
            long size = source.getFileSize() == null ? 0 : source.getFileSize();
            checkUserQuota(currentUserId, size);
            cloudStorageService.checkCapacity(size);
            // 旧数据（无 object_id）回退按 md5 重算引用
            if (copy.getObjectId() == null) {
                fileService.incrementRefCount(source.getFileMd5());
            }
            // 原子扣减：并发超配额时 update 返回 0，抛异常回滚本次保存
            if (size > 0 && userQuotaMapper.updateStorageUsed(currentUserId, size) <= 0) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
            reliableEventPublisher.publishFileIndex(copy, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(copy, SyncChangeEvent.ChangeType.CREATE);
        } else if (source.isFolder()) {
            reliableEventPublisher.publishFileIndex(copy, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(copy, SyncChangeEvent.ChangeType.CREATE);
            LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getParentId, source.getId())
                    .eq(FileNode::getStatus, NodeStatus.NORMAL.getCode());
            List<FileNode> children = fileNodeMapper.selectList(wrapper);
            for (FileNode child : children) {
                saveShareNodeRecursive(child, copy.getId(), newPath, currentUserId, currentTenantId, vo);
            }
        }
        vo.getNodeIds().add(copy.getId());
        return copy;
    }

    /** 校验个人存储配额（与 FileServiceImpl 同口径，避免复制逻辑分散） */
    private void checkUserQuota(Long userId, long fileSize) {
        StorageInfoVO quota = userQuotaMapper.getUserQuota(userId);
        if (quota != null && quota.getQuota() != null && quota.getQuota() > 0) {
            long used = quota.getUsed() == null ? 0 : quota.getUsed();
            if (used + fileSize > quota.getQuota()) {
                throw new BusinessException(ResultCode.STORAGE_QUOTA_EXCEEDED);
            }
        }
    }

    private ShareVO toVO(FileShare share, String fileName) {
        ShareVO vo = new ShareVO();
        vo.setId(share.getId());
        vo.setShareCode(share.getShareCode());
        vo.setFileNodeId(share.getFileNodeId());
        vo.setFileName(fileName);
        vo.setShareType(share.getShareType());
        vo.setPassword(share.getPassword());
        vo.setExpireAt(share.getExpireAt());
        vo.setPermission(share.getPermission());
        vo.setPermissions(share.getPermissions());
        vo.setAllowDownload(share.getAllowDownload());
        vo.setDownloadLimit(share.getDownloadLimit());
        vo.setDownloadCount(share.getDownloadCount());
        vo.setViewCount(share.getViewCount());
        vo.setStatus(share.getStatus());
        vo.setCreatedAt(share.getCreatedAt());
        return vo;
    }
}
