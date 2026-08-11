package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamFolderPermission;
import com.stcloud.team.mapper.TeamFolderPermissionMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件夹权限链计算服务
 * 从当前节点向上遍历 parent_id 至空间根，取最近一条匹配规则
 */
@Slf4j
@Service
public class FolderPermissionService {

    @Resource
    private TeamFolderPermissionMapper teamFolderPermissionMapper;

    @Resource
    private FileNodeMapper fileNodeMapper;

    /**
     * 计算用户对某节点的有效权限
     *
     * @param spaceId   空间ID
     * @param nodeId    文件/文件夹节点ID
     * @param userId    用户ID
     * @param spaceRole 用户的空间级角色（0-管理员 1-编辑者 2-查看者）
     * @return 有效权限：-1-无权限 0-管理 1-编辑 2-查看
     */
    public int resolvePermission(Long spaceId, Long nodeId, Long userId, int spaceRole) {
        if (nodeId == null) return spaceRole;

        Long currentId = nodeId;
        // 向上遍历至空间根，最多 20 层防死循环
        for (int i = 0; i < 20 && currentId != null; i++) {
            // 查当前节点的权限规则
            List<TeamFolderPermission> perms = teamFolderPermissionMapper.selectList(
                    new LambdaQueryWrapper<TeamFolderPermission>()
                            .eq(TeamFolderPermission::getFolderNodeId, currentId));

            // 优先匹配 member 规则（个人权限覆盖角色权限）
            for (TeamFolderPermission p : perms) {
                if ("member".equals(p.getSubjectType()) && p.getSubjectId().equals(userId)) {
                    return p.getPermission();
                }
            }
            // 其次匹配 role 规则
            for (TeamFolderPermission p : perms) {
                if ("role".equals(p.getSubjectType()) && p.getSubjectId() == spaceRole) {
                    return p.getPermission();
                }
            }

            // 向上遍历到父节点
            FileNode node = fileNodeMapper.selectById(currentId);
            if (node == null || node.getParentId() == null) break;
            currentId = node.getParentId();
        }

        // 无覆盖规则，回退空间级角色
        return spaceRole;
    }

    /**
     * 获取文件夹的权限规则列表
     */
    public List<TeamFolderPermission> listPermissions(Long folderNodeId) {
        return teamFolderPermissionMapper.selectList(
                new LambdaQueryWrapper<TeamFolderPermission>()
                        .eq(TeamFolderPermission::getFolderNodeId, folderNodeId));
    }

    /**
     * 设置文件夹权限（先删后建，全量覆盖）
     */
    public void setPermissions(Long spaceId, Long folderNodeId, List<TeamFolderPermission> rules) {
        // 先删除该文件夹的所有权限规则
        teamFolderPermissionMapper.delete(new LambdaQueryWrapper<TeamFolderPermission>()
                .eq(TeamFolderPermission::getFolderNodeId, folderNodeId));
        // 批量插入新规则
        for (TeamFolderPermission rule : rules) {
            rule.setSpaceId(spaceId);
            rule.setFolderNodeId(folderNodeId);
            teamFolderPermissionMapper.insert(rule);
        }
    }
}