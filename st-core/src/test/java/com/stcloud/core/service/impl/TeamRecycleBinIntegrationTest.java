package com.stcloud.core.service.impl;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.RecycleBinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Mapper/事务覆盖团队回收权限及混合上传者目录；外部存储/事件在本套中受控。 */
@Import(FileServiceFlowIntegrationTest.FlowTestConfig.class)
class TeamRecycleBinIntegrationTest extends AbstractIntegrationTest {
    private static final long ADMIN = 71001L, OWNER = 71002L, EDITOR = 71003L, SPACE = 71010L;
    @Autowired private FileService fileService;
    @Autowired private RecycleBinService recycleBinService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        setUpUser(ADMIN, 1L);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,password,status,storage_used,deleted) " +
                "VALUES(71001,1,'team-recycle-fixture','x',1,100,0)");
        space(SPACE, OWNER);
        for (long user : List.of(ADMIN, OWNER, EDITOR)) {
            jdbc.update("INSERT INTO team_member(tenant_id,space_id,user_id,role,member_type,deleted) VALUES(1,?,?,?,0,0)",
                    SPACE, user, user == ADMIN ? 0 : 2);
        }
    }

    private void space(long space, long owner) {
        jdbc.update("INSERT INTO team_space(id,tenant_id,space_name,owner_id,storage_used,status,deleted) VALUES(?,1,?, ?,32,1,0)",
                space, "recycle-fixture-" + space, owner);
    }

    private FileNode node(long space, long owner, String name, long parent, boolean folder, int status) {
        FileNode n = insertFileNode(1L, owner, name, status);
        n.setSpaceId(space);
        n.setParentId(parent);
        n.setNodeType(folder ? 0 : 1);
        n.setFileSize(folder ? 0L : 32L);
        n.setPath(parent == 0 ? "/" + name : fileNodeMapper.selectById(parent).getPath() + "/" + name);
        fileNodeMapper.updateById(n);
        return n;
    }

    private List<Long> recycledIds() {
        return recycleBinService.listRecycleBin().stream().map(v -> v.getId()).toList();
    }

    @Test
    void deleteFolderListAndRestoreMixedOwners() {
        FileNode root = node(SPACE, EDITOR, "团队目录", 0, true, 0);
        FileNode child = node(SPACE, OWNER, "内容.txt", root.getId(), false, 0);
        fileService.deleteTeamFiles(SPACE, List.of(root.getId()));
        assertEquals(List.of(root.getId()), recycledIds());
        assertEquals(0, fileNodeMapper.selectById(child.getId()).getStatus());
        assertThrows(BusinessException.class, () -> fileService.validateAccessible(child.getId()));
        recycleBinService.restore(List.of(root.getId()));
        assertTrue(recycledIds().isEmpty());
        assertEquals(SPACE, fileNodeMapper.selectById(root.getId()).getSpaceId());
        assertEquals(root.getId(), fileNodeMapper.selectById(child.getId()).getParentId());
        assertEquals("/团队目录/内容.txt", fileNodeMapper.selectById(child.getId()).getPath());
        assertDoesNotThrow(() -> fileService.validateAccessible(child.getId()));
    }

    @Test
    void ownerCanRestoreAnotherUploadersFolder() {
        FileNode root = node(SPACE, EDITOR, "owned-space", 0, true, 1);
        setUpUser(OWNER, 1L);
        assertTrue(recycledIds().contains(root.getId()));
        recycleBinService.restore(List.of(root.getId()));
        assertEquals(0, fileNodeMapper.selectById(root.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"editor-owner", "no-member", "removed", "expired", "disabled", "external-disabled", "cross-tenant"})
    void unauthorizedCannotListRestoreOrDelete(String mode) {
        FileNode root = node(SPACE, EDITOR, "protected", 0, true, 1);
        switch (mode) {
            case "editor-owner" -> setUpUser(EDITOR, 1L);
            case "no-member" -> setUpUser(71099L, 1L);
            case "removed" -> jdbc.update("UPDATE team_member SET deleted=1 WHERE space_id=? AND user_id=?", SPACE, ADMIN);
            case "expired" -> jdbc.update("UPDATE team_member SET expire_at='2000-01-01' WHERE space_id=? AND user_id=?", SPACE, ADMIN);
            case "disabled" -> jdbc.update("UPDATE team_space SET status=0 WHERE id=?", SPACE);
            case "external-disabled" -> jdbc.update("UPDATE team_member SET member_type=1 WHERE space_id=? AND user_id=?", SPACE, ADMIN);
            case "cross-tenant" -> setUpUser(ADMIN, 2L);
            default -> fail(mode);
        }
        assertFalse(recycledIds().contains(root.getId()));
        assertThrows(BusinessException.class, () -> recycleBinService.restore(List.of(root.getId())));
        if (mode.equals("cross-tenant")) {
            // 租户拦截器将其他租户 ID 视为不存在，沿用永久删除的幂等空操作契约。
            assertDoesNotThrow(() -> recycleBinService.permanentDelete(List.of(root.getId())));
        } else {
            assertThrows(BusinessException.class, () -> recycleBinService.permanentDelete(List.of(root.getId())));
        }
        recycleBinService.emptyRecycleBin();
        setUpUser(ADMIN, 1L);
        assertNotNull(fileNodeMapper.selectById(root.getId()));
        assertEquals(1, fileNodeMapper.selectById(root.getId()).getStatus());
    }

    @Test
    void authorizationRevocationAfterListTakesEffect() {
        FileNode root = node(SPACE, EDITOR, "revoked", 0, true, 1);
        assertTrue(recycledIds().contains(root.getId()));
        jdbc.update("UPDATE team_member SET role=2 WHERE space_id=? AND user_id=?", SPACE, ADMIN);
        assertFalse(recycledIds().contains(root.getId()));
        assertThrows(BusinessException.class, () -> recycleBinService.restore(List.of(root.getId())));
    }

    @Test
    void unusableParentReturnsToSameSpaceWithMixedOwnerNameConflict() {
        FileNode parent = node(SPACE, OWNER, "已回收父目录", 0, true, 1);
        FileNode root = node(SPACE, EDITOR, "目录", parent.getId(), true, 1);
        FileNode child = node(SPACE, OWNER, "子文件.txt", root.getId(), false, 0);
        node(SPACE, OWNER, "目录", 0, true, 0);
        recycleBinService.restore(List.of(root.getId()));
        FileNode restored = fileNodeMapper.selectById(root.getId());
        assertEquals(SPACE, restored.getSpaceId());
        assertEquals(0L, restored.getParentId());
        assertEquals("目录(1)", restored.getName());
        assertEquals("/目录(1)/子文件.txt", fileNodeMapper.selectById(child.getId()).getPath());
        assertEquals(1, fileNodeMapper.selectById(parent.getId()).getStatus());
    }

    @Test
    void normalParentUnderRecycledAncestorCannotReceiveRestoredFolder() {
        FileNode ancestor = node(SPACE, OWNER, "hidden-root", 0, true, 1);
        FileNode parent = node(SPACE, EDITOR, "normal-parent", ancestor.getId(), true, 0);
        FileNode root = node(SPACE, OWNER, "restore-root", parent.getId(), true, 1);
        recycleBinService.restore(List.of(root.getId()));
        assertEquals(0L, fileNodeMapper.selectById(root.getId()).getParentId());
        assertEquals(SPACE, fileNodeMapper.selectById(root.getId()).getSpaceId());
    }

    @Test
    void differentOwnerParentIsKeptAndOtherSpaceNameDoesNotConflict() {
        space(71020L, 71099L);
        FileNode parent = node(SPACE, OWNER, "parent", 0, true, 0);
        FileNode root = node(SPACE, EDITOR, "restore", parent.getId(), true, 1);
        FileNode child = node(SPACE, OWNER, "child.txt", root.getId(), false, 0);
        node(71020L, EDITOR, "restore", parent.getId(), true, 0);
        recycleBinService.restore(List.of(root.getId()));
        FileNode restored = fileNodeMapper.selectById(root.getId());
        assertEquals(parent.getId(), restored.getParentId());
        assertEquals("restore", restored.getName());
        assertEquals("/parent/restore/child.txt", fileNodeMapper.selectById(child.getId()).getPath());
    }

    @Test
    void externalAdminRequiresCurrentlyEnabledCollaboration() {
        FileNode root = node(SPACE, EDITOR, "external", 0, true, 1);
        jdbc.update("UPDATE team_member SET member_type=1 WHERE space_id=? AND user_id=?", SPACE, ADMIN);
        assertTrue(recycledIds().isEmpty());
        jdbc.update("INSERT INTO team_external_config(tenant_id,space_id,allow_external) VALUES(1,?,1)", SPACE);
        assertTrue(recycledIds().contains(root.getId()));
        jdbc.update("UPDATE team_external_config SET allow_external=0 WHERE space_id=?", SPACE);
        assertThrows(BusinessException.class, () -> recycleBinService.restore(List.of(root.getId())));
    }

    @Test
    void independentRecycledChildRemainsRecycledOnParentRestore() {
        FileNode root = node(SPACE, EDITOR, "root", 0, true, 1);
        FileNode child = node(SPACE, OWNER, "independent", root.getId(), true, 1);
        recycleBinService.restore(List.of(root.getId()));
        assertEquals(List.of(child.getId()), recycledIds());
    }

    @Test
    void permanentDeleteMixedOwnersUsesTeamQuotaAndKeepsOtherScopes() {
        space(71020L, 71099L);
        FileNode root = node(SPACE, EDITOR, "root", 0, true, 1);
        FileNode child = node(SPACE, OWNER, "child.txt", root.getId(), false, 0);
        FileNode other = node(71020L, EDITOR, "other.txt", root.getId(), false, 0);
        recycleBinService.permanentDelete(List.of(root.getId(), child.getId(), root.getId()));
        assertNull(fileNodeMapper.selectById(root.getId()));
        assertNull(fileNodeMapper.selectById(child.getId()));
        assertNotNull(fileNodeMapper.selectById(other.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT storage_used FROM team_space WHERE id=?", Long.class, SPACE));
        assertEquals(100L, jdbc.queryForObject("SELECT storage_used FROM sys_user WHERE id=?", Long.class, ADMIN));
    }

    @Test
    void cannotPermanentlyDeleteNormalNode() {
        FileNode root = node(SPACE, EDITOR, "active", 0, true, 0);
        assertThrows(BusinessException.class, () -> recycleBinService.permanentDelete(List.of(root.getId())));
        assertNotNull(fileNodeMapper.selectById(root.getId()));
    }

    @Test
    void emptyOnlyAuthorizedScopesAndPersonalRestoreCompatible() {
        space(71020L, 71099L);
        FileNode team = node(SPACE, EDITOR, "team", 0, true, 1);
        FileNode child = node(SPACE, OWNER, "independent", team.getId(), true, 1);
        FileNode other = node(71020L, EDITOR, "other-space", 0, true, 1);
        FileNode personal = node(0, ADMIN, "personal", 0, true, 1);
        FileNode otherPersonal = node(0, EDITOR, "other-user", 0, true, 1);
        recycleBinService.restore(List.of(personal.getId()));
        assertEquals(0, fileNodeMapper.selectById(personal.getId()).getStatus());
        fileService.deleteToRecycleBin(List.of(personal.getId()));
        recycleBinService.emptyRecycleBin();
        for (FileNode deleted : List.of(team, child, personal)) assertNull(fileNodeMapper.selectById(deleted.getId()));
        for (FileNode kept : List.of(other, otherPersonal)) assertNotNull(fileNodeMapper.selectById(kept.getId()));
    }
}
