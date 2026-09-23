package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.dto.FileWatchStateVO;
import com.stcloud.team.dto.FileWatchVO;
import com.stcloud.team.entity.FileWatch;
import com.stcloud.team.mapper.FileWatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 关注订阅服务；所有查询都显式限定当前 tenant/user，不能由请求参数切换主体。 */
@Service
@RequiredArgsConstructor
public class FileWatchServiceImpl implements FileWatchService {

    private final FileWatchMapper fileWatchMapper;
    private final FileWatchAccessService accessService;

    @Override
    public Result<FileWatchStateVO> state(Long nodeId) {
        Principal principal = principal();
        validateNodeId(nodeId);
        FileNode node = accessService.findNode(principal.tenantId(), nodeId);
        // 状态接口不向无权主体确认订阅存在；取消接口仍可无条件清理该主体自己的记录。
        if (node == null || !accessService.canViewCurrent(principal.tenantId(), principal.userId(), node)) {
            return Result.success(new FileWatchStateVO(nodeId, false, null));
        }
        FileWatch watch = find(principal, nodeId);
        return Result.success(toState(nodeId, watch));
    }

    @Override
    @Transactional
    public Result<FileWatchStateVO> watch(Long nodeId) {
        Principal principal = principal();
        validateNodeId(nodeId);
        FileNode node = accessService.findNode(principal.tenantId(), nodeId);
        if (node == null || !accessService.canViewCurrent(principal.tenantId(), principal.userId(), node)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        // 直接尝试唯一键 INSERT，避免“先查不存在再加 gap lock”在 MySQL 并发 PUT 下形成死锁。
        // 竞争事务中只有一个 INSERT 成功；失败方完成唯一键冲突后再做当前读，返回已提交的同一订阅代际。
        FileWatch created = new FileWatch();
        created.setTenantId(principal.tenantId());
        created.setUserId(principal.userId());
        created.setNodeId(nodeId);
        created.setCreatedAt(LocalDateTime.now());
        try {
            fileWatchMapper.insert(created);
        } catch (DuplicateKeyException duplicate) {
            // 当前读不会复用 RR 旧快照，保证关注操作幂等且不产生第二条订阅。
            created = fileWatchMapper.selectForUpdate(principal.tenantId(), principal.userId(), nodeId);
            if (created == null) {
                throw duplicate;
            }
        }
        return Result.success(toState(nodeId, created));
    }

    @Override
    @Transactional
    public Result<Void> unwatch(Long nodeId) {
        Principal principal = principal();
        validateNodeId(nodeId);
        fileWatchMapper.delete(new LambdaQueryWrapper<FileWatch>()
                .eq(FileWatch::getTenantId, principal.tenantId())
                .eq(FileWatch::getUserId, principal.userId())
                .eq(FileWatch::getNodeId, nodeId));
        return Result.success();
    }

    @Override
    public Result<IPage<FileWatchVO>> list(int page, int size) {
        Principal principal = principal();
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数不合法");
        }
        Page<FileWatch> pageParam = new Page<>(page, size);
        IPage<FileWatch> watchPage = fileWatchMapper.selectPage(pageParam,
                new LambdaQueryWrapper<FileWatch>()
                        .eq(FileWatch::getTenantId, principal.tenantId())
                        .eq(FileWatch::getUserId, principal.userId())
                        .orderByDesc(FileWatch::getCreatedAt)
                        .orderByDesc(FileWatch::getId));
        IPage<FileWatchVO> result = watchPage.convert(watch -> toVO(principal, watch));
        return Result.success(result);
    }

    private FileWatchVO toVO(Principal principal, FileWatch watch) {
        FileWatchVO vo = new FileWatchVO();
        vo.setWatchId(watch.getId());
        vo.setNodeId(watch.getNodeId());
        vo.setCreatedAt(watch.getCreatedAt());
        FileNode node = accessService.findNode(principal.tenantId(), watch.getNodeId());
        if (node == null || !accessService.canViewCurrent(principal.tenantId(), principal.userId(), node)) {
            // 失效记录只保留 nodeId/watchId，不能把历史路径或空间 ID 继续暴露给列表客户端。
            vo.setAvailable(false);
            return vo;
        }
        vo.setAvailable(true);
        vo.setNodeType(node.getNodeType());
        vo.setSpaceId(node.getSpaceId());
        vo.setParentId(node.getParentId());
        vo.setName(node.getName());
        vo.setPath(node.getPath());
        return vo;
    }

    private FileWatchStateVO toState(Long nodeId, FileWatch watch) {
        return new FileWatchStateVO(nodeId, watch != null, watch == null ? null : watch.getId());
    }

    private FileWatch find(Principal principal, Long nodeId) {
        return fileWatchMapper.selectOne(new LambdaQueryWrapper<FileWatch>()
                .eq(FileWatch::getTenantId, principal.tenantId())
                .eq(FileWatch::getUserId, principal.userId())
                .eq(FileWatch::getNodeId, nodeId)
                .last("LIMIT 1"));
    }

    private Principal principal() {
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.getTenantId();
        if (userId == null || tenantId == null || userId <= 0 || tenantId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return new Principal(tenantId, userId);
    }

    private void validateNodeId(Long nodeId) {
        if (nodeId == null || nodeId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "nodeId 必须为正整数");
        }
    }

    private record Principal(Long tenantId, Long userId) {
    }
}
