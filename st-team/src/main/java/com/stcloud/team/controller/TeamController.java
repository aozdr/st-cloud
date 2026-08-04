package com.stcloud.team.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.annotation.Auditable;
import com.stcloud.common.response.Result;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.FileTreeNodeVO;
import com.stcloud.core.dto.MoveRequest;
import com.stcloud.core.service.FileService;
import com.stcloud.team.dto.*;
import com.stcloud.team.service.TeamService;
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
            @RequestParam(defaultValue = "20") int size) {
        return teamService.listSpaces(page, size);
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

    @Operation(summary = "成员列表")
    @GetMapping("/{spaceId}/members")
    public Result<IPage<TeamMemberVO>> listMembers(
            @PathVariable Long spaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return teamService.listMembers(spaceId, page, size);
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

    // ==================== 空间文件操作（委托 FileService，复用核心逻辑）====================

    @Operation(summary = "空间文件列表")
    @GetMapping("/{spaceId}/files")
    public Result<IPage<FileNodeVO>> listFiles(
            @PathVariable Long spaceId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        teamService.checkPermission(spaceId, 2);
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
        teamService.checkPermission(spaceId, 2);
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
    @PostMapping("/{spaceId}/folder")
    public Result<FileNodeVO> createFolder(
            @PathVariable Long spaceId,
            @RequestParam(required = false) Long parentId,
            @RequestParam String folderName) {
        teamService.checkPermission(spaceId, 1);
        return Result.success(fileService.createTeamFolder(spaceId, parentId, folderName));
    }

    @Operation(summary = "删除空间文件/文件夹至回收站")
    @Auditable(action = "DELETE", targetType = "FILE")
    @PostMapping("/{spaceId}/files/delete")
    public Result<Void> deleteFiles(@PathVariable Long spaceId, @RequestBody List<Long> nodeIds) {
        teamService.checkPermission(spaceId, 1);
        fileService.deleteTeamFiles(spaceId, nodeIds);
        return Result.success();
    }

    @Operation(summary = "重命名空间文件/文件夹")
    @Auditable(action = "RENAME", targetType = "FILE", targetIdParam = "nodeId")
    @PutMapping("/{spaceId}/files/{nodeId}/rename")
    public Result<Void> renameFile(@PathVariable Long spaceId, @PathVariable Long nodeId, @RequestParam String newName) {
        teamService.checkPermission(spaceId, 1);
        fileService.renameTeamFile(spaceId, nodeId, newName);
        return Result.success();
    }

    @Operation(summary = "移动空间文件/文件夹")
    @Auditable(action = "MOVE", targetType = "FILE")
    @PostMapping("/{spaceId}/files/move")
    public Result<Void> moveFiles(@PathVariable Long spaceId, @RequestBody MoveRequest request) {
        teamService.checkPermission(spaceId, 1);
        fileService.moveTeamFiles(spaceId, request.getNodeIds(), request.getTargetParentId());
        return Result.success();
    }

    @Operation(summary = "复制空间文件/文件夹")
    @Auditable(action = "COPY", targetType = "FILE")
    @PostMapping("/{spaceId}/files/copy")
    public Result<Void> copyFiles(@PathVariable Long spaceId, @RequestBody MoveRequest request) {
        teamService.checkPermission(spaceId, 2);
        fileService.copyTeamFiles(spaceId, request.getNodeIds(), request.getTargetParentId());
        return Result.success();
    }

}