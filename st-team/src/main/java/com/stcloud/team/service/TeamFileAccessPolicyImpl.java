package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamRole;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.enums.RoleStatus;
import com.stcloud.team.enums.TeamSpaceStatus;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamRoleMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 团队文件显式主体访问策略。
 *
 * <p>该实现不读取 UserContext，也不把管理员身份当作成员关系的旁路；调用方必须显式传入
 * tenant/user/space/node，所有数据库查询同时带租户与空间条件。</p>
 */
@Slf4j
@Service
public class TeamFileAccessPolicyImpl implements TeamFileAccessPolicy {

    private static final int MAX_PARENT_DEPTH = 20;
    private static final int UPLOAD_COMPLETED = 2;

    @Resource
    private TeamSpaceMapper teamSpaceMapper;
    @Resource
    private TeamMemberMapper teamMemberMapper;
    @Resource
    private TeamRoleMapper teamRoleMapper;
    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private FolderPermissionService folderPermissionService;

    @Override
    public boolean isActiveMember(Long tenantId, Long userId, Long spaceId) {
        if (!validId(tenantId) || !validId(userId) || !validId(spaceId)) {
            return false;
        }
        // 明确的无空间/无成员返回 false；数据库等基础设施异常继续向上抛出，交由
        // 搜索返回暂不可用、由异步通知处理器重试，不能把瞬时故障误记为永久失权。
        return findActiveSpace(tenantId, spaceId) != null
                && findActiveMember(tenantId, userId, spaceId) != null;
    }

    @Override
    public boolean canView(Long tenantId, Long userId, Long spaceId, Long nodeId) {
        if (!validId(tenantId) || !validId(userId) || !validId(spaceId) || !validId(nodeId)) {
            return false;
        }
        if (!isActiveMember(tenantId, userId, spaceId)) {
            return false;
        }

        FileNode node = findNode(tenantId, spaceId, nodeId);
        if (node == null) {
            return false;
        }

        // 先完整验证当前节点及全部祖先，再解析权限；任何断链、跨空间、隐藏或回收均拒绝。
        Set<Long> visited = new LinkedHashSet<>();
        Long currentId = nodeId;
        FileNode current = node;
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (current == null || !visited.add(currentId) || !isVisibleNode(current)) {
                return false;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId <= 0) {
                break;
            }
            if (depth == MAX_PARENT_DEPTH - 1) {
                return false;
            }
            currentId = parentId;
            current = findNode(tenantId, spaceId, currentId);
            if (current == null) {
                return false;
            }
        }

        TeamMember member = findActiveMember(tenantId, userId, spaceId);
        if (member == null) {
            return false;
        }
        Set<String> rolePerms = resolveRolePermissions(tenantId, spaceId, member);
        // 管理员直通仍依赖已验证的成员关系，不能绕过 tenant/space/member 检查。
        if ((member.getRole() != null && member.getRole() == 0)
                || rolePerms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS)) {
            return true;
        }

        // 新搜索/通知链路禁止使用共享权限缓存；fresh 入口遇到链异常会返回空集。
        Set<String> effective = folderPermissionService.resolvePermissionsFreshForTenant(
                tenantId, spaceId, nodeId, userId, rolePerms);
        return effective != null && effective.contains(FolderPermissionService.PERM_VIEW);
    }

    @Override
    public ReadContext openReadContext(Long tenantId, Long userId, Long spaceId) {
        if (!validId(tenantId) || !validId(userId) || !validId(spaceId)) {
            return nodeId -> false;
        }
        TeamSpace space = findActiveSpace(tenantId, spaceId);
        TeamMember member = space == null ? null : findActiveMember(tenantId, userId, spaceId);
        if (member == null) {
            return nodeId -> false;
        }
        Set<String> rolePerms = resolveRolePermissions(tenantId, spaceId, member);
        boolean administrator = member.getRole() != null && member.getRole() == 0
                || rolePerms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS);
        FolderPermissionService.FreshPermissionContext permissionContext = administrator ? null
                : folderPermissionService.freshPermissionContext(tenantId, spaceId, userId,
                        member.getRole() == null ? null : member.getRole().longValue(), rolePerms);
        Map<Long, FileNode> nodes = new HashMap<>();
        return nodeId -> {
            if (!validId(nodeId)) return false;
            Set<Long> visited = new LinkedHashSet<>();
            Long currentId = nodeId;
            for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
                if (!visited.add(currentId)) return false;
                FileNode current;
                if (nodes.containsKey(currentId)) {
                    current = nodes.get(currentId);
                } else {
                    current = findNode(tenantId, spaceId, currentId);
                    nodes.put(currentId, current);
                }
                if (current == null || !isVisibleNode(current)) return false;
                Long parentId = current.getParentId();
                if (parentId == null || parentId <= 0) {
                    if (administrator) return true;
                    Set<String> effective = permissionContext.resolve(nodeId);
                    return effective != null && effective.contains(FolderPermissionService.PERM_VIEW);
                }
                if (depth == MAX_PARENT_DEPTH - 1) return false;
                currentId = parentId;
            }
            return false;
        };
    }

    private TeamSpace findActiveSpace(Long tenantId, Long spaceId) {
        TeamSpace space = teamSpaceMapper.selectOne(new LambdaQueryWrapper<TeamSpace>()
                .eq(TeamSpace::getId, spaceId)
                .eq(TeamSpace::getTenantId, tenantId)
                .eq(TeamSpace::getDeleted, 0)
                .eq(TeamSpace::getStatus, TeamSpaceStatus.NORMAL.getCode()));
        if (space == null || !Objects.equals(space.getId(), spaceId)
                || !Objects.equals(space.getTenantId(), tenantId)
                || space.getStatus() == null
                || space.getStatus() != TeamSpaceStatus.NORMAL.getCode()) {
            return null;
        }
        return space;
    }

    private TeamMember findActiveMember(Long tenantId, Long userId, Long spaceId) {
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTenantId, tenantId)
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getDeleted, 0));
        if (member == null || !Objects.equals(member.getTenantId(), tenantId)
                || !Objects.equals(member.getSpaceId(), spaceId)
                || !Objects.equals(member.getUserId(), userId)
                || (member.getDeleted() != null && member.getDeleted() != 0)
                || (member.getExpireAt() != null
                && !member.getExpireAt().isAfter(LocalDateTime.now()))) {
            return null;
        }
        return member;
    }

    private FileNode findNode(Long tenantId, Long spaceId, Long nodeId) {
        FileNode node = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getId, nodeId)
                .eq(FileNode::getTenantId, tenantId)
                .eq(FileNode::getSpaceId, spaceId)
                .eq(FileNode::getDeleted, 0));
        if (node == null || !Objects.equals(node.getId(), nodeId)
                || !Objects.equals(node.getTenantId(), tenantId)
                || !Objects.equals(node.getSpaceId(), spaceId)
                || (node.getDeleted() != null && node.getDeleted() != 0)) {
            return null;
        }
        return node;
    }

    private boolean isVisibleNode(FileNode node) {
        if (node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()
                || (node.getHidden() != null && node.getHidden() != 0)) {
            return false;
        }
        if (node.getNodeType() == null) {
            return false;
        }
        if (node.getNodeType() == NodeType.FILE.getCode()) {
            return node.getUploadStatus() != null && node.getUploadStatus() == UPLOAD_COMPLETED;
        }
        return node.getNodeType() == NodeType.FOLDER.getCode();
    }

    private Set<String> resolveRolePermissions(Long tenantId, Long spaceId, TeamMember member) {
        if (member == null || member.getRole() == null) {
            return FolderPermissionService.VIEWER_PERMISSIONS;
        }
        int role = member.getRole();
        if (role >= 0 && role <= 2) {
            return FolderPermissionService.presetPermissions(role);
        }
        TeamRole customRole = teamRoleMapper.selectOne(new LambdaQueryWrapper<TeamRole>()
                .eq(TeamRole::getId, (long) role)
                .eq(TeamRole::getTenantId, tenantId)
                .eq(TeamRole::getSpaceId, spaceId)
                .eq(TeamRole::getDeleted, 0)
                .eq(TeamRole::getStatus, RoleStatus.ENABLED.getCode()));
        if (customRole == null || !Objects.equals(customRole.getId(), (long) role)
                || !Objects.equals(customRole.getTenantId(), tenantId)
                || !Objects.equals(customRole.getSpaceId(), spaceId)
                || customRole.getStatus() == null
                || customRole.getStatus() != RoleStatus.ENABLED.getCode()) {
            return FolderPermissionService.VIEWER_PERMISSIONS;
        }
        return FolderPermissionService.parsePermissions(customRole.getPermissions());
    }

    private boolean validId(Long value) {
        return value != null && value > 0;
    }
}
