package com.stcloud.team.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.Result;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.dto.*;
import com.stcloud.team.entity.TeamActivity;
import com.stcloud.team.entity.TeamInvite;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.enums.InviteStatus;
import com.stcloud.team.enums.RoleStatus;
import com.stcloud.team.enums.TeamSpaceStatus;
import com.stcloud.team.mapper.TeamActivityMapper;
import com.stcloud.team.mapper.TeamInviteMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import com.stcloud.team.service.FolderPermissionService;
import com.stcloud.team.service.TeamService;
import com.stcloud.team.util.ActiveTracker;
import com.stcloud.team.util.TeamActivityHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private TeamInviteMapper teamInviteMapper;
    @Resource
    private TeamActivityMapper teamActivityMapper;
    @Resource
    private CloudStorageService cloudStorageService;
    @Resource
    private TeamActivityHelper activityHelper;
    @Resource
    private ActiveTracker activeTracker;
    @Resource
    private com.stcloud.team.service.FolderPermissionService folderPermissionService;
    @Resource
    private com.stcloud.team.util.NotificationHelper notificationHelper;
    @Resource
    private com.stcloud.team.mapper.TeamCommentMapper teamCommentMapper;
    @Resource
    private com.stcloud.team.mapper.TeamFolderPermissionMapper teamFolderPermissionMapper;
    @Resource
    private com.stcloud.team.mapper.TeamRoleMapper teamRoleMapper;
    @Resource
    private com.stcloud.team.mapper.TeamExternalConfigMapper teamExternalConfigMapper;
    @Resource
    private com.stcloud.core.mapper.FileNodeMapper fileNodeMapper;

    // ==================== 空间管理 ====================

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
        // 新建空间默认正常
        space.setStatus(TeamSpaceStatus.NORMAL.getCode());
        teamSpaceMapper.insert(space);

        // 创建者自动成为管理员
        TeamMember member = new TeamMember();
        member.setSpaceId(space.getId());
        member.setUserId(userId);
        member.setRole(0);
        member.setJoinedAt(LocalDateTime.now());
        teamMemberMapper.insert(member);

        log.info("用户{}创建团队空间: spaceId={}, name={}", userId, space.getId(), space.getSpaceName());
        return Result.success(toSpaceVO(space, 1, 0));
    }

    @Override
    public Result<IPage<TeamSpaceVO>> listSpaces(int page, int size, String keyword, String sortBy) {
        // 兼容旧签名
        if (keyword == null) keyword = "";
        if (sortBy == null) sortBy = "createdAt";
        Long userId = UserContext.getUserId();
        var members = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getUserId, userId)
                .orderByDesc(TeamMember::getJoinedAt));
        if (members.isEmpty()) return Result.success(new Page<>(page, size));

        var spaceIds = members.stream().map(TeamMember::getSpaceId).toList();
        Page<TeamSpace> pageParam = new Page<>(page, size);
        IPage<TeamSpace> spacePage = teamSpaceMapper.selectPage(pageParam, new LambdaQueryWrapper<TeamSpace>()
                .in(TeamSpace::getId, spaceIds)
                .eq(TeamSpace::getStatus, 1)
                .orderByDesc(TeamSpace::getCreatedAt));

        IPage<TeamSpaceVO> voPage = spacePage.convert(space -> {
            Long memberCount = teamMemberMapper.selectCount(
                    new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, space.getId()));
            // 查当前用户对该空间的置顶状态
            TeamMember myMember = members.stream()
                    .filter(m -> m.getSpaceId().equals(space.getId())).findFirst().orElse(null);
            return toSpaceVO(space, memberCount.intValue(), myMember != null ? myMember.getIsPinned() : 0);
        });
        return Result.success(voPage);
    }

    @Override
    public Result<TeamSpaceVO> getSpace(Long spaceId) {
        checkPermission(spaceId, 2);
        // 更新活跃时间（5分钟去重）
        activeTracker.touchActive(spaceId, UserContext.getUserId());
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        if (space == null) throw new BusinessException(ResultCode.TEAM_NOT_FOUND);
        Long memberCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId));
        TeamMember myMember = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, UserContext.getUserId()));
        return Result.success(toSpaceVO(space, memberCount.intValue(), myMember != null ? myMember.getIsPinned() : 0));
    }

    @Override
    @Transactional
    public Result<Void> updateSpace(Long spaceId, CreateSpaceRequest request) {
        checkPermission(spaceId, 0);
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        boolean changed = false;
        if (request.getSpaceName() != null) { space.setSpaceName(request.getSpaceName()); changed = true; }
        if (request.getDescription() != null) { space.setDescription(request.getDescription()); changed = true; }
        if (request.getIcon() != null) { space.setIcon(request.getIcon()); changed = true; }
        if (request.getStorageQuota() != null) {
            cloudStorageService.validateQuotaAssignment(space.getStorageQuota(), request.getStorageQuota());
            space.setStorageQuota(request.getStorageQuota());
            changed = true;
        }
        teamSpaceMapper.updateById(space);
        // 记录空间设置变更活动日志
        if (changed) {
            activityHelper.log(spaceId, "SPACE_UPDATE", "SPACE", spaceId, space.getSpaceName());
        }
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
        // 空间与成员删除：权限缓存失效
        folderPermissionService.invalidateSpace(spaceId);
        return Result.success();
    }

    // ==================== 成员管理 ====================

    @Override
    @Transactional
    public Result<TeamMemberVO> inviteMember(Long spaceId, InviteMemberRequest request) {
        checkPermission(spaceId, 0);
        // 按用户ID查找用户（前端搜索选择后传入 userId，避免手动输入用户名拼写错误）
        SysUser user = sysUserMapper.selectById(request.getUserId());
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);

        Long exists = teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, user.getId()));
        if (exists > 0) throw new BusinessException(ResultCode.TEAM_MEMBER_EXISTS);

        TeamMember member = new TeamMember();
        member.setSpaceId(spaceId);
        member.setUserId(user.getId());
        member.setRole(request.getRole() != null ? request.getRole() : 2);
        member.setJoinedAt(LocalDateTime.now());
        teamMemberMapper.insert(member);
        // 成员变更：权限缓存失效，新成员权限下次访问重新计算
        folderPermissionService.invalidateSpace(spaceId);

        // 记录邀请活动日志
        activityHelper.log(spaceId, "MEMBER_INVITE", "MEMBER", user.getId(), user.getNickname());
        // 发送邀请通知
        notificationHelper.notify(user.getId(), "TEAM_INVITE", "您被邀请加入团队空间",
                "您已被邀请加入空间", "team", spaceId);
        return Result.success(toMemberVO(member, user));
    }

    @Override
    public Result<List<UserSearchVO>> searchUsers(Long spaceId, String keyword) {
        checkPermission(spaceId, 0);
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.success(List.of());
        }
        String kw = keyword.trim();
        // 按用户名或昵称模糊搜索，排除已是空间成员的用户
        // 先查出该空间已有成员的用户ID集合
        List<TeamMember> existingMembers = teamMemberMapper.selectList(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId));
        var existingUserIds = existingMembers.stream().map(TeamMember::getUserId).collect(java.util.stream.Collectors.toSet());

        // 模糊搜索用户（最多返回 10 条），仅搜索正常状态用户
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .and(w -> w.like(SysUser::getUsername, kw).or().like(SysUser::getNickname, kw))
                .eq(SysUser::getStatus, 1)
                .last("LIMIT 10"));

        // 过滤掉已是成员的用户
        List<UserSearchVO> result = users.stream()
                .filter(u -> !existingUserIds.contains(u.getId()))
                .map(u -> {
                    UserSearchVO vo = new UserSearchVO();
                    vo.setUserId(u.getId());
                    vo.setUsername(u.getUsername());
                    vo.setNickname(u.getNickname());
                    vo.setAvatar(u.getAvatar());
                    return vo;
                })
                .toList();
        return Result.success(result);
    }

    @Override
    public Result<IPage<TeamMemberVO>> listMembers(Long spaceId, int page, int size, String sortBy) {
        checkPermission(spaceId, 2);
        Page<TeamMember> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<TeamMember> wrapper = new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId);
        // 支持按活跃时间排序，默认按角色排序
        if ("active".equals(sortBy)) {
            wrapper.orderByDesc(TeamMember::getLastActiveAt);
        } else {
            wrapper.orderByAsc(TeamMember::getRole);
        }
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
        // 角色变更：权限缓存失效，重新按新角色计算
        folderPermissionService.invalidateSpace(spaceId);
        // 记录角色变更活动日志
        SysUser user = sysUserMapper.selectById(member.getUserId());
        activityHelper.log(spaceId, "MEMBER_ROLE_CHANGE", "MEMBER", member.getUserId(),
                user != null ? user.getNickname() : null);
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
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能移除自己，请使用退出空间");
        }
        teamMemberMapper.deleteById(memberId);
        // 成员移除：权限缓存失效
        folderPermissionService.invalidateSpace(spaceId);
        // 记录移除活动日志
        SysUser user = sysUserMapper.selectById(member.getUserId());
        activityHelper.log(spaceId, "MEMBER_REMOVE", "MEMBER", member.getUserId(),
                user != null ? user.getNickname() : null);
        // 通知被移除的成员
        notificationHelper.notify(member.getUserId(), "MEMBER_CHANGE", "您被移出团队空间",
                "您已被移出空间", "team", spaceId);
        return Result.success();
    }

    @Override
    public Integer checkPermission(Long spaceId, Integer minRole) {
        Long userId = UserContext.getUserId();
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "您不是该空间的成员");
        }
        // 权限模型重设计：按权限点推导旧角色等级（含自定义角色），管理员（roleId==0 或 manage_settings）直通
        int level = legacyRoleLevel(member);
        if (minRole != null && level > minRole) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "权限不足");
        }
        return level;
    }

    // ==================== P0 新增：邀请链接 ====================

    @Override
    @Transactional
    public Result<TeamInviteVO> createInvite(Long spaceId, CreateInviteRequest request) {
        checkPermission(spaceId, 0);
        // 生成 32 位随机邀请码（大小写字母+数字）
        String inviteCode = generateInviteCode();
        TeamInvite invite = new TeamInvite();
        invite.setSpaceId(spaceId);
        invite.setInviteCode(inviteCode);
        invite.setRole(request.getRole() != null ? request.getRole() : 2);
        invite.setCreatedBy(UserContext.getUserId());
        invite.setExpireAt(request.getExpireAt());
        // 新建邀请链接默认有效
        invite.setStatus(InviteStatus.ACTIVE.getCode());
        teamInviteMapper.insert(invite);
        // 记录活动日志
        activityHelper.log(spaceId, "INVITE_CREATE", "INVITE", invite.getId(), "邀请链接");
        return Result.success(toInviteVO(invite));
    }

    @Override
    public Result<IPage<TeamInviteVO>> listInvites(Long spaceId, int page, int size) {
        checkPermission(spaceId, 0);
        Page<TeamInvite> pageParam = new Page<>(page, size);
        IPage<TeamInvite> invitePage = teamInviteMapper.selectPage(pageParam,
                new LambdaQueryWrapper<TeamInvite>()
                        .eq(TeamInvite::getSpaceId, spaceId)
                        .orderByDesc(TeamInvite::getCreatedAt));
        IPage<TeamInviteVO> voPage = invitePage.convert(this::toInviteVO);
        return Result.success(voPage);
    }

    @Override
    @Transactional
    public Result<Void> revokeInvite(Long spaceId, Long inviteId) {
        checkPermission(spaceId, 0);
        TeamInvite invite = teamInviteMapper.selectById(inviteId);
        if (invite == null || !invite.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ResultCode.TEAM_INVITE_NOT_FOUND);
        }
        // 撤销邀请链接
        invite.setStatus(InviteStatus.REVOKED.getCode());
        teamInviteMapper.updateById(invite);
        // 记录活动日志
        activityHelper.log(spaceId, "INVITE_REVOKE", "INVITE", inviteId, "邀请链接");
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Long> joinByCode(String inviteCode) {
        // 查询邀请码（无需空间权限，已认证即可）
        TeamInvite invite = teamInviteMapper.selectOne(new LambdaQueryWrapper<TeamInvite>()
                .eq(TeamInvite::getInviteCode, inviteCode));
        if (invite == null || invite.getStatus() == InviteStatus.REVOKED.getCode()) {
            throw new BusinessException(ResultCode.TEAM_INVITE_NOT_FOUND);
        }
        // 校验是否过期
        if (invite.getExpireAt() != null && invite.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.TEAM_INVITE_EXPIRED);
        }
        // 校验空间是否存在且正常
        TeamSpace space = teamSpaceMapper.selectById(invite.getSpaceId());
        if (space == null || space.getStatus() != TeamSpaceStatus.NORMAL.getCode()) {
            throw new BusinessException(ResultCode.TEAM_NOT_FOUND);
        }
        Long userId = UserContext.getUserId();
        // 校验是否已是成员
        Long exists = teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, invite.getSpaceId())
                .eq(TeamMember::getUserId, userId));
        if (exists > 0) {
            // 已是成员，返回空间ID供前端跳转
            return Result.success(invite.getSpaceId());
        }
        // 加入空间
        TeamMember member = new TeamMember();
        member.setSpaceId(invite.getSpaceId());
        member.setUserId(userId);
        member.setRole(invite.getRole());
        member.setJoinedAt(LocalDateTime.now());
        teamMemberMapper.insert(member);
        // 成员变更：权限缓存失效
        folderPermissionService.invalidateSpace(invite.getSpaceId());
        // 记录加入活动日志
        SysUser user = sysUserMapper.selectById(userId);
        activityHelper.log(invite.getSpaceId(), "MEMBER_JOIN", "MEMBER", userId,
                user != null ? user.getNickname() : null);
        // 通知空间管理员有新成员加入
        notificationHelper.notify(invite.getCreatedBy(), "MEMBER_CHANGE", "新成员加入空间",
                (user != null ? user.getNickname() : "新用户") + " 通过邀请链接加入了空间", "team", invite.getSpaceId());
        return Result.success(invite.getSpaceId());
    }

    // ==================== P0 新增：退出与移交 ====================

    @Override
    @Transactional
    public Result<Void> leaveSpace(Long spaceId) {
        Long userId = UserContext.getUserId();
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, userId));
        if (member == null) throw new BusinessException(ResultCode.TEAM_MEMBER_NOT_FOUND);

        // 管理员退出需额外校验
        if (member.getRole() == 0) {
            TeamSpace space = teamSpaceMapper.selectById(spaceId);
            // 拥有者必须先移交所有权
            if (space.getOwnerId().equals(userId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "请先移交空间所有权");
            }
            // 最后一个管理员不可退出
            Long adminCount = teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                    .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getRole, 0));
            if (adminCount <= 1) {
                throw new BusinessException(ResultCode.TEAM_LAST_ADMIN);
            }
        }
        teamMemberMapper.deleteById(member.getId());
        // 记录退出活动日志
        SysUser user = sysUserMapper.selectById(userId);
        activityHelper.log(spaceId, "MEMBER_LEAVE", "MEMBER", userId,
                user != null ? user.getNickname() : null);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> transferOwnership(Long spaceId, Long targetMemberId) {
        Long userId = UserContext.getUserId();
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        if (space == null) throw new BusinessException(ResultCode.TEAM_NOT_FOUND);
        // 仅拥有者可移交
        if (!space.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "仅空间拥有者可移交所有权");
        }
        TeamMember target = teamMemberMapper.selectById(targetMemberId);
        if (target == null || !target.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ResultCode.TEAM_MEMBER_NOT_FOUND);
        }
        // 目标必须是管理员
        if (target.getRole() != 0) {
            throw new BusinessException(ResultCode.TEAM_TRANSFER_TARGET_INVALID);
        }
        // 更新拥有者
        space.setOwnerId(target.getUserId());
        teamSpaceMapper.updateById(space);
        // 记录移交活动日志
        SysUser targetUser = sysUserMapper.selectById(target.getUserId());
        activityHelper.log(spaceId, "SPACE_TRANSFER", "SPACE", target.getUserId(),
                targetUser != null ? targetUser.getNickname() : null);
        return Result.success();
    }

    // ==================== P0 新增：活动日志 ====================

    @Override
    public Result<IPage<TeamActivityVO>> listActivities(Long spaceId, String filter, int page, int size) {
        checkPermission(spaceId, 2);
        Page<TeamActivity> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<TeamActivity> wrapper = new LambdaQueryWrapper<TeamActivity>()
                .eq(TeamActivity::getSpaceId, spaceId)
                .orderByDesc(TeamActivity::getCreatedAt);
        // 按操作类型筛选
        if (filter != null && !filter.isEmpty() && !"ALL".equals(filter)) {
            wrapper.likeRight(TeamActivity::getAction, filter + "_");
        }
        IPage<TeamActivity> activityPage = teamActivityMapper.selectPage(pageParam, wrapper);
        IPage<TeamActivityVO> voPage = activityPage.convert(this::toActivityVO);
        return Result.success(voPage);
    }

    @Override
    public Result<Void> reportFileActivity(Long spaceId, String action, Long targetId, String targetName) {
        // 校验调用者是空间编辑者以上（防止查看者伪造活动记录）
        checkPermission(spaceId, 1);
        // 写入活动日志（前端上传完成后回调上报）
        activityHelper.log(spaceId, action, "FILE", targetId, targetName);
        return Result.success();
    }

    // ==================== P1 新增：文件夹权限 ====================

    @Override
    public Integer checkPermission(Long spaceId, Long nodeId, Integer minPermission) {
        // 权限模型重设计：兼容旧单值校验，内部按权限点并集推导旧等级（-1 不再产生，规则只增强）
        int effectiveLevel = FolderPermissionService.legacyLevelOf(resolveMyPermissions(spaceId, nodeId));
        if (minPermission != null && effectiveLevel > minPermission) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "权限不足");
        }
        return effectiveLevel;
    }

    // ==================== 权限模型重设计：权限点校验（TASK-PERM-BE1） ====================

    @Override
    public void requirePermissions(Long spaceId, Long nodeId, String... perms) {
        if (perms == null || perms.length == 0) {
            return;
        }
        Set<String> effective = resolveMyPermissions(spaceId, nodeId);
        for (String perm : perms) {
            if (!effective.contains(perm)) {
                throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "权限不足：" + perm);
            }
        }
    }

    @Override
    public Set<String> resolveMyPermissions(Long spaceId, Long nodeId) {
        Long userId = UserContext.getUserId();
        // 1. 成员校验（非成员拒绝）
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, userId));
        if (member == null) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "您不是该空间的成员");
        }
        // 2. 角色权限集解析
        Set<String> rolePerms = resolveRolePermissions(member);
        // 3. 管理员直通：roleId==0 或权限集含 manage_settings → 全部权限点
        if (member.getRole() != null && (member.getRole() == 0
                || rolePerms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS))) {
            return new LinkedHashSet<>(FolderPermissionService.ALL_PERMISSIONS);
        }
        // 4. 并集：角色权限 ∪ 沿父链收集的文件夹规则权限
        return folderPermissionService.resolvePermissions(spaceId, nodeId, userId, rolePerms);
    }

    /**
     * 成员角色 → 权限点集合：
     * role 0/1/2 用 presetPerms（查看者(2) 为 view=true、download=false）；
     * 自定义角色（>=100，兼容 3~99）从 team_role.permissions JSON 读取，角色缺失/停用回退查看者。
     */
    private Set<String> resolveRolePermissions(TeamMember member) {
        if (member == null || member.getRole() == null) {
            return FolderPermissionService.VIEWER_PERMISSIONS;
        }
        int role = member.getRole();
        if (role >= 0 && role <= 2) {
            return FolderPermissionService.presetPermissions(role);
        }
        com.stcloud.team.entity.TeamRole teamRole = teamRoleMapper.selectById((long) role);
        if (teamRole != null && teamRole.getStatus() != null
                && teamRole.getStatus() == RoleStatus.ENABLED.getCode()) {
            return FolderPermissionService.parsePermissions(teamRole.getPermissions());
        }
        // 角色不存在或已停用：回退查看者
        return FolderPermissionService.VIEWER_PERMISSIONS;
    }

    /**
     * 成员旧角色等级（兼容空间级 checkPermission）：含 manage_settings/roleId==0 → 0；
     * 含任一内容操作权限 → 1；仅 view → 2。
     */
    private int legacyRoleLevel(TeamMember member) {
        if (member == null || member.getRole() == null) {
            return 2;
        }
        Set<String> perms = resolveRolePermissions(member);
        if (member.getRole() == 0 || perms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS)) {
            return 0;
        }
        return FolderPermissionService.legacyLevelOf(perms);
    }

    /**
     * 校验文件夹节点归属（P1 安全修复）：节点必须存在、状态正常且属于指定空间。
     * 防止任意空间管理员跨空间读取/写入其他团队文件夹的权限规则（跨空间 ACL 注入）。
     */
    private FileNode requireFolderNodeInSpace(Long spaceId, Long folderNodeId) {
        FileNode node = fileNodeMapper.selectById(folderNodeId);
        if (node == null || !node.isNormal() || !spaceId.equals(node.getSpaceId())) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "节点不属于该空间");
        }
        return node;
    }

    /**
     * 权限规则服务端校验（P2 安全修复）：
     * <ol>
     *   <li>subjectType 白名单 {all, member, role}，非法值直接拒绝，防止污染数据；</li>
     *   <li>all 主体权限集禁止包含空间级管理权限点 manage_members / manage_settings
     *       （permissions JSON 为空时按旧单值 permission 回退映射，同限），防止越权下放。</li>
     * </ol>
     */
    private void validatePermissionRules(List<FolderPermissionRequest.PermissionRule> rules) {
        if (rules == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "权限规则不能为空");
        }
        Set<String> validSubjectTypes = Set.of("all", "member", "role");
        for (FolderPermissionRequest.PermissionRule rule : rules) {
            if (rule == null || rule.getSubjectType() == null
                    || !validSubjectTypes.contains(rule.getSubjectType())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "subjectType 必须为 all/member/role");
            }
            if (!"all".equals(rule.getSubjectType())) {
                continue;
            }
            // all 主体：permissions JSON 为空时按旧单值 permission 回退映射，权限上限同限
            Set<String> perms = rule.getPermissions() == null || rule.getPermissions().isBlank()
                    ? FolderPermissionService.legacyPermissionSet(rule.getPermission())
                    : FolderPermissionService.parsePermissions(rule.getPermissions());
            if (perms.contains(FolderPermissionService.PERM_MANAGE_MEMBERS)
                    || perms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "全体成员规则不能包含空间管理权限");
            }
        }
    }

    @Override
    public Result<List<FolderPermissionVO>> getFolderPermissions(Long spaceId, Long folderNodeId) {
        checkPermission(spaceId, 0);
        requireFolderNodeInSpace(spaceId, folderNodeId);
        List<com.stcloud.team.entity.TeamFolderPermission> perms = folderPermissionService.listPermissions(folderNodeId);
        List<FolderPermissionVO> voList = perms.stream().map(p -> {
            FolderPermissionVO vo = new FolderPermissionVO();
            vo.setId(p.getId());
            vo.setSpaceId(p.getSpaceId());
            vo.setFolderNodeId(p.getFolderNodeId());
            vo.setSubjectType(p.getSubjectType());
            vo.setSubjectId(p.getSubjectId());
            vo.setPermission(p.getPermission());
            vo.setPermissions(p.getPermissions());
            // 填充对象名称
            if ("member".equals(p.getSubjectType())) {
                SysUser user = sysUserMapper.selectById(p.getSubjectId());
                vo.setSubjectName(user != null ? user.getNickname() : "未知");
            } else if ("all".equals(p.getSubjectType())) {
                vo.setSubjectName("全体成员");
            } else {
                vo.setSubjectName(p.getSubjectId() == 0 ? "管理员" : p.getSubjectId() == 1 ? "编辑者" : "查看者");
            }
            vo.setCreatedAt(p.getCreatedAt());
            return vo;
        }).toList();
        return Result.success(voList);
    }

    @Override
    @Transactional
    public Result<Void> setFolderPermissions(Long spaceId, Long folderNodeId, FolderPermissionRequest request) {
        checkPermission(spaceId, 0);
        requireFolderNodeInSpace(spaceId, folderNodeId);
        validatePermissionRules(request.getRules());
        List<com.stcloud.team.entity.TeamFolderPermission> rules = request.getRules().stream().map(r -> {
            com.stcloud.team.entity.TeamFolderPermission perm = new com.stcloud.team.entity.TeamFolderPermission();
            perm.setSubjectType(r.getSubjectType());
            perm.setSubjectId(r.getSubjectId());
            // 兼容 permission NOT NULL：未传单值时由权限点 JSON 推导旧等级
            perm.setPermission(r.getPermission() != null ? r.getPermission()
                    : FolderPermissionService.legacyLevelOf(FolderPermissionService.parsePermissions(r.getPermissions())));
            perm.setPermissions(r.getPermissions());
            return perm;
        }).toList();
        folderPermissionService.setPermissions(spaceId, folderNodeId, rules);
        activityHelper.log(spaceId, "SPACE_UPDATE", "FOLDER", folderNodeId, "文件夹权限设置");
        return Result.success();
    }

    // ==================== P1 新增：文件评论 ====================

    @Override
    public Result<List<TeamCommentVO>> listComments(Long spaceId, Long nodeId) {
        checkPermission(spaceId, 2);
        // 查顶级评论
        List<com.stcloud.team.entity.TeamComment> topComments = teamCommentMapper.selectList(
                new LambdaQueryWrapper<com.stcloud.team.entity.TeamComment>()
                        .eq(com.stcloud.team.entity.TeamComment::getSpaceId, spaceId)
                        .eq(com.stcloud.team.entity.TeamComment::getNodeId, nodeId)
                        .isNull(com.stcloud.team.entity.TeamComment::getParentId)
                        .orderByAsc(com.stcloud.team.entity.TeamComment::getCreatedAt));
        // 查回复
        List<TeamCommentVO> voList = topComments.stream().map(c -> {
            TeamCommentVO vo = toCommentVO(c);
            List<com.stcloud.team.entity.TeamComment> replies = teamCommentMapper.selectList(
                    new LambdaQueryWrapper<com.stcloud.team.entity.TeamComment>()
                            .eq(com.stcloud.team.entity.TeamComment::getParentId, c.getId())
                            .orderByAsc(com.stcloud.team.entity.TeamComment::getCreatedAt));
            vo.setReplies(replies.stream().map(this::toCommentVO).toList());
            return vo;
        }).toList();
        return Result.success(voList);
    }

    @Override
    @Transactional
    public Result<TeamCommentVO> addComment(Long spaceId, CommentRequest request) {
        checkPermission(spaceId, 2);
        com.stcloud.team.entity.TeamComment comment = new com.stcloud.team.entity.TeamComment();
        comment.setSpaceId(spaceId);
        comment.setNodeId(request.getNodeId());
        comment.setUserId(UserContext.getUserId());
        comment.setContent(request.getContent());
        comment.setParentId(request.getParentId());
        comment.setMentions(request.getMentions());
        teamCommentMapper.insert(comment);

        // @提及发通知
        if (request.getMentions() != null && !request.getMentions().isEmpty()) {
            for (String uid : request.getMentions().split(",")) {
                try {
                    Long mentionedUserId = Long.parseLong(uid.trim());
                    if (!mentionedUserId.equals(UserContext.getUserId())) {
                        SysUser commenter = sysUserMapper.selectById(UserContext.getUserId());
                        notificationHelper.notify(mentionedUserId, "MENTION", "你在评论中被@提及",
                                (commenter != null ? commenter.getNickname() : "有人") + " 在评论中@了你", "comment", comment.getId());
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        activityHelper.log(spaceId, "FILE_COMMENT", "FILE", request.getNodeId(), "发表评论");
        return Result.success(toCommentVO(comment));
    }

    @Override
    @Transactional
    public Result<Void> updateComment(Long spaceId, Long commentId, String content) {
        com.stcloud.team.entity.TeamComment comment = teamCommentMapper.selectById(commentId);
        if (comment == null || !comment.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评论不存在");
        }
        // 仅本人可编辑
        if (!comment.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "仅评论人可编辑");
        }
        comment.setContent(content);
        teamCommentMapper.updateById(comment);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> deleteComment(Long spaceId, Long commentId) {
        com.stcloud.team.entity.TeamComment comment = teamCommentMapper.selectById(commentId);
        if (comment == null || !comment.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评论不存在");
        }
        Long userId = UserContext.getUserId();
        // 本人或管理员可删除
        boolean isOwner = comment.getUserId().equals(userId);
        boolean isAdmin = checkPermission(spaceId, null, 0) == 0;
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "无权删除该评论");
        }
        teamCommentMapper.deleteById(commentId);
        // 删除子回复
        teamCommentMapper.delete(new LambdaQueryWrapper<com.stcloud.team.entity.TeamComment>()
                .eq(com.stcloud.team.entity.TeamComment::getParentId, commentId));
        return Result.success();
    }

    // ==================== P1 新增：空间置顶 ====================

    @Override
    @Transactional
    public Result<Void> togglePin(Long spaceId) {
        Long userId = UserContext.getUserId();
        TeamMember member = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, userId));
        if (member == null) throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED);
        member.setIsPinned(member.getIsPinned() != null && member.getIsPinned() == 1 ? 0 : 1);
        teamMemberMapper.updateById(member);
        return Result.success();
    }

    private TeamCommentVO toCommentVO(com.stcloud.team.entity.TeamComment comment) {
        TeamCommentVO vo = new TeamCommentVO();
        vo.setId(comment.getId());
        vo.setSpaceId(comment.getSpaceId());
        vo.setNodeId(comment.getNodeId());
        vo.setUserId(comment.getUserId());
        SysUser user = sysUserMapper.selectById(comment.getUserId());
        vo.setUsername(user != null ? user.getUsername() : "未知");
        vo.setNickname(user != null ? user.getNickname() : "未知");
        vo.setAvatar(user != null ? user.getAvatar() : null);
        vo.setContent(comment.getContent());
        vo.setParentId(comment.getParentId());
        vo.setMentions(comment.getMentions());
        vo.setCreatedAt(comment.getCreatedAt());
        return vo;
    }

    @Override
    public void checkNotLocked(Long nodeId) {
        if (nodeId == null) return;
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null) return;
        if (node.getLockedBy() != null) {
            // 检查锁是否过期（过期视为未锁定）
            if (node.getLockExpireAt() == null || node.getLockExpireAt().isAfter(LocalDateTime.now())) {
                SysUser locker = sysUserMapper.selectById(node.getLockedBy());
                String lockerName = locker != null ? locker.getNickname() : "他人";
                // 锁定人自己可以操作自己的锁定文件
                if (!node.getLockedBy().equals(UserContext.getUserId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "文件被" + lockerName + "锁定，无法操作");
                }
            }
        }
    }
    // ==================== P2 新增：文件锁定 ====================

    @Override
    @Transactional
    public Result<Void> lockFile(Long spaceId, Long nodeId, Integer hours) {
        // 锁定=内容修改操作，按 rename 权限点校验（与控制器 @PreAuthorize file:rename 一致）
        requirePermissions(spaceId, nodeId, FolderPermissionService.PERM_RENAME);
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null) throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        if (node.getLockedBy() != null) {
            if (node.getLockExpireAt() == null || node.getLockExpireAt().isAfter(LocalDateTime.now())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文件已被锁定");
            }
        }
        // 显式 set 三列：hours=0（永久锁）时 lockExpireAt 必须写 NULL，
        // 避免覆盖旧的过期时间导致"永久锁仍残留过期时间"
        LocalDateTime now = LocalDateTime.now();
        fileNodeMapper.update(null, new LambdaUpdateWrapper<FileNode>()
                .eq(FileNode::getId, nodeId)
                .set(FileNode::getLockedBy, UserContext.getUserId())
                .set(FileNode::getLockedAt, now)
                .set(FileNode::getLockExpireAt,
                        hours != null && hours > 0 ? now.plusHours(hours) : null));
        activityHelper.log(spaceId, "FILE_LOCK", "FILE", nodeId, node.getName());
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> unlockFile(Long spaceId, Long nodeId) {
        requirePermissions(spaceId, nodeId, FolderPermissionService.PERM_VIEW);
        FileNode node = fileNodeMapper.selectById(nodeId);
        if (node == null) throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        if (node.getLockedBy() == null) return Result.success();
        Long userId = UserContext.getUserId();
        boolean isLocker = node.getLockedBy().equals(userId);
        boolean isAdmin = checkPermission(spaceId, null, 0) == 0;
        if (!isLocker && !isAdmin) {
            throw new BusinessException(ResultCode.TEAM_PERMISSION_DENIED, "仅锁定人或管理员可解锁");
        }
        // updateById 默认不更新 NULL 字段，必须显式 set NULL 才能清空锁定列
        fileNodeMapper.update(null, new LambdaUpdateWrapper<FileNode>()
                .eq(FileNode::getId, nodeId)
                .set(FileNode::getLockedBy, null)
                .set(FileNode::getLockedAt, null)
                .set(FileNode::getLockExpireAt, null));
        activityHelper.log(spaceId, "FILE_UNLOCK", "FILE", nodeId, node.getName());
        return Result.success();
    }

    @Override
    public Result<List<TeamRoleVO>> listRoles(Long spaceId) {
        checkPermission(spaceId, 0);
        List<TeamRoleVO> result = new java.util.ArrayList<>();
        // 预设角色均为启用状态
        result.add(toRoleVO(0L, spaceId, "管理员", presetPerms(0), RoleStatus.ENABLED.getCode(), true));
        result.add(toRoleVO(1L, spaceId, "编辑者", presetPerms(1), RoleStatus.ENABLED.getCode(), true));
        result.add(toRoleVO(2L, spaceId, "查看者", presetPerms(2), RoleStatus.ENABLED.getCode(), true));
        List<com.stcloud.team.entity.TeamRole> roles = teamRoleMapper.selectList(
                new LambdaQueryWrapper<com.stcloud.team.entity.TeamRole>().eq(com.stcloud.team.entity.TeamRole::getSpaceId, spaceId));
        roles.forEach(r -> result.add(toRoleVO(r.getId(), spaceId, r.getName(), r.getPermissions(), r.getStatus(), false)));
        return Result.success(result);
    }

    @Override
    @Transactional
    public Result<TeamRoleVO> createRole(Long spaceId, TeamRoleRequest request) {
        checkPermission(spaceId, 0);
        com.stcloud.team.entity.TeamRole role = new com.stcloud.team.entity.TeamRole();
        role.setSpaceId(spaceId); role.setName(request.getName());
        role.setPermissions(request.getPermissions());
        // 新建自定义角色默认启用
        role.setStatus(RoleStatus.ENABLED.getCode());
        teamRoleMapper.insert(role);
        // 角色变更：权限缓存失效（角色规则影响权限链）
        folderPermissionService.invalidateSpace(spaceId);
        return Result.success(toRoleVO(role.getId(), spaceId, role.getName(), role.getPermissions(),
                RoleStatus.ENABLED.getCode(), false));
    }

    @Override
    @Transactional
    public Result<Void> updateRole(Long spaceId, Long roleId, TeamRoleRequest request) {
        checkPermission(spaceId, 0);
        com.stcloud.team.entity.TeamRole role = teamRoleMapper.selectById(roleId);
        if (role == null || !role.getSpaceId().equals(spaceId)) throw new BusinessException(ResultCode.BAD_REQUEST, "角色不存在");
        role.setName(request.getName()); role.setPermissions(request.getPermissions());
        teamRoleMapper.updateById(role);
        // 角色变更：权限缓存失效
        folderPermissionService.invalidateSpace(spaceId);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> deleteRole(Long spaceId, Long roleId) {
        checkPermission(spaceId, 0);
        Long count = teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getRole, roleId));
        if (count > 0) throw new BusinessException(ResultCode.BAD_REQUEST, "角色正在使用中，无法删除");
        teamRoleMapper.deleteById(roleId);
        // 角色变更：权限缓存失效
        folderPermissionService.invalidateSpace(spaceId);
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> setExternalMember(Long spaceId, Long memberId, ExternalMemberRequest request) {
        checkPermission(spaceId, 0);
        TeamMember member = teamMemberMapper.selectById(memberId);
        if (member == null || !member.getSpaceId().equals(spaceId)) throw new BusinessException(ResultCode.TEAM_MEMBER_NOT_FOUND);
        member.setMemberType(request.getMemberType() != null ? request.getMemberType() : 0);
        member.setExpireAt(request.getExpireAt());
        teamMemberMapper.updateById(member);
        // 成员属性变更：权限缓存失效
        folderPermissionService.invalidateSpace(spaceId);
        return Result.success();
    }

    @Override
    public Result<Integer> getExternalConfig(Long spaceId) {
        checkPermission(spaceId, 0);
        com.stcloud.team.entity.TeamExternalConfig config = teamExternalConfigMapper.selectOne(
                new LambdaQueryWrapper<com.stcloud.team.entity.TeamExternalConfig>().eq(com.stcloud.team.entity.TeamExternalConfig::getSpaceId, spaceId));
        return Result.success(config != null ? config.getAllowExternal() : 0);
    }

    @Override
    @Transactional
    public Result<Void> setExternalConfig(Long spaceId, boolean allow) {
        checkPermission(spaceId, 0);
        com.stcloud.team.entity.TeamExternalConfig config = teamExternalConfigMapper.selectOne(
                new LambdaQueryWrapper<com.stcloud.team.entity.TeamExternalConfig>().eq(com.stcloud.team.entity.TeamExternalConfig::getSpaceId, spaceId));
        if (config == null) { config = new com.stcloud.team.entity.TeamExternalConfig(); config.setSpaceId(spaceId); config.setAllowExternal(allow ? 1 : 0); teamExternalConfigMapper.insert(config); }
        else { config.setAllowExternal(allow ? 1 : 0); teamExternalConfigMapper.updateById(config); }
        return Result.success();
    }

    @Override
    public Result<TeamStatsVO> getStats(Long spaceId, int days) {
        checkPermission(spaceId, 0);
        TeamStatsVO stats = new TeamStatsVO();
        TeamSpace space = teamSpaceMapper.selectById(spaceId);
        stats.setStorageUsed(space.getStorageUsed()); stats.setStorageQuota(space.getStorageQuota());
        Long fileCount = fileNodeMapper.selectCount(new LambdaQueryWrapper<FileNode>().eq(FileNode::getSpaceId, spaceId).eq(FileNode::getStatus, 0));
        stats.setFileCount(fileCount);
        List<FileNode> files = fileNodeMapper.selectList(new LambdaQueryWrapper<FileNode>().eq(FileNode::getSpaceId, spaceId).eq(FileNode::getStatus, 0).eq(FileNode::getNodeType, 1));
        java.util.Map<String, Long> typeCount = new java.util.HashMap<>();
        for (FileNode f : files) { typeCount.merge(categorizeFileType(f.getSuffix()), 1L, Long::sum); }
        stats.setFileTypeDistribution(typeCount.entrySet().stream().map(e -> { Map<String, Object> m = new java.util.HashMap<>(); m.put("type", e.getKey()); m.put("count", e.getValue()); return m; }).toList());
        List<TeamMember> members = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getSpaceId, spaceId).orderByDesc(TeamMember::getLastActiveAt).last("LIMIT 5"));
        stats.setMemberActivity(members.stream().map(m -> { Map<String, Object> map = new java.util.HashMap<>(); map.put("userId", m.getUserId()); SysUser user = sysUserMapper.selectById(m.getUserId()); map.put("nickname", user != null ? user.getNickname() : "未知"); map.put("lastActiveAt", m.getLastActiveAt()); return map; }).toList());
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<com.stcloud.team.entity.TeamActivity> activities = teamActivityMapper.selectList(new LambdaQueryWrapper<com.stcloud.team.entity.TeamActivity>().eq(com.stcloud.team.entity.TeamActivity::getSpaceId, spaceId).ge(com.stcloud.team.entity.TeamActivity::getCreatedAt, since));
        stats.setOperationStats(activities.stream().collect(java.util.stream.Collectors.groupingBy(com.stcloud.team.entity.TeamActivity::getAction, java.util.stream.Collectors.counting())).entrySet().stream().map(e -> { Map<String, Object> m = new java.util.HashMap<>(); m.put("action", e.getKey()); m.put("count", e.getValue()); return m; }).toList());
        return Result.success(stats);
    }

    private String categorizeFileType(String suffix) {
        if (suffix == null) return "其他";
        suffix = suffix.toLowerCase();
        if (suffix.matches("jpg|jpeg|png|gif|bmp|webp|svg")) return "图片";
        if (suffix.matches("mp4|avi|mov|wmv|flv|mkv")) return "视频";
        if (suffix.matches("mp3|wav|flac|aac|ogg")) return "音频";
        if (suffix.matches("doc|docx|pdf|txt|xls|xlsx|ppt|pptx|md")) return "文档";
        if (suffix.matches("zip|rar|7z|tar|gz")) return "压缩包";
        return "其他";
    }

    private String presetPerms(int role) {
        // 查看者(2)：仅 view=true，download=false（权限模型重设计已确认）
        return FolderPermissionService.permissionsToJson(FolderPermissionService.presetPermissions(role));
    }

    private TeamRoleVO toRoleVO(Long id, Long spaceId, String name, String permissions, int status, boolean isPreset) {
        TeamRoleVO vo = new TeamRoleVO();
        vo.setId(id); vo.setSpaceId(spaceId); vo.setName(name);
        vo.setPermissions(permissions); vo.setStatus(status); vo.setIsPreset(isPreset);
        return vo;
    }

    // ==================== 私有方法 ====================

    /** 生成 32 位随机邀请码（大小写字母+数字） */
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private String generateInviteCode() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private TeamSpaceVO toSpaceVO(TeamSpace space, int memberCount, Integer isPinned) {
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
        vo.setMemberCount(memberCount); vo.setIsPinned(isPinned != null ? isPinned : 0);
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

    private TeamInviteVO toInviteVO(TeamInvite invite) {
        TeamInviteVO vo = new TeamInviteVO();
        vo.setId(invite.getId());
        vo.setSpaceId(invite.getSpaceId());
        vo.setInviteCode(invite.getInviteCode());
        vo.setRole(invite.getRole());
        vo.setCreatedBy(invite.getCreatedBy());
        SysUser creator = sysUserMapper.selectById(invite.getCreatedBy());
        vo.setCreatedByName(creator != null ? creator.getNickname() : "未知");
        vo.setExpireAt(invite.getExpireAt());
        vo.setStatus(invite.getStatus());
        vo.setCreatedAt(invite.getCreatedAt());
        return vo;
    }

    private TeamActivityVO toActivityVO(TeamActivity activity) {
        TeamActivityVO vo = new TeamActivityVO();
        vo.setId(activity.getId());
        vo.setUserId(activity.getUserId());
        vo.setUsername(activity.getUsername());
        vo.setNickname(activity.getNickname());
        vo.setAction(activity.getAction());
        vo.setTargetType(activity.getTargetType());
        vo.setTargetId(activity.getTargetId());
        vo.setTargetName(activity.getTargetName());
        vo.setDetail(activity.getDetail());
        vo.setCreatedAt(activity.getCreatedAt());
        return vo;
    }
}
