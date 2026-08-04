package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.common.response.Result;
import com.stcloud.team.dto.*;

public interface TeamService {

    Result<TeamSpaceVO> createSpace(CreateSpaceRequest request);

    Result<IPage<TeamSpaceVO>> listSpaces(int page, int size);

    Result<TeamSpaceVO> getSpace(Long spaceId);

    Result<Void> updateSpace(Long spaceId, CreateSpaceRequest request);

    Result<Void> deleteSpace(Long spaceId);

    Result<TeamMemberVO> inviteMember(Long spaceId, InviteMemberRequest request);

    Result<IPage<TeamMemberVO>> listMembers(Long spaceId, int page, int size);

    Result<Void> updateMemberRole(Long spaceId, Long memberId, Integer role);

    Result<Void> removeMember(Long spaceId, Long memberId);

    /**
     * 检查用户在空间中的权限，返回角色(0-管理员 1-编辑者 2-查看者)，无权限抛异常
     */
    Integer checkPermission(Long spaceId, Integer minRole);
}
