package com.stcloud.share.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.DownloadService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.dto.*;
import com.stcloud.share.entity.FileShare;
import com.stcloud.share.mapper.FileShareMapper;
import com.stcloud.share.service.ShareService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShareServiceImpl implements ShareService {

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource
    private FileNodeMapper fileNodeMapper;

    @Resource
    private DownloadService downloadService;

    @Resource
    private FileService fileService;

    @Resource
    private StorageService storageService;

    @Override
    @Transactional
    public Result<ShareVO> createShare(CreateShareRequest request) {
        FileNode fileNode = fileNodeMapper.selectById(request.getFileNodeId());
        if (fileNode == null || fileNode.getStatus() != 0) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(request.getFileNodeId());

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
        share.setExpireAt(request.getExpireAt());
        share.setPermission(request.getPermission() != null ? request.getPermission() : 0);
        share.setDownloadLimit(request.getDownloadLimit());
        share.setDownloadCount(0);
        share.setViewCount(0);
        share.setStatus(1);
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
    @Transactional
    public Result<Void> cancelShare(Long shareId) {
        FileShare share = checkOwnership(shareId);
        share.setStatus(0);
        fileShareMapper.updateById(share);
        log.info("用户{}取消分享: shareId={}", UserContext.getUserId(), shareId);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> updateShare(Long shareId, UpdateShareRequest request) {
        FileShare share = checkOwnership(shareId);

        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FileShare::getId, shareId);
        if (request.getShareType() != null) {
            wrapper.set(FileShare::getShareType, request.getShareType());
        }
        if (request.getPassword() != null) {
            wrapper.set(FileShare::getPassword, request.getPassword());
        }
        if (request.getExpireAt() != null) {
            wrapper.set(FileShare::getExpireAt, request.getExpireAt());
        }
        if (request.getPermission() != null) {
            wrapper.set(FileShare::getPermission, request.getPermission());
        }
        if (request.getDownloadLimit() != null) {
            wrapper.set(FileShare::getDownloadLimit, request.getDownloadLimit());
        }
        if (request.getStatus() != null) {
            wrapper.set(FileShare::getStatus, request.getStatus());
        }
        fileShareMapper.update(null, wrapper);
        log.info("用户{}修改分享: shareId={}", UserContext.getUserId(), shareId);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<ShareAccessVO> accessShare(ShareAccessRequest request) {
        FileShare share = validateShareAccess(request.getShareCode(), request.getPassword());

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
        vo.setIsExpired(false);
        vo.setFileNodeId(share.getFileNodeId());
        vo.setShareType(share.getShareType());
        return Result.success(vo);
    }

    @Override
    @Transactional
    public Result<String> getDownloadUrl(String shareCode, Long nodeId, String password) {
        FileShare share = validateShareAccess(shareCode, password);

        if (share.getDownloadLimit() != null && share.getDownloadCount() >= share.getDownloadLimit()) {
            throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "下载次数已达上限");
        }

        Long targetNodeId = nodeId != null ? nodeId : share.getFileNodeId();
        FileNode targetNode = fileNodeMapper.selectById(targetNodeId);
        if (targetNode == null || targetNode.getStatus() != 0) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(targetNodeId);
        // 子文件下载时校验归属
        if (nodeId != null) {
            FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
            if (root == null || targetNode.getPath() == null
                    || !targetNode.getPath().startsWith(root.getPath())) {
                throw new BusinessException(ResultCode.SHARE_ACCESS_DENIED, "无权访问该文件");
            }
        }

        // 增加下载次数
        fileShareMapper.update(null, new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getId, share.getId())
                .setSql("download_count = download_count + 1"));

        String url = downloadService.generateDownloadUrl(targetNodeId);
        return Result.success(url);
    }

    @Override
    public Result<List<FileNodeVO>> listShareFiles(String shareCode, Long parentId, String password) {
        FileShare share = validateShareAccess(shareCode, password);

        FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
        if (root == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        if (root.getNodeType() != 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只能浏览文件夹分享");
        }

        Long queryParentId = parentId != null ? parentId : share.getFileNodeId();
        fileService.validateAccessible(queryParentId);

        // 子目录归属校验：parentId 节点的 path 必须以根节点 path 为前缀
        if (parentId != null && !parentId.equals(share.getFileNodeId())) {
            FileNode parent = fileNodeMapper.selectById(parentId);
            if (parent == null || parent.getStatus() != 0) {
                throw new BusinessException(ResultCode.FILE_NOT_FOUND);
            }
            if (parent.getPath() == null || !parent.getPath().startsWith(root.getPath())) {
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
    public void streamShareFile(String shareCode, Long nodeId, String password, HttpServletResponse response) {
        // 分享码+提取码验证即为认证，无需用户登录 token
        FileShare share = validateShareAccess(shareCode, password);

        Long targetNodeId = nodeId != null ? nodeId : share.getFileNodeId();
        FileNode targetNode = fileNodeMapper.selectById(targetNodeId);
        if (targetNode == null || targetNode.getStatus() != 0 || targetNode.getNodeType() != 1) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        fileService.validateAccessible(targetNodeId);
        // 子文件流式预览校验归属：path 必须以根节点 path 为前缀
        if (nodeId != null) {
            FileNode root = fileNodeMapper.selectById(share.getFileNodeId());
            if (root == null || targetNode.getPath() == null
                    || !targetNode.getPath().startsWith(root.getPath())) {
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

        try (InputStream is = storageService.downloadObject(targetNode.getStoragePath());
             OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } catch (IOException e) {
            log.warn("分享文件流式传输中断: shareCode={}, nodeId={}, err={}",
                    shareCode, targetNodeId, e.getMessage());
        }
    }

    private FileShare validateShareAccess(String shareCode, String password) {
        FileShare share = fileShareMapper.selectOne(
                new LambdaQueryWrapper<FileShare>().eq(FileShare::getShareCode, shareCode));
        if (share == null || share.getStatus() == 0) {
            throw new BusinessException(ResultCode.SHARE_NOT_FOUND);
        }
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))) {
            throw new BusinessException(ResultCode.SHARE_EXPIRED);
        }
        if (share.getShareType() == 1) {
            if (password == null || !password.equals(share.getPassword())) {
                throw new BusinessException(ResultCode.SHARE_PASSWORD_ERROR);
            }
        }
        return share;
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

    private String generateShareCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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
        vo.setDownloadLimit(share.getDownloadLimit());
        vo.setDownloadCount(share.getDownloadCount());
        vo.setViewCount(share.getViewCount());
        vo.setStatus(share.getStatus());
        vo.setCreatedAt(share.getCreatedAt());
        return vo;
    }
}
