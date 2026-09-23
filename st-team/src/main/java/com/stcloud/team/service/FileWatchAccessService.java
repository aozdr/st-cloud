package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.enums.UserStatus;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.common.enums.NodeStatus;
import com.stcloud.common.enums.NodeType;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.enums.UploadStatus;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamRole;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.enums.RoleStatus;
import com.stcloud.team.enums.TeamSpaceStatus;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamRoleMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 文件关注链路的即时授权服务。
 *
 * <p>普通团队节点委托公共显式主体策略；个人节点和删除状态在此执行完整租户/所有者/祖先校验。
 * 删除核权仅允许事件根处于回收态，其余祖先必须正常，且不会向调用方返回节点名称或路径。</p>
 */
@Service
@RequiredArgsConstructor
public class FileWatchAccessService {

    private static final int MAX_PARENT_DEPTH = 20;
    private final FileNodeMapper fileNodeMapper;
    private final SysUserMapper sysUserMapper;
    private final TeamFileAccessPolicy teamFileAccessPolicy;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamRoleMapper teamRoleMapper;
    private final TeamSpaceMapper teamSpaceMapper;
    private final FolderPermissionService folderPermissionService;

    public FileNode findNode(Long tenantId, Long nodeId) {
        if (!validId(tenantId) || !validId(nodeId)) {
            return null;
        }
        return fileNodeMapper.selectByTenantAndId(tenantId, nodeId);
    }

    /**
     * 异步投递前重新确认接收主体仍是同租户的正常用户。
     *
     * <p>队列中的 userId 只代表捕获时的候选接收者，不能代替当前用户记录的租户、逻辑删除和状态校验。
     * 查询异常向上抛出交给投递重试，避免把数据库故障误判成失权。</p>
     */
    public boolean isActiveUser(Long tenantId, Long userId) {
        if (!validId(tenantId) || !validId(userId)) {
            return false;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0)
                .eq(SysUser::getStatus, UserStatus.NORMAL.getCode()));
        return user != null;
    }

    /** 当前可见性核权；所有异常向上抛给投递器重试，调用方不能把 SQL 故障当作抑制。 */
    public boolean canViewCurrent(Long tenantId, Long userId, FileNode node) {
        if (!validId(tenantId) || !validId(userId) || node == null
                || !tenantId.equals(node.getTenantId()) || !validId(node.getId())) {
            return false;
        }
        if (node.getSpaceId() != null && node.getSpaceId() > 0) {
            return teamFileAccessPolicy.canView(tenantId, userId, node.getSpaceId(), node.getId());
        }
        return canViewPersonalCurrent(tenantId, userId, node);
    }

    /**
     * 删除事件专用核权：根节点允许 RECYCLED，不能放宽其它祖先/成员/权限规则。
     * 永久删除或无法从当前数据库复核时返回 false，避免历史 payload 泄漏名称/路径。
     */
    public boolean canViewDeletion(Long tenantId, Long userId, Long nodeId) {
        FileNode root = findNode(tenantId, nodeId);
        if (root == null || root.getStatus() == null
                || root.getStatus() != NodeStatus.RECYCLED.getCode()) {
            return false;
        }
        if (root.getSpaceId() != null && root.getSpaceId() > 0) {
            return canViewTeamDeletion(tenantId, userId, root);
        }
        return canViewPersonalDeletion(tenantId, userId, root);
    }

    /**
     * 删除目录时复核某个直接后代订阅者。删除只改变根节点状态，后代可能仍为 NORMAL；
     * 允许在同一删除子树内按后代自身的当前 ACL 判定是否发送通用不可用提醒，绝不返回根名称/路径。
     */
    public boolean canViewDeletionTarget(Long tenantId, Long userId, Long eventRootId, Long watchNodeId) {
        if (!validId(eventRootId) || !validId(watchNodeId)) {
            return false;
        }
        if (eventRootId.equals(watchNodeId)) {
            return canViewDeletion(tenantId, userId, eventRootId);
        }
        FileNode root = findNode(tenantId, eventRootId);
        FileNode target = findNode(tenantId, watchNodeId);
        if (root == null || target == null
                || root.getStatus() == null || root.getStatus() != NodeStatus.RECYCLED.getCode()
                || !sameScope(root, target)) {
            return false;
        }
        // 事件捕获同时匹配变更根的祖先订阅。祖先订阅不是“删除子树内的后代”，
        // 不能套用后代路径校验；此处复用删除根专用核权，避免删除文件时父目录订阅被错误抑制。
        if (isAncestorOf(tenantId, target, root)) {
            return canViewDeletion(tenantId, userId, eventRootId);
        }
        if (!root.isFolder()) {
            return false;
        }
        if (isTeamNode(root)) {
            return canViewTeamDeletionTarget(tenantId, userId, root, target);
        }
        return canViewPersonalDeletionTarget(tenantId, userId, root, target);
    }

    /**
     * MOVE 的目录根可能因当前 ACL 不可见，但用户直接关注的后代仍可见。
     * 这种场景只允许发送无细节提醒，不能把根名称/路径当作通知内容。
     */
    public boolean canViewMovedDescendant(Long tenantId, Long userId, FileNode movedRoot, FileNode watchedNode) {
        if (!validId(tenantId) || !validId(userId) || movedRoot == null || watchedNode == null
                || !movedRoot.isFolder() || !sameScope(movedRoot, watchedNode)
                || !isDescendantOf(tenantId, movedRoot, watchedNode)) {
            return false;
        }
        // 仍按当前数据库权限核对后代；根不可见本身只影响通知细节，不绕过后代 ACL。
        return canViewCurrent(tenantId, userId, watchedNode);
    }

    private boolean canViewPersonalCurrent(Long tenantId, Long userId, FileNode node) {
        if (!userId.equals(node.getOwnerId()) || isTeamNode(node)) {
            return false;
        }
        FileNode current = node;
        Set<Long> visited = new LinkedHashSet<>();
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (current == null || !visited.add(current.getId()) || !isVisibleNode(current)
                    || !tenantId.equals(current.getTenantId()) || isTeamNode(current)
                    || !userId.equals(current.getOwnerId())) {
                return false;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId <= 0) {
                return true;
            }
            current = findNode(tenantId, parentId);
        }
        return false;
    }

    private boolean canViewPersonalDeletion(Long tenantId, Long userId, FileNode root) {
        if (!userId.equals(root.getOwnerId()) || isTeamNode(root)
                || !isVisibleIdentity(root, true)) {
            return false;
        }
        FileNode current = root;
        Set<Long> visited = new LinkedHashSet<>();
        boolean reachedRoot = false;
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (current == null || !visited.add(current.getId())
                    || !tenantId.equals(current.getTenantId()) || isTeamNode(current)
                    || !userId.equals(current.getOwnerId())) {
                return false;
            }
            if (depth > 0 && !isVisibleNode(current)) {
                return false;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId <= 0) {
                return true;
            }
            current = findNode(tenantId, parentId);
        }
        return false;
    }

    private boolean canViewTeamDeletion(Long tenantId, Long userId, FileNode root) {
        Long spaceId = root.getSpaceId();
        TeamSpace space = teamSpaceMapper.selectOne(new LambdaQueryWrapper<TeamSpace>()
                .eq(TeamSpace::getId, spaceId)
                .eq(TeamSpace::getTenantId, tenantId)
                .eq(TeamSpace::getDeleted, 0)
                .eq(TeamSpace::getStatus, TeamSpaceStatus.NORMAL.getCode()));
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTenantId, tenantId)
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getDeleted, 0));
        if (space == null || member == null || !isActive(member)) {
            return false;
        }

        FileNode current = root;
        Set<Long> visited = new LinkedHashSet<>();
        boolean reachedRoot = false;
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (current == null || !visited.add(current.getId())
                    || !tenantId.equals(current.getTenantId())
                    || !spaceId.equals(current.getSpaceId())) {
                return false;
            }
            // 根允许回收态，祖先必须处于当前可见的正常状态。
            if (depth == 0 ? !isVisibleIdentity(current, true) : !isVisibleNode(current)) {
                return false;
            }
            Long parentId = current.getParentId();
            if (parentId == null || parentId <= 0) {
                reachedRoot = true;
                break;
            }
            current = findNode(tenantId, parentId);
        }

        // 到达安全深度仍未走到根，不能把管理员/角色权限当作祖先链完整而放行。
        if (!reachedRoot) {
            return false;
        }

        Set<String> rolePerms = rolePermissions(tenantId, spaceId, member);
        if ((member.getRole() != null && member.getRole() == 0)
                || rolePerms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS)) {
            return true;
        }
        // resolvePermissionsFreshForTenant 不读取共享缓存，且可处理 RECYCLED 根的 ACL 快照。
        Set<String> effective = folderPermissionService.resolvePermissionsFreshForTenant(
                tenantId, spaceId, root.getId(), userId, rolePerms);
        return effective != null && effective.contains(FolderPermissionService.PERM_VIEW);
    }

    private boolean canViewTeamDeletionTarget(Long tenantId, Long userId, FileNode root, FileNode target) {
        Long spaceId = root.getSpaceId();
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTenantId, tenantId)
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId)
                .eq(TeamMember::getDeleted, 0));
        if (member == null || !isActive(member)) {
            return false;
        }
        if (!isDeletionSubtreePathValid(tenantId, spaceId, root, target, false)) {
            return false;
        }
        Set<String> rolePerms = rolePermissions(tenantId, spaceId, member);
        if ((member.getRole() != null && member.getRole() == 0)
                || rolePerms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS)) {
            return true;
        }
        Set<String> effective = folderPermissionService.resolvePermissionsFreshForTenant(
                tenantId, spaceId, target.getId(), userId, rolePerms);
        return effective != null && effective.contains(FolderPermissionService.PERM_VIEW);
    }

    private boolean canViewPersonalDeletionTarget(Long tenantId, Long userId, FileNode root, FileNode target) {
        if (!userId.equals(root.getOwnerId()) || !userId.equals(target.getOwnerId())
                || !isDeletionSubtreePathValid(tenantId, null, root, target, true)) {
            return false;
        }
        return true;
    }

    /** 校验 target 到删除根及根以上祖先的租户/空间/状态/隐藏/环路边界。 */
    private boolean isDeletionSubtreePathValid(Long tenantId, Long spaceId, FileNode root,
                                               FileNode target, boolean personal) {
        FileNode current = target;
        Set<Long> visited = new LinkedHashSet<>();
        boolean reachedRoot = false;
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (current == null || !visited.add(current.getId())
                    || !tenantId.equals(current.getTenantId())
                    || (personal ? isTeamNode(current) || !Objects.equals(current.getOwnerId(), target.getOwnerId())
                    : !Objects.equals(current.getSpaceId(), spaceId))) {
                return false;
            }
            if (current.getId().equals(root.getId())) {
                if (!isVisibleIdentity(current, true)) {
                    return false;
                }
                reachedRoot = true;
                current = findNode(tenantId, current.getParentId());
                break;
            }
            if (!isVisibleNode(current)) {
                return false;
            }
            Long parentId = current.getParentId();
            if (!validId(parentId)) {
                return false;
            }
            current = findNode(tenantId, parentId);
        }
        if (!reachedRoot) {
            return false;
        }

        // 删除根之上的目录链仍需保持正常，且深度上限耗尽必须拒绝。
        Set<Long> aboveVisited = new LinkedHashSet<>();
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            // 根节点的 parent_id 为 NULL/0 表示已到真实根；正数 parent_id 但查不到记录是断链，必须拒绝。
            if (current == null || !validId(current.getId()) || !aboveVisited.add(current.getId())
                    || !tenantId.equals(current.getTenantId())
                    || (personal ? isTeamNode(current) || !Objects.equals(current.getOwnerId(), target.getOwnerId())
                    : !Objects.equals(current.getSpaceId(), spaceId))
                    || !isVisibleNode(current)) {
                return false;
            }
            Long parentId = current.getParentId();
            if (!validId(parentId)) {
                return true;
            }
            current = findNode(tenantId, parentId);
            if (current == null) {
                return false;
            }
        }
        return false;
    }

    private boolean sameScope(FileNode left, FileNode right) {
        if (left == null || right == null || !Objects.equals(left.getTenantId(), right.getTenantId())) {
            return false;
        }
        if (isTeamNode(left) != isTeamNode(right)) {
            return false;
        }
        return !isTeamNode(left) || Objects.equals(left.getSpaceId(), right.getSpaceId());
    }

    private boolean isAncestorOf(Long tenantId, FileNode ancestor, FileNode node) {
        if (ancestor == null || node == null || !validId(ancestor.getId()) || !validId(node.getId())) {
            return false;
        }
        Set<Long> visited = new LinkedHashSet<>();
        FileNode current = node;
        for (int depth = 0; depth < MAX_PARENT_DEPTH; depth++) {
            if (current == null || !visited.add(current.getId())
                    || !tenantId.equals(current.getTenantId())) {
                return false;
            }
            Long parentId = current.getParentId();
            if (!validId(parentId)) {
                return false;
            }
            if (ancestor.getId().equals(parentId)) {
                return true;
            }
            current = findNode(tenantId, parentId);
        }
        return false;
    }

    private boolean isDescendantOf(Long tenantId, FileNode ancestor, FileNode node) {
        return isAncestorOf(tenantId, ancestor, node);
    }

    private Set<String> rolePermissions(Long tenantId, Long spaceId, TeamMember member) {
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
        return customRole == null ? FolderPermissionService.VIEWER_PERMISSIONS
                : FolderPermissionService.parsePermissions(customRole.getPermissions());
    }

    private boolean isVisibleNode(FileNode node) {
        return isVisibleIdentity(node, false);
    }

    private boolean isVisibleIdentity(FileNode node, boolean deletionRoot) {
        if (node == null || (node.getHidden() != null && node.getHidden() != 0)
                || node.getNodeType() == null) {
            return false;
        }
        if (deletionRoot) {
            if (node.getStatus() == null || node.getStatus() != NodeStatus.RECYCLED.getCode()) {
                return false;
            }
        } else if (node.getStatus() == null || node.getStatus() != NodeStatus.NORMAL.getCode()) {
            return false;
        }
        return node.getNodeType() == NodeType.FOLDER.getCode()
                || (node.getNodeType() == NodeType.FILE.getCode()
                && node.getUploadStatus() != null
                && node.getUploadStatus() == UploadStatus.COMPLETED.getCode());
    }

    private boolean isTeamNode(FileNode node) {
        return node.getSpaceId() != null && node.getSpaceId() > 0;
    }

    private boolean isActive(TeamMember member) {
        return member.getExpireAt() == null || member.getExpireAt().isAfter(LocalDateTime.now());
    }

    private boolean validId(Long id) {
        return id != null && id > 0;
    }
}
