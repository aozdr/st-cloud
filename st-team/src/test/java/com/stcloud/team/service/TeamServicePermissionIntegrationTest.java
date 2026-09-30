package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.CreateSpaceRequest;
import com.stcloud.team.dto.CreateInviteRequest;
import com.stcloud.team.dto.TeamRoleRequest;
import com.stcloud.team.dto.FolderPermissionRequest;
import com.stcloud.team.dto.FolderPermissionVO;
import com.stcloud.team.dto.InviteMemberRequest;
import com.stcloud.team.dto.TeamMemberVO;
import com.stcloud.team.dto.TeamRoleVO;
import com.stcloud.team.dto.TeamSpaceVO;
import com.stcloud.team.entity.TeamFolderPermission;
import com.stcloud.team.entity.TeamRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 团队权限模型核心集成测试（TASK-PERM-BE1）。
 * <p>
 * 覆盖：查看者预设 download=false、requirePermissions 权限点校验、
 * resolveMyPermissions 并集（member/all 规则）、自定义角色（>=100）、
 * 角色停用回退查看者、管理员直通、非成员拒绝。真实 H2 + MyBatis-Plus。
 */
class TeamServicePermissionIntegrationTest extends AbstractTeamIntegrationTest {

    private CreateSpaceRequest spaceRequest(String name) {
        CreateSpaceRequest request = new CreateSpaceRequest();
        request.setSpaceName(name);
        request.setDescription("权限集成测试空间");
        request.setStorageQuota(1024L * 1024 * 1024);
        return request;
    }

    private Long createSpaceAs(Long userId) {
        setUpUser(userId, 1L);
        com.stcloud.common.response.Result<TeamSpaceVO> result = teamService.createSpace(spaceRequest("权限空间-" + userId));
        assertEquals(200, result.getCode());
        return result.getData().getId();
    }

    private Long inviteMemberAs(Long spaceId, Long ownerId, Long userId) {
        setUpUser(ownerId, 1L);
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(userId);
        TeamMemberVO vo = teamService.inviteMember(spaceId, request).getData();
        return vo.getId();
    }

    private FileNode insertNode(Long tenantId, Long ownerId, Long spaceId, String name) {
        return insertFileNode(tenantId, ownerId, spaceId, name, 1, 0);
    }

    private void addFolderRule(Long spaceId, Long nodeId, String subjectType, Long subjectId, String permissionsJson) {
        TeamFolderPermission rule = new TeamFolderPermission();
        rule.setSpaceId(spaceId);
        rule.setFolderNodeId(nodeId);
        rule.setSubjectType(subjectType);
        rule.setSubjectId(subjectId);
        rule.setPermissions(permissionsJson);
        // 兼容 permission NOT NULL：permissions JSON 为权威，单值仅作历史兼容列
        rule.setPermission(2);
        teamFolderPermissionMapper.insert(rule);
    }

    private FolderPermissionRequest.PermissionRule rule(String subjectType, String subjectId, String permissionsJson) {
        FolderPermissionRequest.PermissionRule rule = new FolderPermissionRequest.PermissionRule();
        rule.setSubjectType(subjectType);
        rule.setSubjectId(subjectId);
        rule.setPermissions(permissionsJson);
        return rule;
    }

    private FolderPermissionRequest ruleRequest(String subjectType, String subjectId, String permissionsJson) {
        FolderPermissionRequest request = new FolderPermissionRequest();
        request.setRules(List.of(rule(subjectType, subjectId, permissionsJson)));
        return request;
    }

    @Test
    void viewerPresetHasNoDownload() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);
        setUpUser(100L, 1L);

        TeamRoleVO viewer = teamService.listRoles(spaceId).getData().stream()
                .filter(r -> "查看者".equals(r.getName()))
                .findFirst().orElseThrow();
        Set<String> perms = FolderPermissionService.parsePermissions(viewer.getPermissions());
        assertTrue(perms.contains("view"));
        assertFalse(perms.contains("download"));
    }

    @Test
    void requirePermissions_viewerCanViewButNotDownload() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "viewer");
        Long spaceId = createSpaceAs(100L);
        inviteMemberAs(spaceId, 100L, 200L); // 默认查看者
        FileNode node = insertNode(1L, 100L, spaceId, "doc.txt");

        setUpUser(200L, 1L);
        // 查看者：view 通过
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, node.getId(), "view"));
        // 查看者：download 拒绝（查看者预设 download=false）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceId, node.getId(), "download"));
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());
    }

    @Test
    void resolveMyPermissions_unionsMemberRule() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "viewer");
        Long spaceId = createSpaceAs(100L);
        inviteMemberAs(spaceId, 100L, 200L);
        FileNode node = insertNode(1L, 100L, spaceId, "doc.txt");

        // 管理员给节点配置 member 规则 {download}
        setUpUser(100L, 1L);
        addFolderRule(spaceId, node.getId(), "member", 200L, "{\"download\":true}");

        setUpUser(200L, 1L);
        Set<String> perms = teamService.resolveMyPermissions(spaceId, node.getId());
        // 查看者 {view} ∪ member 规则 {download} → {view,download}
        assertTrue(perms.contains("view"));
        assertTrue(perms.contains("download"));
    }

    @Test
    void resolveMyPermissions_unionsAllRule() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "viewer");
        Long spaceId = createSpaceAs(100L);
        inviteMemberAs(spaceId, 100L, 200L);
        FileNode node = insertNode(1L, 100L, spaceId, "doc.txt");

        setUpUser(100L, 1L);
        addFolderRule(spaceId, node.getId(), "all", 0L, "{\"download\":true}");

        setUpUser(200L, 1L);
        Set<String> perms = teamService.resolveMyPermissions(spaceId, node.getId());
        assertTrue(perms.contains("view"));
        assertTrue(perms.contains("download"));
    }

    @Test
    void adminBypassFolderRules() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);
        FileNode node = insertNode(1L, 100L, spaceId, "doc.txt");

        setUpUser(100L, 1L);
        addFolderRule(spaceId, node.getId(), "member", 100L, "{\"view\":true}");

        // 管理员直通：直接全部权限点，不受文件夹规则限制
        Set<String> perms = teamService.resolveMyPermissions(spaceId, node.getId());
        assertTrue(perms.contains(FolderPermissionService.PERM_MANAGE_SETTINGS));
        assertTrue(perms.contains(FolderPermissionService.PERM_DELETE));
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, node.getId(), "manage_settings"));
    }

    @Test
    void customRolePermissionsApplied() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "uploader");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);

        // 自定义角色 id=100：上传者 = view+upload
        setUpUser(100L, 1L);
        TeamRole role = new TeamRole();
        role.setId(100L);
        role.setSpaceId(spaceId);
        role.setName("上传者");
        role.setPermissions("{\"view\":true,\"upload\":true}");
        role.setStatus(1);
        teamRoleMapper.insert(role);
        teamService.updateMemberRole(spaceId, memberId, 100L);

        FileNode node = insertNode(1L, 100L, spaceId, "doc.txt");
        setUpUser(200L, 1L);
        Set<String> perms = teamService.resolveMyPermissions(spaceId, node.getId());
        assertEquals(Set.of("view", "upload"), perms);
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, node.getId(), "upload"));
        assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceId, node.getId(), "delete"));
    }

    @Test
    void emptyCustomRoleNeverPassesEitherLegacyPermissionOverload() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "empty-role-member");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);
        setUpUser(100L, 1L);
        TeamRole empty = new TeamRole();
        empty.setId(9007199254740993L);
        empty.setSpaceId(spaceId);
        empty.setName("无基础权限");
        empty.setPermissions("{}");
        empty.setStatus(1);
        teamRoleMapper.insert(empty);
        teamService.updateMemberRole(spaceId, memberId, empty.getId());
        FileNode node = insertNode(1L, 100L, spaceId, "empty-role.txt");
        setUpUser(200L, 1L);
        for (Integer minimum : new Integer[]{null, 2}) {
            assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), assertThrows(BusinessException.class,
                    () -> teamService.checkPermission(spaceId, minimum)).getCode());
            assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), assertThrows(BusinessException.class,
                    () -> teamService.checkPermission(spaceId, node.getId(), minimum)).getCode());
        }
        addFolderRule(spaceId, node.getId(), "member", 200L, "{\"view\":true,\"upload\":true}");
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, node.getId(), "view", "upload"));
        empty.setStatus(0);
        teamRoleMapper.updateById(empty);
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceId, node.getId(), "view")).getCode());
    }

    @Test
    void adjacentLargeRoleIdsMatchOnlyTheirOwnFolderAcl() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "role-member");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);
        setUpUser(100L, 1L);
        long[] roleIds = {9007199254740993L, 9007199254740995L};
        for (long id : roleIds) {
            TeamRole role = new TeamRole();
            role.setId(id);
            role.setSpaceId(spaceId);
            role.setName("精确角色-" + id);
            role.setPermissions("{}");
            role.setStatus(1);
            teamRoleMapper.insert(role);
        }
        FileNode first = insertNode(1L, 100L, spaceId, "role-a.txt");
        FileNode second = insertNode(1L, 100L, spaceId, "role-b.txt");
        addFolderRule(spaceId, first.getId(), "role", roleIds[0], "{\"view\":true}");
        addFolderRule(spaceId, second.getId(), "role", roleIds[1], "{\"view\":true}");

        for (int selected = 0; selected < 2; selected++) {
            setUpUser(100L, 1L);
            teamService.updateMemberRole(spaceId, memberId, roleIds[selected]);
            setUpUser(200L, 1L);
            Long allowed = selected == 0 ? first.getId() : second.getId();
            Long denied = selected == 0 ? second.getId() : first.getId();
            assertEquals(2, teamService.checkPermission(spaceId, allowed, null));
            assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, allowed, "view"));
            assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), assertThrows(BusinessException.class,
                    () -> teamService.requirePermissions(spaceId, denied, "view")).getCode());
        }
    }

    @Test
    void invalidCustomRolesCannotReplaceMemberRole() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member-invalid-role");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);
        setUpUser(100L, 1L);
        long[] ids = {9007199254740993L, 9007199254740995L, 9007199254740997L, 9007199254740999L};
        for (int index = 0; index < ids.length; index++) {
            TeamRole role = new TeamRole();
            role.setId(ids[index]);
            role.setSpaceId(index == 2 ? spaceId + 999 : spaceId);
            role.setTenantId(index == 3 ? 2L : 1L);
            role.setName("无效角色-" + index);
            role.setPermissions("{\"view\":true}");
            role.setStatus(index == 0 ? 0 : 1);
            teamRoleMapper.insert(role);
            if (index == 1) teamRoleMapper.deleteById(role.getId());
        }
        for (long roleId : new long[]{9007199254741001L, ids[0], ids[1], ids[2], ids[3]}) {
            assertThrows(BusinessException.class, () -> teamService.updateMemberRole(spaceId, memberId, roleId));
            assertEquals(2L, teamMemberMapper.selectById(memberId).getRole());
        }
    }

    @Test
    void referencedCustomRoleCannotBeDeletedUntilMemberAndActiveInviteReleaseIt() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "role-user");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);
        setUpUser(100L, 1L);
        TeamRole role = new TeamRole();
        role.setId(9007199254740993L);
        role.setSpaceId(spaceId);
        role.setName("被引用角色");
        role.setPermissions("{\"view\":true}");
        role.setStatus(1);
        teamRoleMapper.insert(role);
        teamService.updateMemberRole(spaceId, memberId, role.getId());
        assertThrows(BusinessException.class, () -> teamService.deleteRole(spaceId, role.getId()));
        assertTrue(teamRoleMapper.selectById(role.getId()) != null);

        teamService.updateMemberRole(spaceId, memberId, 2L);
        CreateInviteRequest activeRequest = new CreateInviteRequest();
        activeRequest.setRole(role.getId());
        Long inviteId = teamService.createInvite(spaceId, activeRequest).getData().getId();
        assertThrows(BusinessException.class, () -> teamService.deleteRole(spaceId, role.getId()));
        assertTrue(teamRoleMapper.selectById(role.getId()) != null);

        teamService.revokeInvite(spaceId, inviteId);
        teamService.deleteRole(spaceId, role.getId());
        assertTrue(teamRoleMapper.selectById(role.getId()) == null);

        TeamRole expiredRole = new TeamRole();
        expiredRole.setId(9007199254740995L);
        expiredRole.setSpaceId(spaceId);
        expiredRole.setName("仅过期邀请引用");
        expiredRole.setPermissions("{\"view\":true}");
        expiredRole.setStatus(1);
        teamRoleMapper.insert(expiredRole);
        CreateInviteRequest expiredRequest = new CreateInviteRequest();
        expiredRequest.setRole(expiredRole.getId());
        expiredRequest.setExpireAt(LocalDateTime.now().minusMinutes(1));
        teamService.createInvite(spaceId, expiredRequest);
        teamService.deleteRole(spaceId, expiredRole.getId());
        assertTrue(teamRoleMapper.selectById(expiredRole.getId()) == null);
    }

    @Test
    void inviteAcceptanceRevalidatesRoleExpiryAndRevocation() {
        insertUser(100L, 1L, "owner");
        insertUser(300L, 1L, "invitee");
        Long spaceId = createSpaceAs(100L);
        setUpUser(100L, 1L);
        TeamRole role = new TeamRole();
        role.setId(9007199254740993L);
        role.setSpaceId(spaceId);
        role.setName("邀请角色");
        role.setPermissions("{\"view\":true}");
        role.setStatus(1);
        teamRoleMapper.insert(role);
        CreateInviteRequest roleRequest = new CreateInviteRequest();
        roleRequest.setRole(role.getId());
        String disabledCode = teamService.createInvite(spaceId, roleRequest).getData().getInviteCode();
        role.setStatus(0);
        teamRoleMapper.updateById(role);
        setUpUser(300L, 1L);
        assertThrows(BusinessException.class, () -> teamService.joinByCode(disabledCode));

        setUpUser(100L, 1L);
        role.setStatus(1);
        teamRoleMapper.updateById(role);
        String deletedCode = teamService.createInvite(spaceId, roleRequest).getData().getInviteCode();
        teamRoleMapper.deleteById(role.getId()); // 模拟历史失效引用，绕开正常删除保护
        setUpUser(300L, 1L);
        assertThrows(BusinessException.class, () -> teamService.joinByCode(deletedCode));

        setUpUser(100L, 1L);
        CreateInviteRequest expired = new CreateInviteRequest();
        expired.setExpireAt(LocalDateTime.now().minusMinutes(1));
        String expiredCode = teamService.createInvite(spaceId, expired).getData().getInviteCode();
        var revoked = teamService.createInvite(spaceId, new CreateInviteRequest()).getData();
        teamService.revokeInvite(spaceId, revoked.getId());
        setUpUser(300L, 1L);
        assertThrows(BusinessException.class, () -> teamService.joinByCode(expiredCode));
        assertThrows(BusinessException.class, () -> teamService.joinByCode(revoked.getInviteCode()));
        assertEquals(0L, teamMemberMapper.selectCount(new LambdaQueryWrapper<com.stcloud.team.entity.TeamMember>()
                .eq(com.stcloud.team.entity.TeamMember::getSpaceId, spaceId)
                .eq(com.stcloud.team.entity.TeamMember::getUserId, 300L)));
    }

    @Test
    void ownerPresetAndCrossSpaceRoleProtectionsPreserveExistingState() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);
        setUpUser(100L, 1L);
        Long ownerMemberId = teamMemberMapper.selectOne(new LambdaQueryWrapper<com.stcloud.team.entity.TeamMember>()
                .eq(com.stcloud.team.entity.TeamMember::getSpaceId, spaceId)
                .eq(com.stcloud.team.entity.TeamMember::getUserId, 100L)).getId();
        assertThrows(BusinessException.class, () -> teamService.updateMemberRole(spaceId, ownerMemberId, 2L));
        assertEquals(0L, teamMemberMapper.selectById(ownerMemberId).getRole());

        TeamRoleRequest update = new TeamRoleRequest();
        update.setName("越界修改");
        update.setPermissions("{\"view\":true}");
        for (long presetId : new long[]{0L, 1L, 2L}) {
            assertThrows(BusinessException.class, () -> teamService.updateRole(spaceId, presetId, update));
            assertThrows(BusinessException.class, () -> teamService.deleteRole(spaceId, presetId));
        }

        Long otherSpace = teamService.createSpace(spaceRequest("另一个空间")).getData().getId();
        TeamRole foreign = new TeamRole();
        foreign.setId(9007199254740993L);
        foreign.setSpaceId(otherSpace);
        foreign.setName("别的空间角色");
        foreign.setPermissions("{\"view\":true}");
        foreign.setStatus(1);
        teamRoleMapper.insert(foreign);
        assertThrows(BusinessException.class, () -> teamService.updateRole(spaceId, foreign.getId(), update));
        assertThrows(BusinessException.class, () -> teamService.deleteRole(spaceId, foreign.getId()));
        assertEquals("别的空间角色", teamRoleMapper.selectById(foreign.getId()).getName());
    }

    @Test
    void disabledCustomRoleDeniesPermissions() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);

        // 已分配的自定义角色停用后必须立即拒绝授权。
        setUpUser(100L, 1L);
        TeamRole role = new TeamRole();
        role.setId(101L);
        role.setSpaceId(spaceId);
        role.setName("停用角色");
        role.setPermissions("{\"view\":true,\"upload\":true,\"download\":true}");
        role.setStatus(1);
        teamRoleMapper.insert(role);
        teamService.updateMemberRole(spaceId, memberId, 101L);
        role.setStatus(0);
        teamRoleMapper.updateById(role);

        setUpUser(200L, 1L);
        assertThrows(BusinessException.class,
                () -> teamService.resolveMyPermissions(spaceId, null));
        assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceId, null, "download"));
    }

    @Test
    void nonMemberDenied() {
        insertUser(100L, 1L, "owner");
        insertUser(300L, 1L, "outsider");
        Long spaceId = createSpaceAs(100L);

        setUpUser(300L, 1L);
        assertThrows(BusinessException.class, () -> teamService.resolveMyPermissions(spaceId, null));
        assertThrows(BusinessException.class, () -> teamService.requirePermissions(spaceId, null, "view"));
    }

    @Test
    void setFolderPermissions_crossSpaceFolderNodeRejected() {
        // 攻击者自建空间成为管理员后，尝试对受害者空间的文件夹节点配置权限（P1 跨空间 ACL 注入）
        insertUser(100L, 1L, "attacker");
        insertUser(200L, 1L, "victim");
        Long attackerSpace = createSpaceAs(100L);
        Long victimSpace = createSpaceAs(200L);
        FileNode victimNode = insertNode(1L, 200L, victimSpace, "victim-dir");

        // 攻击者以自己空间的管理员身份写受害者空间的节点 → 拒绝
        setUpUser(100L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(attackerSpace, victimNode.getId(),
                        ruleRequest("all", "0", "{\"view\":true}")));
        assertEquals(ResultCode.TEAM_PERMISSION_DENIED.getCode(), ex.getCode());

        // 读路径同样拒绝跨空间访问
        assertThrows(BusinessException.class,
                () -> teamService.getFolderPermissions(attackerSpace, victimNode.getId()));

        // 被拒后目标文件夹不得残留任何规则
        assertEquals(0, teamFolderPermissionMapper.selectCount(
                new LambdaQueryWrapper<TeamFolderPermission>()
                        .eq(TeamFolderPermission::getFolderNodeId, victimNode.getId())));

        // 同空间合法配置不受影响
        setUpUser(200L, 1L);
        assertDoesNotThrow(() -> teamService.setFolderPermissions(victimSpace, victimNode.getId(),
                ruleRequest("all", "0", "{\"view\":true}")));
    }

    @Test
    void rootPermissionsSaveReadAndStayWithinSpace() {
        insertUser(100L, 1L, "owner-a");
        insertUser(200L, 1L, "owner-b");
        insertUser(300L, 1L, "viewer");
        Long spaceA = createSpaceAs(100L);
        Long spaceB = createSpaceAs(200L);
        inviteMemberAs(spaceA, 100L, 300L);
        inviteMemberAs(spaceB, 200L, 300L);
        FileNode nodeA = insertNode(1L, 100L, spaceA, "a.txt");
        FileNode nodeB = insertNode(1L, 200L, spaceB, "b.txt");

        setUpUser(100L, 1L);
        assertDoesNotThrow(() -> teamService.setFolderPermissions(spaceA, 0L,
                ruleRequest("all", "0", "{\"upload\":true}")));
        assertEquals(1, teamService.getFolderPermissions(spaceA, 0L).getData().size());
        assertEquals(spaceA, teamService.getFolderPermissions(spaceA, 0L).getData().get(0).getSpaceId());

        setUpUser(200L, 1L);
        assertDoesNotThrow(() -> teamService.setFolderPermissions(spaceB, 0L,
                ruleRequest("all", "0", "{\"download\":true}")));
        assertEquals(1, teamService.getFolderPermissions(spaceB, 0L).getData().size());

        setUpUser(300L, 1L);
        // 控制器创建文件夹/空白文件时 parentId 可为 null，授权必须读取虚拟根规则。
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceA, null, "upload"));
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceA, 0L, "upload"));
        assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceB, null, "upload"));
        assertTrue(teamService.resolveMyPermissions(spaceA, nodeA.getId()).contains("upload"));
        assertFalse(teamService.resolveMyPermissions(spaceA, nodeA.getId()).contains("download"));
        assertTrue(teamService.resolveMyPermissions(spaceB, nodeB.getId()).contains("download"));
        assertFalse(teamService.resolveMyPermissions(spaceB, nodeB.getId()).contains("upload"));
        // 传入其他空间的节点 ID 时，即使本空间根规则允许上传，也不能跨空间授权。
        assertEquals(Set.of(), teamService.resolveMyPermissions(spaceA, nodeB.getId()));
        assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceA, nodeB.getId(), "upload"));
        assertThrows(BusinessException.class, () -> teamService.setFolderPermissions(spaceA, 0L,
                ruleRequest("all", "0", "{\"download\":true}")));

        setUpUser(100L, 1L);
        FolderPermissionRequest empty = new FolderPermissionRequest();
        empty.setRules(List.of());
        teamService.setFolderPermissions(spaceA, 0L, empty);
        assertEquals(0, teamService.getFolderPermissions(spaceA, 0L).getData().size());
        setUpUser(200L, 1L);
        assertEquals(1, teamService.getFolderPermissions(spaceB, 0L).getData().size());
    }

    @Test
    void recycledNodeLosesInheritedRootPermissionImmediately() {
        insertUser(100L, 1L, "owner");
        insertUser(300L, 1L, "viewer");
        Long spaceId = createSpaceAs(100L);
        inviteMemberAs(spaceId, 100L, 300L);
        FileNode node = insertNode(1L, 100L, spaceId, "recycled.txt");
        teamService.setFolderPermissions(spaceId, 0L,
                ruleRequest("all", "0", "{\"upload\":true}"));

        setUpUser(300L, 1L);
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, node.getId(), "upload"));

        node.setStatus(1);
        assertEquals(1, fileNodeMapper.updateById(node));
        assertEquals(Set.of(), teamService.resolveMyPermissions(spaceId, node.getId()));
        assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceId, node.getId(), "upload"));
    }

    @Test
    void setFolderPermissions_allRuleManagePermissionRejected() {
        insertUser(100L, 1L, "owner");
        Long spaceId = createSpaceAs(100L);
        FileNode node = insertNode(1L, 100L, spaceId, "dir");

        setUpUser(100L, 1L);
        // all 规则显式包含空间管理权限点 → 拒绝
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(),
                        ruleRequest("all", "0", "{\"view\":true,\"manage_members\":true}")));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex1.getCode());
        // all 规则含 manage_settings → 拒绝
        assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(),
                        ruleRequest("all", "0", "{\"manage_settings\":true}")));
        // all 规则 permissions 为空 + 旧单值 permission=0（管理）→ 回退映射含空间管理权限 → 拒绝
        FolderPermissionRequest legacyAll = new FolderPermissionRequest();
        FolderPermissionRequest.PermissionRule legacyRule = new FolderPermissionRequest.PermissionRule();
        legacyRule.setSubjectType("all");
        legacyRule.setSubjectId("0");
        legacyRule.setPermission(0);
        legacyAll.setRules(List.of(legacyRule));
        assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(), legacyAll));
        // 非法 subjectType → 拒绝
        assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(),
                        ruleRequest("owner", "0", "{\"view\":true}")));
        // 全部被拒后不应残留任何规则
        assertEquals(0, teamFolderPermissionMapper.selectCount(
                new LambdaQueryWrapper<TeamFolderPermission>()
                        .eq(TeamFolderPermission::getFolderNodeId, node.getId())));
    }

    @Test
    void setFolderPermissions_validAllMemberRoleRulesPass() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member");
        Long spaceId = createSpaceAs(100L);
        inviteMemberAs(spaceId, 100L, 200L);
        FileNode node = insertNode(1L, 100L, spaceId, "dir");

        setUpUser(100L, 1L);
        // 合法 all/member/role 规则同批提交 → 通过
        FolderPermissionRequest request = new FolderPermissionRequest();
        request.setRules(List.of(
                rule("all", "0", "{\"view\":true,\"upload\":true}"),
                rule("member", "200", "{\"view\":true,\"download\":true}"),
                rule("role", "1", "{\"view\":true,\"download\":true}")));
        assertDoesNotThrow(() -> teamService.setFolderPermissions(spaceId, node.getId(), request));

        // 读回：3 条规则均落库且 spaceId 与空间一致（P1 写路径归属正确）
        List<FolderPermissionVO> vos = teamService.getFolderPermissions(spaceId, node.getId()).getData();
        assertEquals(3, vos.size());
        assertTrue(vos.stream().allMatch(v -> spaceId.equals(v.getSpaceId())));
    }
}
