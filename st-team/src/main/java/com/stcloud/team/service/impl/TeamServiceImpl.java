package com.stcloud.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import com.stcloud.team.service.TeamService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class TeamServiceImpl implements TeamService {

    @Resource
    private TeamSpaceMapper teamSpaceMapper;

    @Resource
    private TeamMemberMapper teamMemberMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private com.stcloud.core.service.CloudStorageService cloudStorageService;

    @Override
    @Transactional
    public Result<TeamSpaceVO> createSpace(CreateSpaceRequest request) {
        Long userId = UserContext.getUserId();

        TeamSpace space = new TeamSpace();
        space.setSpaceName(request.getSpaceName());
        space.setDescription(request.getDescription());
        space.setIcon(request.getIcon());
        space.setOwnerId(userId);
        space.setStorageUsed(0L);
        space.setStorageQuota(request.getStorageQuota() != null ? request.getStorageQuota() : 10L * 1024 * 1024 * 1024);
        cloudStorageService.validateQuotaAssignment(0L, space.getStorageQuota());
        space.setStatus(1);
        teamSpaceMapper.insert(space);

        // 创建者自动成为管理员
        TeamMember member = new TeamMember();
        member.setSpaceId(space.getId());
        member.setUserId(userId);
        member.setRole(0);
        member.setJoinedAt(LocalDateTime.now());
        teamMemberMapper.insert(member);

        log.info("用户{}创建团队空间: spaceId={}, name={}", userId, space.getId(), space.getSpaceName());
        return Result.success(toSpaceVO(space, 1));
    }

    @Override
    public Result<IPage<TeamSpaceVO>> listSpaces(int page, int size) {
        Long userId = UserContext.getUserId();

        // 查询用户参与的所有空间ID
        LambdaQueryWrapper<TeamMember> memberWrapper = new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getUserId, userId)
                .orderByDesc(TeamMember::getJoinedAt);
        var members = teamMemberMapper.selectList(memberWrapper);

        if (members.isEmpty()) {
            return Result.success(new Page<>(page, size));
        }

        var spaceIds = members.stream().map(TeamMember::getSpaceId).toList();
        Page<TeamSpace> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<TeamSpace> wrapper = new LambdaQueryWrapper<TeamSpace>()
                .in(TeamSpace::getId, spaceIds)
                .eq(TeamSpace::getStatus, 1)
                .orderByDesc(TeamSpace::getCreatedAt);
        IPage<TeamSpace> spacePage = teamSpaceMapper.selectPage(pageParam, wrapper);

        IPage<TeamSpaceVO> voPage = spacePage.convert(space -> {
            Long memberCount = teamMemberMapper.selectCount(
                    new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, space.getId()));
            return toSpaceVO(space, memberCount.intValue());
        });
        return Result.success(voPage);
    }

    @Override
    public Result<TeamSpaceVO> getSpace(Long spaceId) {
        checkPermission(spaceId, 2);
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException(ResultCode.TEAM_NOT_FOUND);
        }
        Long memberCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId));
        return Result.success(toSpaceVO(space, memberCount.intValue()));
    }

    @Override
    @Transactional
    public Result<Void> updateSpace(Long spaceId, CreateSpaceRequest request) {
        checkPermission(spaceId, 0);
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        if (request.getSpaceName() != null) space.setSpaceName(request.getSpaceName());
        if (request.getDescription() != null) space.setDescription(request.getDescription());
        if (request.getIcon() != null) space.setIcon(request.getIcon());
        if (request.getStorageQuota() != null) {
            cloudStorageService.validateQuotaAssignment(space.getStorageQuota(), request.getStorageQuota());
            space.setStorageQuota(request.getStorageQuota());
        }
        teamSpaceMapper.updateById(space);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> deleteSpace(Long spaceId) {
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        if (space == null) throw new BusinessException(ResultCode.TEAM_NOT_FOUND);
        if (!space.getOwnerId().equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "仅空间拥有者可删除");
        }
        teamSpaceMapper.deleteById(spaceId);
        teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId));
        return Result.success();
    }

    @Override
    @Transactional
    public Result<TeamMemberVO> inviteMember(Long spaceId, InviteMemberRequest request) {
        checkPermission(spaceId, 0);

        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);

        // 检查是否已是成员
        Long exists = teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, user.getId()));
        if (exists > 0) throw new BusinessException(ResultCode.TEAM_MEMBER_EXISTS);

        TeamMember member = new TeamMember();
        member.setSpaceId(spaceId);
        member.setUserId(user.getId());
        member.setRole(request.getRole() != null ? request.getRole() : 2);
        member.setJoinedAt(LocalDateTime.now());
        teamMemberMapper.insert(member);

        return Result.success(toMemberVO(member, user));
    }

    @Override
    public Result<IPage<TeamMemberVO>> listMembers(Long spaceId, int page, int size) {
        checkPermission(spaceId, 2);
        Page<TeamMember> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<TeamMember> wrapper = new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .orderByAsc(TeamMember::getRole);
        IPage<TeamMember> memberPage = teamMemberMapper.selectPage(pageParam, wrapper);

        IPage<TeamMemberVO> voPage = memberPage.convert(member -> {
            SysUser user = sysUserMapper.selectById(member.getUserId());
            return toMemberVO(member, user);
        });
        return Result.success(voPage);
    }

    @Override
    @Transactional
    public Result<Void> updateMemberRole(Long spaceId, Long memberId, Integer role) {
        checkPermission(spaceId, 0);
        TeamMember member = teamMemberMapper.selectById(memberId);
        if (member == null || !member.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ResultCode.TEAM_MEMBER_NOT_FOUND);
        }
        member.setRole(role);
        teamMemberMapper.updateById(member);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> removeMember(Long spaceId, Long memberId) {
        checkPermission(spaceId, 0);
        TeamMember member = teamMemberMapper.selectById(memberId);
        if (member == null || !member.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ResultCode.TEAM_MEMBER_NOT_FOUND);
        }
        if (member.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能移除自己");
        }
        teamMemberMapper.deleteById(memberId);
        return Result.success();
    }

    @Override
    public Integer checkPermission(Long spaceId, Integer minRole) {
        Long userId = UserContext.getUserId();
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId)
                .eq(TeamMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "您不是该空间的成员");
        }
        if (member.getRole() > minRole) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "权限不足");
        }
        return member.getRole();
    }

    private TeamSpaceVO toSpaceVO(TeamSpace space, int memberCount) {
        TeamSpaceVO vo = new TeamSpaceVO();
        vo.setId(space.getId());
        vo.setSpaceName(space.getSpaceName());
        vo.setDescription(space.getDescription());
        vo.setIcon(space.getIcon());
        vo.setOwnerId(space.getOwnerId());
        SysUser owner = sysUserMapper.selectById(space.getOwnerId());
        vo.setOwnerName(owner != null ? owner.getNickname() : "未知");
        vo.setStorageUsed(space.getStorageUsed());
        vo.setStorageQuota(space.getStorageQuota());
        vo.setMemberCount(memberCount);
        vo.setStatus(space.getStatus());
        vo.setCreatedAt(space.getCreatedAt());
        return vo;
    }

    private TeamMemberVO toMemberVO(TeamMember member, SysUser user) {
        TeamMemberVO vo = new TeamMemberVO();
        vo.setId(member.getId());
        vo.setSpaceId(member.getSpaceId());
        vo.setUserId(member.getUserId());
        vo.setUsername(user != null ? user.getUsername() : "未知");
        vo.setNickname(user != null ? user.getNickname() : "未知");
        vo.setAvatar(user != null ? user.getAvatar() : null);
        vo.setRole(member.getRole());
        vo.setJoinedAt(member.getJoinedAt());
        vo.setLastActiveAt(member.getLastActiveAt());
        return vo;
    }
}
