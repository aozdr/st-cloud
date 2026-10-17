package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.CreateInviteRequest;
import com.stcloud.team.dto.CreateSpaceRequest;
import com.stcloud.team.dto.InviteMemberRequest;
import com.stcloud.team.dto.TeamMemberVO;
import com.stcloud.team.dto.TeamSpaceVO;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 团队自定义角色提权链回归测试（P0-4）。
 *
 * <p>修复前 {@code FolderPermissionService.legacyLevelOf} 把 {@code manage_members}
 * 与 {@code manage_settings} 一并映射为空间管理员等级 0，于是"只被授予管理成员"的自定义角色：</p>
 * <ul>
 *   <li>可以通过 {@code checkPermission(spaceId, 0)} 进入改设置 / 改角色 / 写文件夹 ACL 等入口；</li>
 *   <li>可以直接 {@code updateMemberRole(自己, role=0)} 一步变成内置管理员；</li>
 *   <li>可以通过邀请成员或邀请链接批量下发内置管理员。</li>
 * </ul>
 *
 * <p>修复后：manage_members 只保留"查看等级"，成员管理走独立的 checkMemberManage；
 * 禁止改自己的角色、禁止非 owner 授予 role=0。</p>
 */
class TeamRoleEscalationIntegrationTest extends AbstractTeamIntegrationTest {

    private static final Long OWNER_ID = 100L;
    private static final Long MANAGER_ID = 200L;
    private static final Long INVITEE_ID = 300L;
    private static final Long TENANT_ID = 1L;
    /** 自定义角色 ID 必须 >= 100，避免与内置 0/1/2 冲突 */
    private static final Long CUSTOM_ROLE_ID = 9007199254741001L;

    private CreateSpaceRequest spaceRequest() {
        CreateSpaceRequest request = new CreateSpaceRequest();
        request.setSpaceName("提权回归空间");
        request.setDescription("P0-4");
        request.setStorageQuota(1024L * 1024 * 1024);
        return request;
    }

    private Long createSpaceAndInviteManager() {
        insertUser(OWNER_ID, TENANT_ID, "owner");
        insertUser(MANAGER_ID, TENANT_ID, "manager");
        insertUser(INVITEE_ID, TENANT_ID, "invitee");

        setUpUser(OWNER_ID, TENANT_ID);
        TeamSpaceVO space = teamService.createSpace(spaceRequest()).getData();

        InviteMemberRequest invite = new InviteMemberRequest();
        invite.setUserId(MANAGER_ID);
        TeamMemberVO manager = teamService.inviteMember(space.getId(), invite).getData();

        // 只授予"管理成员 + 查看"的自定义角色
        TeamRole role = new TeamRole();
        role.setId(CUSTOM_ROLE_ID);
        role.setSpaceId(space.getId());
        role.setName("成员管理员");
        role.setPermissions("{\"view\":true,\"manage_members\":true}");
        role.setStatus(1);
        teamRoleMapper.insert(role);
        teamService.updateMemberRole(space.getId(), manager.getId(), CUSTOM_ROLE_ID);
        return space.getId();
    }

    @Test
    @DisplayName("持有 manage_members 的成员不能把自己改成内置管理员")
    void manageMembersCannotPromoteSelf() {
        Long spaceId = createSpaceAndInviteManager();
        TeamMember self = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, MANAGER_ID));

        setUpUser(MANAGER_ID, TENANT_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamService.updateMemberRole(spaceId, self.getId(), 0L));

        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());
        assertEquals(CUSTOM_ROLE_ID, teamMemberMapper.selectById(self.getId()).getRole(),
                "角色不得被自我提升");
    }

    @Test
    @DisplayName("manage_members 不再等同于空间管理员：不能改空间设置")
    void manageMembersCannotUpdateSpaceSettings() {
        Long spaceId = createSpaceAndInviteManager();

        setUpUser(MANAGER_ID, TENANT_ID);
        CreateSpaceRequest update = new CreateSpaceRequest();
        update.setSpaceName("被篡改的空间名");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamService.updateSpace(spaceId, update));

        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("manage_members 不能邀请内置管理员，也不能创建管理员邀请链接")
    void manageMembersCannotGrantAdminRole() {
        Long spaceId = createSpaceAndInviteManager();

        setUpUser(MANAGER_ID, TENANT_ID);
        InviteMemberRequest invite = new InviteMemberRequest();
        invite.setUserId(INVITEE_ID);
        invite.setRole(0L);
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(),
                assertThrows(BusinessException.class,
                        () -> teamService.inviteMember(spaceId, invite)).getCode());

        CreateInviteRequest link = new CreateInviteRequest();
        link.setRole(0L);
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(),
                assertThrows(BusinessException.class,
                        () -> teamService.createInvite(spaceId, link)).getCode());
    }

    @Test
    @DisplayName("manage_members 不能移除空间拥有者")
    void manageMembersCannotRemoveOwner() {
        Long spaceId = createSpaceAndInviteManager();
        TeamMember owner = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, OWNER_ID));

        setUpUser(MANAGER_ID, TENANT_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamService.removeMember(spaceId, owner.getId()));

        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("manage_members 仍可邀请普通成员（修复不误伤成员管理能力）")
    void manageMembersCanStillInviteRegularMember() {
        Long spaceId = createSpaceAndInviteManager();

        setUpUser(MANAGER_ID, TENANT_ID);
        InviteMemberRequest invite = new InviteMemberRequest();
        invite.setUserId(INVITEE_ID);
        assertDoesNotThrow(() -> teamService.inviteMember(spaceId, invite));
    }

    @Test
    @DisplayName("空间拥有者仍可授予管理员（保留合法路径）")
    void ownerCanStillGrantAdmin() {
        Long spaceId = createSpaceAndInviteManager();
        TeamMember manager = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getSpaceId, spaceId).eq(TeamMember::getUserId, MANAGER_ID));

        setUpUser(OWNER_ID, TENANT_ID);
        assertDoesNotThrow(() -> teamService.updateMemberRole(spaceId, manager.getId(), 0L));
        assertEquals(0L, teamMemberMapper.selectById(manager.getId()).getRole());
    }
}
