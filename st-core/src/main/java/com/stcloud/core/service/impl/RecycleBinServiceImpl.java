package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.core.dto.RecycleItemVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.FileIndexEvent;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.event.SyncChangeEvent;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.RecycleBinService;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
    private FileObjectService fileObjectService;
    @Resource
    private ReliableEventPublisher reliableEventPublisher;

    private static final int RETENTION_DAYS = 30;

    @Override
    public List<RecycleItemVO> listRecycleBin() {
        Long userId = UserContext.getUserId();
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getStatus, NodeStatus.RECYCLED.getCode())
                .eq(!UserContext.canAccessTenant(), FileNode::getOwnerId, userId)
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

            String oldPath = node.getPath();
            Long targetParentId = node.getParentId() == null ? 0L : node.getParentId();
            String parentPath = "";

            // 父目录校验：父不存在或非正常态则回归根目录
            if (targetParentId != 0L) {
                FileNode parent = fileNodeMapper.selectById(targetParentId);
                if (parent == null || parent.getStatus() != NodeStatus.NORMAL.getCode()) {
                    targetParentId = 0L;
                } else {
                    parentPath = parent.getPath();
                }
            }

            // 重名冲突处理
            String name = node.getName();
            if (fileNodeMapper.countByParentAndName(targetParentId, name) > 0) {
                name = fileService.resolveNameConflict(targetParentId, name);
            }

            // 计算恢复后路径（id 不变，重命名/回归根仅改变 path 与 name）
            String targetPath = targetParentId == 0L ? "/" + name : parentPath + "/" + name;

            node.setParentId(targetParentId);
            node.setName(name);
            node.setPath(targetPath);
            node.setStatus(NodeStatus.NORMAL.getCode());
            node.setUpdatedAt(LocalDateTime.now());
            fileNodeMapper.updateById(node);
            // 恢复后重新可访问：失效可访问性缓存，避免残留的不可访问判定
            fileService.invalidateAccessible(nodeId);

            // 路径变更时同步子孙 path（状态判定已改为祖先链校验）
            if (!oldPath.equals(targetPath) && node.isFolder()) {
                fileNodeMapper.updateChildrenPath(oldPath, targetPath);
            }

            // ES：重新索引恢复节点及正常态子孙（独立回收的子孙保持不可搜）
            reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.INDEX);
            reliableEventPublisher.publishSyncChange(node, SyncChangeEvent.ChangeType.CREATE);
            // 恢复后子孙可能残留「不可访问」缓存（回收期间被访问过）：级联失效，恢复访问正确性
            for (FileNode descendant : fileService.collectDescendants(nodeId)) {
                fileService.invalidateAccessible(descendant.getId());
                if (descendant.getStatus() == NodeStatus.NORMAL.getCode()) {
                    reliableEventPublisher.publishFileIndex(descendant, FileIndexEvent.ActionType.INDEX);
                    reliableEventPublisher.publishSyncChange(descendant, SyncChangeEvent.ChangeType.CREATE);
                }
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
            // 删除引用：对象引用归零才真正删除 S3 物理对象；旧数据（无 object_id）回退按 storage_path 判重
            if (node.getObjectId() != null) {
                int remaining = fileObjectService.release(node.getObjectId());
                if (remaining <= 0) {
                    fileObjectService.deletePhysical(node.getObjectId());
                }
            } else if (node.getStoragePath() != null
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
        reliableEventPublisher.publishFileIndex(node, FileIndexEvent.ActionType.DELETE);
        fileNodeMapper.deleteById(node.getId());
        // 物理删除后节点不可再访问：失效可访问性缓存
        fileService.invalidateAccessible(node.getId());
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
                .eq(!UserContext.canAccessTenant(), FileNode::getOwnerId, userId);
        List<FileNode> nodes = fileNodeMapper.selectList(wrapper);
        for (FileNode node : nodes) {
            permanentDeleteNodeAndChildren(node);
        }
    }

    @Override
    public List<Long> findExpiredRecycleRoots() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        LambdaQueryWrapper<FileNode> wrapper = new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getStatus, NodeStatus.RECYCLED.getCode())
                .lt(FileNode::getUpdatedAt, cutoff)
                .notInSql(FileNode::getParentId,
                        "SELECT id FROM file_node WHERE status = " + NodeStatus.RECYCLED.getCode()
                                + " AND deleted = 0");
        return fileNodeMapper.selectList(wrapper).stream()
                .map(FileNode::getId)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void purgeNode(Long nodeId) {
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null || node.getStatus() != NodeStatus.RECYCLED.getCode()) {
            // 已被恢复或已清理，跳过
            return;
        }
        permanentDeleteNodeAndChildren(node);
    }

    @Override
    @Transactional
    public void permanentDeleteAdmin(List<Long> nodeIds) {
        for (Long nodeId : nodeIds) {
            FileNode node = fileNodeMapper.selectById(nodeId);
            if (node == null) {
                continue;
            }
            permanentDeleteNodeAndChildren(node);
        }
    }
}
