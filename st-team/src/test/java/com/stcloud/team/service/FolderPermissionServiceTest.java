package com.stcloud.team.service;

import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.entity.TeamFolderPermission;
import com.stcloud.team.mapper.TeamFolderPermissionMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FolderPermissionService 权限缓存单测（TASK-005 适配权限模型重设计 TASK-PERM-BE1）。
 * 纯 Mockito：验证缓存命中不重复向上遍历、invalidateSpace/setPermissions 失效后重算、不同空间 key 隔离。
 */
class FolderPermissionServiceTest {

    private FolderPermissionService newService(TeamFolderPermissionMapper permMapper, FileNodeMapper nodeMapper) {
        FolderPermissionService service = new FolderPermissionService();
        ReflectionTestUtils.setField(service, "teamFolderPermissionMapper", permMapper);
        ReflectionTestUtils.setField(service, "fileNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "teamMemberMapper", mock(TeamMemberMapper.class));
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

    @Test
    void cacheHitSkipsSecondTraversal() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // 节点 10 有 member 规则：用户 5 → {download}
        when(permMapper.selectList(any())).thenReturn(List.of(rule("member", 5L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper);

        Set<String> expected = Set.of("view", "upload", "download");
        assertEquals(expected, service.resolvePermissions(1L, 10L, 5L, Set.of("view", "upload")));
        assertEquals(expected, service.resolvePermissions(1L, 10L, 5L, Set.of("view", "upload")));

        // 二次命中缓存，不再查询权限规则与节点
        verify(permMapper, times(1)).selectList(any());
        // 并集语义：即使首节点命中规则也继续向上收集父链，首次计算会查询一次父节点（mock 返回 null 结束）
        verify(nodeMapper, times(1)).selectById(anyLong());
    }

    @Test
    void invalidateSpaceRecomputes() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        // 第一次无规则 → {view}；失效后新增 member 规则 → {view,download}
        when(permMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(rule("member", 5L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper);

        assertEquals(Set.of("view"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
        service.invalidateSpace(1L);
        assertEquals(Set.of("view", "download"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));

        verify(permMapper, times(2)).selectList(any());
    }

    @Test
    void differentSpaceKeyIsolated() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        when(permMapper.selectList(any())).thenReturn(List.of(rule("member", 5L, "{\"download\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper);

        // 空间 1：规则 spaceId=1 命中 → {view,download}
        assertEquals(Set.of("view", "download"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
        // 空间 2：key 不同需重新计算，且规则 spaceId=1 不匹配 → 仅角色权限集 {view}
        assertEquals(Set.of("view"), service.resolvePermissions(2L, 10L, 5L, Set.of("view")));
        verify(permMapper, times(2)).selectList(any());
    }

    @Test
    void setPermissionsInvalidatesSpace() {
        TeamFolderPermissionMapper permMapper = mock(TeamFolderPermissionMapper.class);
        FileNodeMapper nodeMapper = mock(FileNodeMapper.class);
        when(permMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(rule("member", 5L, "{\"upload\":true}")));

        FolderPermissionService service = newService(permMapper, nodeMapper);
        assertEquals(Set.of("view"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));

        // setPermissions 内部会 invalidateSpace：后续访问重新计算
        service.setPermissions(1L, 10L, List.of(rule("member", 5L, "{\"upload\":true}")));

        assertEquals(Set.of("view", "upload"), service.resolvePermissions(1L, 10L, 5L, Set.of("view")));
        // 缓存已失效，selectList 被再次调用
        verify(permMapper, times(2)).selectList(any());
    }

    @Test
    void uploadImpliesView() {
        FolderPermissionService service = newService(mock(TeamFolderPermissionMapper.class), mock(FileNodeMapper.class));
        // 隐含关系：upload 隐含 view
        assertEquals(Set.of("view", "upload"), service.resolvePermissions(1L, 10L, 5L, Set.of("upload")));
    }
}
