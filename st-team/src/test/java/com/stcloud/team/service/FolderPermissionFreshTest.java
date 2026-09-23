package com.stcloud.team.service;

import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamFolderPermission;
import com.stcloud.team.mapper.TeamFolderPermissionMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 显式主体 fresh 权限解析边界：不碰共享缓存，脏父链不能保留部分角色权限。
 */
@ExtendWith(MockitoExtension.class)
class FolderPermissionFreshTest {

    @Mock
    private TeamFolderPermissionMapper permissionMapper;
    @Mock
    private FileNodeMapper nodeMapper;
    @Mock
    private TeamMemberMapper memberMapper;

    private FolderPermissionService service() {
        FolderPermissionService service = new FolderPermissionService();
        ReflectionTestUtils.setField(service, "teamFolderPermissionMapper", permissionMapper);
        ReflectionTestUtils.setField(service, "fileNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "teamMemberMapper", memberMapper);
        return service;
    }

    private FileNode node(long id, Long parentId) {
        FileNode node = new FileNode();
        node.setId(id);
        node.setTenantId(1L);
        node.setSpaceId(10L);
        node.setParentId(parentId);
        node.setDeleted(0);
        node.setStatus(0);
        return node;
    }

    private TeamFolderPermission rule(long tenantId, long spaceId, long folderId, String permissions) {
        TeamFolderPermission rule = new TeamFolderPermission();
        rule.setTenantId(tenantId);
        rule.setSpaceId(spaceId);
        rule.setFolderNodeId(folderId);
        rule.setSubjectType("member");
        rule.setSubjectId(5L);
        rule.setPermissions(permissions);
        return rule;
    }

    @Test
    void freshUsesCurrentChainAndSupportsJsonPermissions() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 200L), node(200L, 0L));
        when(permissionMapper.selectList(any())).thenReturn(
                List.of(rule(1L, 10L, 100L, "{\"download\":true}")), List.of());

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 100L, 5L, Set.of("view"));

        assertEquals(Set.of("view", "download"), result);
    }

    @Test
    void freshAppliesRootRuleOnlyAfterVerifiedParentChain() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 0L));
        when(permissionMapper.selectList(any())).thenReturn(List.of(),
                List.of(rule(1L, 10L, 0L, "{\"upload\":true}")));

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 100L, 5L, Set.of("view"));

        assertEquals(Set.of("view", "upload"), result);
    }

    @Test
    void freshDoesNotApplyRootRuleWhenParentIsMissing() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 200L), null);
        when(permissionMapper.selectList(any())).thenReturn(List.of(),
                List.of(rule(1L, 10L, 0L, "{\"upload\":true}")));

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 100L, 5L, Set.of("view"));

        assertEquals(Set.of(), result);
        verify(permissionMapper, times(1)).selectList(any());
    }

    @Test
    void cachedResolverIncludesRootRuleAtMaximumSupportedDepth() {
        AtomicInteger nodes = new AtomicInteger();
        AtomicInteger rules = new AtomicInteger();
        when(nodeMapper.selectById(any())).thenAnswer(invocation -> {
            long id = nodes.incrementAndGet();
            return node(id, id == 20 ? 0L : id + 1);
        });
        when(permissionMapper.selectList(any())).thenAnswer(invocation ->
                rules.incrementAndGet() == 21
                        ? List.of(rule(1L, 10L, 0L, "{\"upload\":true}")) : List.of());

        Set<String> result = service().resolvePermissions(10L, 1L, 5L, Set.of("view"));

        assertEquals(Set.of("view", "upload"), result);
        assertEquals(20, nodes.get());
    }

    @Test
    void freshRejectsCrossTenantRuleEvenWhenMapperReturnsIt() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 0L));
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(rule(2L, 10L, 100L, "{\"download\":true}")));

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 100L, 5L, Set.of("view"));

        assertEquals(Set.of("view"), result);
    }

    @Test
    void cycleFailsClosedInsteadOfReturningPartialPermissions() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 200L), node(200L, 100L));
        when(permissionMapper.selectList(any())).thenReturn(
                List.of(rule(1L, 10L, 100L, "{\"download\":true}")));

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 100L, 5L, Set.of("view"));

        assertEquals(Set.of(), result);
    }

    @Test
    void missingAncestorFailsClosed() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 200L), null);
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(rule(1L, 10L, 100L, "{\"download\":true}")));

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 100L, 5L, Set.of("view"));

        assertEquals(Set.of(), result);
    }

    @Test
    void overDepthChainFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        when(nodeMapper.selectOne(any())).thenAnswer(invocation -> {
            int index = calls.getAndIncrement();
            return node(index + 1L, index == 20 ? 0L : index + 2L);
        });
        when(permissionMapper.selectList(any())).thenReturn(List.of());

        Set<String> result = service().resolvePermissionsFreshForTenant(
                1L, 10L, 1L, 5L, Set.of("view"));

        assertEquals(Set.of(), result);
    }

    @Test
    void requestContextReusesSharedAncestorAndRules() {
        when(nodeMapper.selectOne(any())).thenReturn(node(100L, 200L), node(200L, 0L), node(101L, 200L));
        when(permissionMapper.selectList(any())).thenReturn(List.of(),
                List.of(rule(1L, 10L, 200L, "{\"download\":true}")), List.of());

        FolderPermissionService.FreshPermissionContext context = service().freshPermissionContext(
                1L, 10L, 5L, 2L, Set.of("view"));
        assertEquals(Set.of("view", "download"), context.resolve(100L));
        assertEquals(Set.of("view", "download"), context.resolve(101L));

        // 两个同目录候选共用父节点与规则读取；不使用跨请求共享缓存。
        verify(nodeMapper, times(3)).selectOne(any());
        verify(permissionMapper, times(4)).selectList(any());
    }
}
