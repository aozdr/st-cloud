package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.team.dto.*;
import com.stcloud.team.dto.FolderPermissionVO;
import com.stcloud.team.dto.FolderPermissionRequest;
import com.stcloud.team.dto.CommentRequest;
import com.stcloud.team.dto.TeamCommentVO;
import com.stcloud.team.dto.TeamRoleVO;
import com.stcloud.team.dto.TeamRoleRequest;
import com.stcloud.team.dto.TeamStatsVO;
import com.stcloud.team.dto.ExternalMemberRequest;

public interface TeamService {

    Result<TeamSpaceVO> createSpace(CreateSpaceRequest request);

    

    Result<TeamSpaceVO> getSpace(Long spaceId);

    Result<Void> updateSpace(Long spaceId, CreateSpaceRequest request);

    Result<Void> deleteSpace(Long spaceId);

    Result<TeamMemberVO> inviteMember(Long spaceId, InviteMemberRequest request);

    /** 搜索可邀请的用户（排除已是成员的），按用户名/昵称模糊匹配 */
    Result<java.util.List<UserSearchVO>> searchUsers(Long spaceId, String keyword);

    Result<IPage<TeamMemberVO>> listMembers(Long spaceId, int page, int size, String sortBy);

    Result<Void> updateMemberRole(Long spaceId, Long memberId, Integer role);

    Result<Void> removeMember(Long spaceId, Long memberId);

    /**
     * 检查用户在空间中的权限，返回角色(0-管理员 1-编辑者 2-查看者)，无权限抛异常
     */
    Integer checkPermission(Long spaceId, Integer minRole);

    // ==================== 权限模型重设计：权限点校验（TASK-PERM-BE1） ====================

    /**
     * 校验当前用户对节点的权限点（并集语义，管理员直通）：
     * 成员校验（非成员拒绝）→ 管理员直通（roleId==0 或权限集含 manage_settings）→
     * resolvePermissions 并集 → 校验包含全部 perms，否则 TEAM_PERMISSION_DENIED。
     * 团队文件操作按权限点校验：下载→download、上传→upload、删除→delete、重命名→rename、移动→move。
     */
    void requirePermissions(Long spaceId, Long nodeId, String... perms);

    /**
     * 当前用户对该节点有效权限点集合（st-share 分享上限校验用，管理员返回全部权限点）。
     */
    java.util.Set<String> resolveMyPermissions(Long spaceId, Long nodeId);

    // ==================== P0 新增：邀请链接 ====================

    /** 生成邀请链接 */
    Result<TeamInviteVO> createInvite(Long spaceId, CreateInviteRequest request);

    /** 邀请链接列表 */
    Result<IPage<TeamInviteVO>> listInvites(Long spaceId, int page, int size);

    /** 撤销邀请链接 */
    Result<Void> revokeInvite(Long spaceId, Long inviteId);

    /** 通过邀请码加入空间 */
    Result<Long> joinByCode(String inviteCode);

    // ==================== P0 新增：退出与移交 ====================

    /** 成员退出空间 */
    Result<Void> leaveSpace(Long spaceId);

    /** 移交空间所有权 */
    Result<Void> transferOwnership(Long spaceId, Long targetMemberId);

    // ==================== P0 新增：活动日志 ====================

    /** 空间活动日志 */
    Result<IPage<TeamActivityVO>> listActivities(Long spaceId, String filter, int page, int size);

    /** 上报文件上传活动（前端回调） */
    Result<Void> reportFileActivity(Long spaceId, String action, Long targetId, String targetName);

    // ==================== P1 新增：文件夹权限 ====================

    /** 校验节点级权限（重载，支持文件夹权限链） */
    Integer checkPermission(Long spaceId, Long nodeId, Integer minPermission);

    /** 获取文件夹权限规则列表 */
    Result<java.util.List<FolderPermissionVO>> getFolderPermissions(Long spaceId, Long folderNodeId);

    /** 设置文件夹权限规则 */
    Result<Void> setFolderPermissions(Long spaceId, Long folderNodeId, FolderPermissionRequest request);

    // ==================== P1 新增：文件评论 ====================

    /** 文件评论列表 */
    Result<java.util.List<TeamCommentVO>> listComments(Long spaceId, Long nodeId);

    /** 发表评论 */
    Result<TeamCommentVO> addComment(Long spaceId, CommentRequest request);

    /** 编辑评论 */
    Result<Void> updateComment(Long spaceId, Long commentId, String content);

    /** 删除评论 */
    Result<Void> deleteComment(Long spaceId, Long commentId);

    // ==================== P1 新增：空间搜索排序 ====================

    /** 空间列表（支持搜索/排序） */
    Result<IPage<TeamSpaceVO>> listSpaces(int page, int size, String keyword, String sortBy);

    /** 切换空间置顶 */
    Result<Void> togglePin(Long spaceId);

    /** 检查节点是否被锁定（被锁定的节点不允许修改/删除/移动） */
    void checkNotLocked(Long nodeId);

    // ==================== P2 新增：文件锁定 ====================

    /** 锁定文件 */
    Result<Void> lockFile(Long spaceId, Long nodeId, Integer hours);

    /** 解锁文件 */
    Result<Void> unlockFile(Long spaceId, Long nodeId);

    // ==================== P2 新增：自定义角色 ====================

    /** 角色列表 */
    Result<java.util.List<TeamRoleVO>> listRoles(Long spaceId);

    /** 创建角色 */
    Result<TeamRoleVO> createRole(Long spaceId, TeamRoleRequest request);

    /** 编辑角色 */
    Result<Void> updateRole(Long spaceId, Long roleId, TeamRoleRequest request);

    /** 删除角色 */
    Result<Void> deleteRole(Long spaceId, Long roleId);

    // ==================== P2 新增：外部协作者 ====================

    /** 设置成员外部标记 */
    Result<Void> setExternalMember(Long spaceId, Long memberId, ExternalMemberRequest request);

    /** 获取外部协作配置 */
    Result<Integer> getExternalConfig(Long spaceId);

    /** 设置外部协作配置 */
    Result<Void> setExternalConfig(Long spaceId, boolean allow);

    // ==================== P2 新增：空间统计 ====================

    /** 空间统计 */
    Result<TeamStatsVO> getStats(Long spaceId, int days);
}
