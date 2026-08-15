package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileNode;
import com.stcloud.team.AbstractTeamIntegrationTest;
import com.stcloud.team.dto.CreateSpaceRequest;
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

    private FolderPermissionRequest.PermissionRule rule(String subjectType, Long subjectId, String permissionsJson) {
        FolderPermissionRequest.PermissionRule rule = new FolderPermissionRequest.PermissionRule();
        rule.setSubjectType(subjectType);
        rule.setSubjectId(subjectId);
        rule.setPermissions(permissionsJson);
        return rule;
    }

    private FolderPermissionRequest ruleRequest(String subjectType, Long subjectId, String permissionsJson) {
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
        teamService.updateMemberRole(spaceId, memberId, 100);

        FileNode node = insertNode(1L, 100L, spaceId, "doc.txt");
        setUpUser(200L, 1L);
        Set<String> perms = teamService.resolveMyPermissions(spaceId, node.getId());
        assertEquals(Set.of("view", "upload"), perms);
        assertDoesNotThrow(() -> teamService.requirePermissions(spaceId, node.getId(), "upload"));
        assertThrows(BusinessException.class,
                () -> teamService.requirePermissions(spaceId, node.getId(), "delete"));
    }

    @Test
    void disabledCustomRoleFallsBackToViewer() {
        insertUser(100L, 1L, "owner");
        insertUser(200L, 1L, "member");
        Long spaceId = createSpaceAs(100L);
        Long memberId = inviteMemberAs(spaceId, 100L, 200L);

        // 自定义角色 id=101：停用（status=0）→ 回退查看者 {view}
        setUpUser(100L, 1L);
        TeamRole role = new TeamRole();
        role.setId(101L);
        role.setSpaceId(spaceId);
        role.setName("停用角色");
        role.setPermissions("{\"view\":true,\"upload\":true,\"download\":true}");
        role.setStatus(0);
        teamRoleMapper.insert(role);
        teamService.updateMemberRole(spaceId, memberId, 101);

        setUpUser(200L, 1L);
        assertEquals(Set.of("view"), teamService.resolveMyPermissions(spaceId, null));
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
                        ruleRequest("all", 0L, "{\"view\":true}")));
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
                ruleRequest("all", 0L, "{\"view\":true}")));
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
                        ruleRequest("all", 0L, "{\"view\":true,\"manage_members\":true}")));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex1.getCode());
        // all 规则含 manage_settings → 拒绝
        assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(),
                        ruleRequest("all", 0L, "{\"manage_settings\":true}")));
        // all 规则 permissions 为空 + 旧单值 permission=0（管理）→ 回退映射含空间管理权限 → 拒绝
        FolderPermissionRequest legacyAll = new FolderPermissionRequest();
        FolderPermissionRequest.PermissionRule legacyRule = new FolderPermissionRequest.PermissionRule();
        legacyRule.setSubjectType("all");
        legacyRule.setSubjectId(0L);
        legacyRule.setPermission(0);
        legacyAll.setRules(List.of(legacyRule));
        assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(), legacyAll));
        // 非法 subjectType → 拒绝
        assertThrows(BusinessException.class,
                () -> teamService.setFolderPermissions(spaceId, node.getId(),
                        ruleRequest("owner", 0L, "{\"view\":true}")));
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
                rule("all", 0L, "{\"view\":true,\"upload\":true}"),
                rule("member", 200L, "{\"view\":true,\"download\":true}"),
                rule("role", 1L, "{\"view\":true,\"download\":true}")));
        assertDoesNotThrow(() -> teamService.setFolderPermissions(spaceId, node.getId(), request));

        // 读回：3 条规则均落库且 spaceId 与空间一致（P1 写路径归属正确）
        List<FolderPermissionVO> vos = teamService.getFolderPermissions(spaceId, node.getId()).getData();
        assertEquals(3, vos.size());
        assertTrue(vos.stream().allMatch(v -> spaceId.equals(v.getSpaceId())));
    }
}
