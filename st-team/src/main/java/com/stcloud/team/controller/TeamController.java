package com.stcloud.team.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.FileTreeNodeVO;
import com.stcloud.core.dto.MoveRequest;
import com.stcloud.core.service.FileService;
import com.stcloud.team.dto.*;
import com.stcloud.team.dto.FolderPermissionRequest;
import com.stcloud.team.dto.FolderPermissionVO;
import com.stcloud.team.dto.CommentRequest;
import com.stcloud.team.dto.TeamCommentVO;
import com.stcloud.team.dto.TeamRoleVO;
import com.stcloud.team.dto.TeamRoleRequest;
import com.stcloud.team.dto.TeamStatsVO;
import com.stcloud.team.dto.LockRequest;
import com.stcloud.team.dto.ExternalMemberRequest;
import com.stcloud.team.service.TeamService;
import com.stcloud.team.util.ActiveTracker;
import com.stcloud.team.util.TeamActivityHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "团队协作", description = "团队空间管理、成员管理、空间文件操作")
@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TeamController {

    private final TeamService teamService;
    private final FileService fileService;
    private final TeamActivityHelper activityHelper;
    private final ActiveTracker activeTracker;

    // ==================== 空间管理 ====================

    @Operation(summary = "创建团队空间")
    @Auditable(action = "TEAM_CREATE", targetType = "TEAM")
    @PreAuthorize("hasAuthority('team:create') or hasRole('ADMIN')")
    @PostMapping("/space")
    public Result<TeamSpaceVO> createSpace(@Valid @RequestBody CreateSpaceRequest request) {
        return teamService.createSpace(request);
    }

    @Operation(summary = "我的团队空间列表")
    @GetMapping("/spaces")
    public Result<IPage<TeamSpaceVO>> listSpaces(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy) {
        return teamService.listSpaces(page, size, keyword, sortBy);
    }

    @Operation(summary = "空间详情")
    @GetMapping("/{spaceId}")
    public Result<TeamSpaceVO> getSpace(@PathVariable Long spaceId) {
        return teamService.getSpace(spaceId);
    }

    @Operation(summary = "修改空间")
    @Auditable(action = "TEAM_UPDATE", targetType = "TEAM", targetIdParam = "spaceId")
    @PutMapping("/{spaceId}")
    public Result<Void> updateSpace(@PathVariable Long spaceId, @RequestBody CreateSpaceRequest request) {
        return teamService.updateSpace(spaceId, request);
    }

    @Operation(summary = "删除空间")
    @Auditable(action = "TEAM_DELETE", targetType = "TEAM")
    @DeleteMapping("/{spaceId}")
    public Result<Void> deleteSpace(@PathVariable Long spaceId) {
        return teamService.deleteSpace(spaceId);
    }

    // ==================== 成员管理 ====================

    @Operation(summary = "邀请成员")
    @Auditable(action = "TEAM_INVITE", targetType = "TEAM")
    @PreAuthorize("hasAuthority('team:invite') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/member")
    public Result<TeamMemberVO> inviteMember(@PathVariable Long spaceId, @Valid @RequestBody InviteMemberRequest request) {
        return teamService.inviteMember(spaceId, request);
    }

    @Operation(summary = "搜索可邀请的用户")
    @GetMapping("/{spaceId}/users/search")
    public Result<List<UserSearchVO>> searchUsers(
            @PathVariable Long spaceId,
            @RequestParam String keyword) {
        return teamService.searchUsers(spaceId, keyword);
    }

    @Operation(summary = "成员列表")
    @GetMapping("/{spaceId}/members")
    public Result<IPage<TeamMemberVO>> listMembers(
            @PathVariable Long spaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy) {
        return teamService.listMembers(spaceId, page, size, sortBy);
    }

    @Operation(summary = "修改成员角色")
    @Auditable(action = "TEAM_UPDATE_MEMBER", targetType = "TEAM", targetIdParam = "memberId")
    @PutMapping("/{spaceId}/member/{memberId}")
    public Result<Void> updateMemberRole(@PathVariable Long spaceId, @PathVariable Long memberId, @RequestParam Integer role) {
        return teamService.updateMemberRole(spaceId, memberId, role);
    }

    @Operation(summary = "移除成员")
    @Auditable(action = "TEAM_REMOVE_MEMBER", targetType = "TEAM", targetIdParam = "memberId")
    @DeleteMapping("/{spaceId}/member/{memberId}")
    public Result<Void> removeMember(@PathVariable Long spaceId, @PathVariable Long memberId) {
        return teamService.removeMember(spaceId, memberId);
    }

    // ==================== P0 新增：邀请链接 ====================

    @Operation(summary = "生成邀请链接")
    @Auditable(action = "TEAM_INVITE_LINK", targetType = "TEAM")
    @PostMapping("/{spaceId}/invite")
    public Result<TeamInviteVO> createInvite(@PathVariable Long spaceId, @Valid @RequestBody CreateInviteRequest request) {
        return teamService.createInvite(spaceId, request);
    }

    @Operation(summary = "邀请链接列表")
    @GetMapping("/{spaceId}/invites")
    public Result<IPage<TeamInviteVO>> listInvites(
            @PathVariable Long spaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return teamService.listInvites(spaceId, page, size);
    }

    @Operation(summary = "撤销邀请链接")
    @Auditable(action = "TEAM_REVOKE_INVITE", targetType = "TEAM")
    @DeleteMapping("/{spaceId}/invite/{inviteId}")
    public Result<Void> revokeInvite(@PathVariable Long spaceId, @PathVariable Long inviteId) {
        return teamService.revokeInvite(spaceId, inviteId);
    }

    @Operation(summary = "通过邀请码加入空间")
    @PostMapping("/invite/{code}")
    public Result<Long> joinByCode(@PathVariable String code) {
        return teamService.joinByCode(code);
    }

    // ==================== P0 新增：退出与移交 ====================

    @Operation(summary = "退出空间")
    @Auditable(action = "TEAM_LEAVE", targetType = "TEAM")
    @PostMapping("/{spaceId}/leave")
    public Result<Void> leaveSpace(@PathVariable Long spaceId) {
        return teamService.leaveSpace(spaceId);
    }

    @Operation(summary = "移交空间所有权")
    @Auditable(action = "TEAM_TRANSFER", targetType = "TEAM")
    @PostMapping("/{spaceId}/transfer")
    public Result<Void> transferOwnership(@PathVariable Long spaceId, @Valid @RequestBody TransferRequest request) {
        return teamService.transferOwnership(spaceId, request.getTargetMemberId());
    }

    // ==================== P0 新增：活动日志 ====================

    @Operation(summary = "空间活动日志")
    @GetMapping("/{spaceId}/activities")
    public Result<IPage<TeamActivityVO>> listActivities(
            @PathVariable Long spaceId,
            @RequestParam(required = false, defaultValue = "ALL") String filter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return teamService.listActivities(spaceId, filter, page, size);
    }

    @Operation(summary = "上报文件活动（前端上传回调）")
    @PostMapping("/{spaceId}/activity")
    public Result<Void> reportFileActivity(
            @PathVariable Long spaceId,
            @RequestParam String action,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String targetName) {
        return teamService.reportFileActivity(spaceId, action, targetId, targetName);
    }


    // ==================== P1 新增：文件夹权限 ====================

    @Operation(summary = "获取文件夹权限规则")
    @GetMapping("/{spaceId}/folder/{nodeId}/permissions")
    public Result<List<FolderPermissionVO>> getFolderPermissions(@PathVariable Long spaceId, @PathVariable Long nodeId) {
        return teamService.getFolderPermissions(spaceId, nodeId);
    }

    @Operation(summary = "设置文件夹权限规则")
    @Auditable(action = "TEAM_FOLDER_PERMISSION", targetType = "FOLDER")
    @PutMapping("/{spaceId}/folder/{nodeId}/permissions")
    public Result<Void> setFolderPermissions(@PathVariable Long spaceId, @PathVariable Long nodeId, @Valid @RequestBody FolderPermissionRequest request) {
        return teamService.setFolderPermissions(spaceId, nodeId, request);
    }

    // ==================== P1 新增：文件评论 ====================

    @Operation(summary = "文件评论列表")
    @GetMapping("/{spaceId}/comments/{nodeId}")
    public Result<List<TeamCommentVO>> listComments(@PathVariable Long spaceId, @PathVariable Long nodeId) {
        return teamService.listComments(spaceId, nodeId);
    }

    @Operation(summary = "发表评论")
    @PostMapping("/{spaceId}/comments")
    public Result<TeamCommentVO> addComment(@PathVariable Long spaceId, @Valid @RequestBody CommentRequest request) {
        return teamService.addComment(spaceId, request);
    }

    @Operation(summary = "编辑评论")
    @PutMapping("/{spaceId}/comments/{commentId}")
    public Result<Void> updateComment(@PathVariable Long spaceId, @PathVariable Long commentId, @RequestParam String content) {
        return teamService.updateComment(spaceId, commentId, content);
    }

    @Operation(summary = "删除评论")
    @DeleteMapping("/{spaceId}/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long spaceId, @PathVariable Long commentId) {
        return teamService.deleteComment(spaceId, commentId);
    }

    // ==================== P1 新增：空间置顶 ====================

    @Operation(summary = "切换空间置顶")
    @PostMapping("/{spaceId}/pin")
    public Result<Void> togglePin(@PathVariable Long spaceId) {
        return teamService.togglePin(spaceId);
    }



    @Operation(summary = "空间文件列表")
    @GetMapping("/{spaceId}/files")
    public Result<IPage<FileNodeVO>> listFiles(
            @PathVariable Long spaceId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        teamService.checkPermission(spaceId, parentId, 2);
        activeTracker.touchActive(spaceId, com.stcloud.common.context.UserContext.getUserId());
        return Result.success(fileService.listTeamFiles(spaceId, parentId, page, size));
    }

    @Operation(summary = "根据路径解析空间文件夹")
    @GetMapping("/{spaceId}/files/by-path")
    public Result<FileNodeVO> resolveByPath(@PathVariable Long spaceId, @RequestParam(required = false) String path) {
        teamService.checkPermission(spaceId, 2);
        return Result.success(fileService.resolveTeamByPath(spaceId, path));
    }

    @Operation(summary = "获取空间文件/文件夹详情")
    @GetMapping("/{spaceId}/files/{nodeId}")
    public Result<FileNodeVO> getNodeById(@PathVariable Long spaceId, @PathVariable Long nodeId) {
        teamService.checkPermission(spaceId, nodeId, 2);
        return Result.success(fileService.getTeamNodeById(spaceId, nodeId));
    }

    @Operation(summary = "空间文件夹树")
    @GetMapping("/{spaceId}/tree")
    public Result<List<FileTreeNodeVO>> getTree(@PathVariable Long spaceId) {
        teamService.checkPermission(spaceId, 2);
        return Result.success(fileService.getTeamFolderTree(spaceId));
    }

    @Operation(summary = "在空间中创建文件夹")
    @Auditable(action = "TEAM_CREATE_FOLDER", targetType = "FOLDER")
    @PreAuthorize("hasAuthority('file:upload') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/folder")
    public Result<FileNodeVO> createFolder(
            @PathVariable Long spaceId,
            @RequestParam(required = false) Long parentId,
            @RequestParam String folderName) {
        teamService.checkPermission(spaceId, parentId, 1);
        teamService.checkNotLocked(parentId);
        FileNodeVO node = fileService.createTeamFolder(spaceId, parentId, folderName);
        // 记录创建文件夹活动日志
        activityHelper.log(spaceId, "FOLDER_CREATE", "FOLDER", node.getId(), folderName);
        return Result.success(node);
    }

    @Operation(summary = "删除空间文件/文件夹至回收站")
    @Auditable(action = "DELETE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:delete') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/files/delete")
    public Result<Void> deleteFiles(@PathVariable Long spaceId, @RequestBody List<Long> nodeIds) {
        // 逐个校验节点级权限
        for (Long nodeId : nodeIds) { teamService.checkPermission(spaceId, nodeId, 1); teamService.checkNotLocked(nodeId); }
        fileService.deleteTeamFiles(spaceId, nodeIds);
        // 记录删除活动日志
        for (Long nodeId : nodeIds) {
            activityHelper.log(spaceId, "FILE_DELETE", "FILE", nodeId, null);
        }
        return Result.success();
    }

    @Operation(summary = "重命名空间文件/文件夹")
    @Auditable(action = "RENAME", targetType = "FILE", targetIdParam = "nodeId")
    @PreAuthorize("hasAuthority('file:rename') or hasRole('ADMIN')")
    @PutMapping("/{spaceId}/files/{nodeId}/rename")
    public Result<Void> renameFile(@PathVariable Long spaceId, @PathVariable Long nodeId, @RequestParam String newName) {
        teamService.checkPermission(spaceId, nodeId, 1);
        teamService.checkNotLocked(nodeId);
        fileService.renameTeamFile(spaceId, nodeId, newName);
        // 记录重命名活动日志
        activityHelper.log(spaceId, "FILE_RENAME", "FILE", nodeId, newName);
        return Result.success();
    }

    @Operation(summary = "移动空间文件/文件夹")
    @Auditable(action = "MOVE", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:move') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/files/move")
    public Result<Void> moveFiles(@PathVariable Long spaceId, @RequestBody MoveRequest request) {
        // 校验源节点编辑权限 + 目标文件夹编辑权限
        for (Long nodeId : request.getNodeIds()) { teamService.checkPermission(spaceId, nodeId, 1); teamService.checkNotLocked(nodeId); }
        teamService.checkPermission(spaceId, request.getTargetParentId(), 1);
        teamService.checkNotLocked(request.getTargetParentId());
        fileService.moveTeamFiles(spaceId, request.getNodeIds(), request.getTargetParentId());
        // 记录移动活动日志
        for (Long nodeId : request.getNodeIds()) {
            activityHelper.log(spaceId, "FILE_MOVE", "FILE", nodeId, null);
        }
        return Result.success();
    }

    @Operation(summary = "复制空间文件/文件夹")
    @Auditable(action = "COPY", targetType = "FILE")
    @PreAuthorize("hasAuthority('file:copy') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/files/copy")
    public Result<Void> copyFiles(@PathVariable Long spaceId, @RequestBody MoveRequest request) {
        for (Long nodeId : request.getNodeIds()) { teamService.checkPermission(spaceId, nodeId, 2); }
        teamService.checkPermission(spaceId, request.getTargetParentId(), 2);
        fileService.copyTeamFiles(spaceId, request.getNodeIds(), request.getTargetParentId());
        // 记录复制活动日志
        for (Long nodeId : request.getNodeIds()) {
            activityHelper.log(spaceId, "FILE_COPY", "FILE", nodeId, null);
        }
        return Result.success();
    }
    @Operation(summary = "锁定文件/文件夹")
    @Auditable(action = "FILE_LOCK", targetType = "FILE", targetIdParam = "nodeId")
    @PreAuthorize("hasAuthority('file:rename') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/files/{nodeId}/lock")
    public Result<Void> lockFile(@PathVariable Long spaceId, @PathVariable Long nodeId,
                                  @RequestBody LockRequest request) {
        // 锁定操作需要编辑权限，服务层校验权限并记录活动日志
        return teamService.lockFile(spaceId, nodeId, request.getHours());
    }

    @Operation(summary = "解锁文件/文件夹")
    @Auditable(action = "FILE_UNLOCK", targetType = "FILE", targetIdParam = "nodeId")
    @PreAuthorize("hasAuthority('file:rename') or hasRole('ADMIN')")
    @PostMapping("/{spaceId}/files/{nodeId}/unlock")
    public Result<Void> unlockFile(@PathVariable Long spaceId, @PathVariable Long nodeId) {
        // 解锁仅允许锁定人或管理员，服务层校验并记录活动日志
        return teamService.unlockFile(spaceId, nodeId);
    }
}
