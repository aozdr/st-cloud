package com.stcloud.team.service;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamMember;
import com.stcloud.team.entity.TeamSpace;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamRoleMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队显式主体授权边界：成员过期、跨租户、祖先状态及权限撤销均即时生效。
 */
@ExtendWith(MockitoExtension.class)
class TeamFileAccessPolicyTest {

    @Mock
    private TeamSpaceMapper spaceMapper;
    @Mock
    private TeamMemberMapper memberMapper;
    @Mock
    private TeamRoleMapper roleMapper;
    @Mock
    private FileNodeMapper nodeMapper;
    @Mock
    private FolderPermissionService permissionService;

    private TeamFileAccessPolicyImpl policy() {
        TeamFileAccessPolicyImpl policy = new TeamFileAccessPolicyImpl();
        ReflectionTestUtils.setField(policy, "teamSpaceMapper", spaceMapper);
        ReflectionTestUtils.setField(policy, "teamMemberMapper", memberMapper);
        ReflectionTestUtils.setField(policy, "teamRoleMapper", roleMapper);
        ReflectionTestUtils.setField(policy, "fileNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(policy, "folderPermissionService", permissionService);
        return policy;
    }

    private TeamSpace space(long tenantId, long id) {
        TeamSpace space = new TeamSpace();
        space.setTenantId(tenantId);
        space.setId(id);
        space.setStatus(1);
        return space;
    }

    private TeamMember member(long tenantId, long spaceId, long userId, int role) {
        TeamMember member = new TeamMember();
        member.setTenantId(tenantId);
        member.setSpaceId(spaceId);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    private FileNode file(long tenantId, long spaceId, long id, Long parentId) {
        FileNode file = new FileNode();
        file.setTenantId(tenantId);
        file.setSpaceId(spaceId);
        file.setId(id);
        file.setParentId(parentId);
        file.setNodeType(1);
        file.setStatus(0);
        file.setHidden(0);
        file.setUploadStatus(2);
        return file;
    }

    @Test
    void invalidIdsAreRejectedBeforeDatabaseAccess() {
        assertFalse(policy().isActiveMember(0L, 5L, 10L));
        assertFalse(policy().canView(1L, 5L, 10L, -1L));
        verify(spaceMapper, never()).selectOne(any());
        verify(memberMapper, never()).selectOne(any());
    }

    @Test
    void expiredMemberIsRejectedImmediately() {
        when(spaceMapper.selectOne(any())).thenReturn(space(1L, 10L));
        TeamMember expired = member(1L, 10L, 5L, 2);
        expired.setExpireAt(LocalDateTime.now().minusSeconds(1));
        when(memberMapper.selectOne(any())).thenReturn(expired);

        assertFalse(policy().isActiveMember(1L, 5L, 10L));
    }

    @Test
    void ordinaryAdminStillNeedsExplicitMemberAndTenant() {
        when(spaceMapper.selectOne(any())).thenReturn(space(1L, 10L));
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 10L, 5L, 0));
        FileNode file = file(1L, 10L, 100L, null);
        when(nodeMapper.selectOne(any())).thenReturn(file);

        assertTrue(policy().canView(1L, 5L, 10L, 100L));
        verify(permissionService, never()).resolvePermissionsFreshForTenant(any(), any(), any(), any(), any());
    }

    @Test
    void crossTenantRowsReturnedByAStubAreRejectedAgain() {
        when(spaceMapper.selectOne(any())).thenReturn(space(1L, 10L));
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 10L, 5L, 2));
        when(nodeMapper.selectOne(any())).thenReturn(file(2L, 10L, 100L, null));

        assertFalse(policy().canView(1L, 5L, 10L, 100L));
    }

    @Test
    void hiddenAncestorIsDeniedBeforePermissionResolution() {
        when(spaceMapper.selectOne(any())).thenReturn(space(1L, 10L));
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 10L, 5L, 2));
        FileNode child = file(1L, 10L, 100L, 200L);
        FileNode hiddenParent = file(1L, 10L, 200L, null);
        hiddenParent.setHidden(1);
        when(nodeMapper.selectOne(any())).thenReturn(child, hiddenParent);

        assertFalse(policy().canView(1L, 5L, 10L, 100L));
        verify(permissionService, never()).resolvePermissionsFreshForTenant(any(), any(), any(), any(), any());
    }

    @Test
    void permissionRevocationTakesEffectWithoutSharedCache() {
        when(spaceMapper.selectOne(any())).thenReturn(space(1L, 10L));
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 10L, 5L, 2), member(1L, 10L, 5L, 2));
        when(nodeMapper.selectOne(any())).thenReturn(file(1L, 10L, 100L, null), file(1L, 10L, 100L, null));
        when(permissionService.resolvePermissionsFreshForTenant(1L, 10L, 100L, 5L,
                FolderPermissionService.VIEWER_PERMISSIONS)).thenReturn(Set.of());

        assertFalse(policy().canView(1L, 5L, 10L, 100L));
    }

    @Test
    void searchReadContextReusesMemberAndAncestorForSiblingCandidates() {
        when(spaceMapper.selectOne(any())).thenReturn(space(1L, 10L));
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 10L, 5L, 0));
        FileNode parent = file(1L, 10L, 200L, null);
        parent.setNodeType(0);
        when(nodeMapper.selectOne(any())).thenReturn(file(1L, 10L, 100L, 200L), parent,
                file(1L, 10L, 101L, 200L));

        var context = policy().openReadContext(1L, 5L, 10L);
        assertTrue(context.canView(100L));
        assertTrue(context.canView(101L));

        verify(spaceMapper, times(1)).selectOne(any());
        verify(memberMapper, times(1)).selectOne(any());
        verify(nodeMapper, times(3)).selectOne(any());
    }
}
