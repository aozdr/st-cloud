package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.core.dto.RecycleItemVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecycleBinServiceImpl implements RecycleBinService {

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private UserQuotaMapper userQuotaMapper;
    @Resource
    private com.stcloud.core.mapper.TeamStorageMapper teamStorageMapper;
    @Resource
    private FileService fileService;
    @Resource
    private StorageService storageService;
    @Resource
    private ApplicationEventPublisher eventPublisher;

    private static final int RETENTION_DAYS = 30;

    @Override
    public List<RecycleItemVO> listRecycleBin() {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getStatus, NodeStatus.RECYCLED.getCode())
                .eq(FileNode::getOwnerId, userId)
                .orderByDesc(FileNode::getUpdatedAt);

        List<FileNode> nodes = fileNodeMapper.selectList(wrapper);
        LocalDateTime now = LocalDateTime.now();
        return nodes.stream().map(node -> {
            RecycleItemVO vo = new RecycleItemVO();
            vo.setId(node.getId());
            vo.setName(node.getName());
            vo.setNodeType(node.getNodeType());
            vo.setPath(node.getPath());
            vo.setFileSize(node.getFileSize());
            vo.setUpdatedAt(node.getUpdatedAt());
            long daysElapsed = ChronoUnit.DAYS.between(node.getUpdatedAt(), now);
            vo.setRemainingDays(Math.max(0, RETENTION_DAYS - (int) daysElapsed));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void restore(List<Long> nodeIds) {
        for (Long nodeId : nodeIds) {
            FileNode node = fileService.getNodeByIdAndOwner(nodeId);
            if (node.getStatus() != NodeStatus.RECYCLED.getCode()) {
                continue;
            }

            // 检查父目录
            if (node.getParentId() != 0) {
                FileNode parent = fileNodeMapper.selectById(node.getParentId());
                if (parent == null || parent.getStatus() != NodeStatus.NORMAL.getCode()) {
                    node.setParentId(0L);
                    node.setPath("/" + node.getName());
                }
            }

            // 重名处理
            String name = node.getName();
            if (fileNodeMapper.countByParentAndName(node.getParentId(), name) > 0) {
                name = fileService.resolveNameConflict(node.getParentId(), name);
                node.setName(name);
                node.setPath(node.getParentId() == 0 ? "/" + name : "/" + name);
            }

            node.setStatus(NodeStatus.NORMAL.getCode());
            fileNodeMapper.updateById(node);

            // 查询待恢复的子节点（更新前查询，以便后续发 INDEX 事件）
            LambdaQueryWrapper<FileNode> childQuery = new LambdaQueryWrapper<FileNode>()
                    .likeRight(FileNode::getPath, node.getPath() + "/")
                    .eq(FileNode::getStatus, NodeStatus.RECYCLED.getCode());
            List<FileNode> children = fileNodeMapper.selectList(childQuery);

            // 恢复子节点
            LambdaUpdateWrapper<FileNode> childUpdate = new LambdaUpdateWrapper<FileNode>()
                    .likeRight(FileNode::getPath, node.getPath() + "/")
                    .eq(FileNode::getStatus, NodeStatus.RECYCLED.getCode())
                    .set(FileNode::getStatus, NodeStatus.NORMAL.getCode());
            fileNodeMapper.update(null, childUpdate);

            // 发布 INDEX 事件：恢复的节点和子节点都需要重新索引到 ES
            eventPublisher.publishEvent(new FileIndexEvent(this, node, FileIndexEvent.ActionType.INDEX));
            for (FileNode child : children) {
                child.setStatus(NodeStatus.NORMAL.getCode());
                eventPublisher.publishEvent(new FileIndexEvent(this, child, FileIndexEvent.ActionType.INDEX));
            }
        }
    }

    @Override
    @Transactional
    public void permanentDelete(List<Long> nodeIds) {
        for (Long nodeId : nodeIds) {
            FileNode node = fileService.getNodeByIdAndOwner(nodeId);
            permanentDeleteNodeAndChildren(node);
        }
    }

    private void permanentDeleteNodeAndChildren(FileNode node) {
        if (node.isFolder()) {
            LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                    .eq(FileNode::getParentId, node.getId());
            List<FileNode> children = fileNodeMapper.selectList(wrapper);
            for (FileNode child : children) {
                permanentDeleteNodeAndChildren(child);
            }
        } else {
            // 仅当没有其他节点引用同一 S3 物理对象时，才真正删除底层存储
            if (node.getStoragePath() != null
                    && fileNodeMapper.countOtherRefsByStoragePath(node.getStoragePath(), node.getId()) == 0) {
                storageService.deleteObject(node.getStoragePath());
            }
            // 按归属退还配额：团队文件退团队空间，个人文件退用户
            if (node.getFileSize() != null && node.getFileSize() > 0) {
                if (node.getSpaceId() != null && node.getSpaceId() > 0) {
                    teamStorageMapper.updateTeamStorageUsed(node.getSpaceId(), -node.getFileSize());
                } else {
                    userQuotaMapper.updateStorageUsed(node.getOwnerId(), -node.getFileSize());
                }
            }
        }
        // 删除 ES 索引
        eventPublisher.publishEvent(new FileIndexEvent(this, node, FileIndexEvent.ActionType.DELETE));
        fileNodeMapper.deleteById(node.getId());
        // 同步剩余同 MD5 节点的引用计数，保持 ref_count 与实际引用数一致
        if (node.isFile() && node.getFileMd5() != null) {
            fileNodeMapper.syncRefCountByMd5(node.getFileMd5());
        }
    }

    @Override
    @Transactional
    public void emptyRecycleBin() {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getStatus, NodeStatus.RECYCLED.getCode())
                .eq(FileNode::getOwnerId, userId);
        List<FileNode> nodes = fileNodeMapper.selectList(wrapper);
        for (FileNode node : nodes) {
            permanentDeleteNodeAndChildren(node);
        }
    }
}
