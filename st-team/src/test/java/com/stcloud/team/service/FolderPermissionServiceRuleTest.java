package com.stcloud.team.service;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamFolderPermission;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.mapper.TeamFolderPermissionMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 团队文件夹权限规则单测（权限模型重设计 TASK-PERM-BE1）。
 * 验证并集增强语义：角色∪member/all/role 规则、自定义角色匹配、祖先链收集、
 * permissions JSON 优先/旧单值回退、-1 仅标注不生效、无规则回退角色权限集、查看者预设无 download。
 */
class FolderPermissionServiceRuleTest {

    private FolderPermissionService newService(TeamFolderPermissionMapper permMapper,
                                               FileNodeMapper nodeMapper,
                                               TeamMemberMapper memberMapper) {
        FolderPermissionService service = new FolderPermissionService();
        ReflectionTestUtils.setField(service, "teamFolderPermissionMapper", permMapper);
        ReflectionTestUtils.setField(service, "fileNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "teamMemberMapper", memberMapper);
        return service;
    }

    private TeamFolderPermission rule(String subjectType, Long subjectId, String permissions) {
        TeamFolderPermission p = new TeamFolderPermission();
        p.setSpaceId(1L);
        p.setSubjectType(subjectType);
        p.setSubjectId(subjectId);
        p.setPermissions(permissions);
        return p;
    }

    private TeamFolderPermission legacyRule(String subjectType, Long subjectId, int permission) {
        TeamFolderPermission p = rule(subjectType, subjectId, null);
        p.setPermission(permission);
        return p;
    }

    private FileNode node(Long id, Long parentId) {
        FileNode n = new FileNode();
        n.setId(id);
        n.setParentId(parentId);
        return n;
    }

    @Test
    void unionOfRoleAndMemberRule() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // 上传者角色 {view,upload} + 节点 member 规则 {download} → {view,upload,download}（用户例子）
        when(permMapper.selectList(any())).thenReturn(List.of(rule("member", 5L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view", "upload", "download"),
                service.resolvePermissions(1L, 10L, 5L, Set.of("view", "upload")));
    }

    @Test
    void allRuleAppliesToEveryMember() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // all 规则：全体成员生效（管理员直通由 TeamService 处理）
        when(permMapper.selectList(any())).thenReturn(List.of(rule("all", 0L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view", "download"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
    }

    @Test
    void roleRuleMatchesCustomRole() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        // 成员角色=100（自定义），role 规则 subjectId=100 → 命中 {download}
        TeamMember member = new TeamMember();
        member.setRole(100);
        when(memberMapper.selectOne(any())).thenReturn(member);
        when(permMapper.selectList(any())).thenReturn(List.of(rule("role", 100L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper, memberMapper);
        assertEquals(Set.of("view", "download"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
    }

    @Test
    void roleRuleNotMatchOtherRole() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        TeamMemberMapper memberMapper = mock(TeamMemberMapper.class);
        // 成员角色=2，role 规则 subjectId=100 → 不命中，回退角色权限集
        TeamMember member = new TeamMember();
        member.setRole(2);
        when(memberMapper.selectOne(any())).thenReturn(member);
        when(permMapper.selectList(any())).thenReturn(List.of(rule("role", 100L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper, memberMapper);
        assertEquals(Set.of("view"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
    }

    @Test
    void ruleFromOtherSpaceNotApplied() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // 跨空间注入的规则（spaceId=2）：计算空间 1 的有效权限时必须忽略，防止跨空间 ACL 提升
        TeamFolderPermission crossSpaceRule = rule("member", 5L, "{\"download\":true}");
        crossSpaceRule.setSpaceId(2L);
        when(permMapper.selectList(any())).thenReturn(List.of(crossSpaceRule));

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
    }

    @Test
    void ancestorRulesCollectUnion() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // 节点 30 无规则 → 父 20 member {download} → 祖父 10 all {share} → 并集
        when(permMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(rule("member", 5L, "{\"download\":true}")))
                .thenReturn(List.of(rule("all", 0L, "{\"share\":true}")));
        when(nodeMapper.selectById(30L)).thenReturn(node(30L, 20L));
        when(nodeMapper.selectById(20L)).thenReturn(node(20L, 10L));
        when(nodeMapper.selectById(10L)).thenReturn(node(10L, 0L));

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view", "download", "share"),
                service.resolvePermissions(1L, 30L, 5L, Set.of("view")));
    }

    @Test
    void legacyPermissionFallback() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // 无 permissions JSON，旧 permission=2 → {view}
        when(permMapper.selectList(any())).thenReturn(List.of(legacyRule("member", 5L, 2)));

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view"), service.resolvePermissions(1L, 10L, 5L, Set.of()));
    }

    @Test
    void denyRuleEnhanceOnly() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // -1 仅标注、不参与并集（规则只增强）：角色权限不因规则减少
        when(permMapper.selectList(any())).thenReturn(List.of(legacyRule("member", 5L, -1)));

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view", "upload"), service.resolvePermissions(1L, 10L, 5L, Set.of("view", "upload")));
    }

    @Test
    void noRuleFallsBackToRolePerms() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        when(permMapper.selectList(any())).thenReturn(List.of());

        FolderPermissionService service = newService(permMapper, nodeMapper, mock(TeamMemberMapper.class));
        assertEquals(Set.of("view", "upload"), service.resolvePermissions(1L, 10L, 5L, Set.of("view", "upload")));
    }

    @Test
    void presetViewerHasNoDownload() {
        // 内置查看者预设：view=true、download=false
        Set<String> viewer = FolderPermissionService.presetPermissions(2);
        assertEquals(Set.of("view"), viewer);
        assertFalse(viewer.contains("download"));
    }

    @Test
    void permissionsJsonRoundTrip() {
        // JSON 与 Set 互转；upload 隐含 view
        String json = FolderPermissionService.permissionsToJson(Set.of("view", "upload"));
        assertEquals(Set.of("view", "upload"), FolderPermissionService.parsePermissions(json));
    }
}
