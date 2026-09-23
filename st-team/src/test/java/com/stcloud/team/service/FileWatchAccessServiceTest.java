package com.stcloud.team.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stcloud.auth.entity.SysUser;
import com.stcloud.auth.mapper.SysUserMapper;
import com.stcloud.common.access.TeamFileAccessPolicy;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.team.mapper.TeamMemberMapper;
import com.stcloud.team.mapper.TeamRoleMapper;
import com.stcloud.team.mapper.TeamSpaceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 投递接收主体的租户、逻辑删除和用户状态核验测试。 */
@ExtendWith(MockitoExtension.class)
class FileWatchAccessServiceTest {

    @Mock
    private FileNodeMapper fileNodeMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private TeamFileAccessPolicy teamFileAccessPolicy;
    @Mock
    private TeamMemberMapper teamMemberMapper;
    @Mock
    private TeamRoleMapper teamRoleMapper;
    @Mock
    private TeamSpaceMapper teamSpaceMapper;
    @Mock
    private FolderPermissionService folderPermissionService;

    private FileWatchAccessService accessService;

    @BeforeEach
    void setUp() {
        initializeSysUserTableInfo();
        accessService = new FileWatchAccessService(fileNodeMapper, sysUserMapper,
                teamFileAccessPolicy, teamMemberMapper, teamRoleMapper, teamSpaceMapper,
                folderPermissionService);
    }

    /**
     * Mockito 不会启动 MyBatis-Plus 的 mapper 初始化流程，lambda 列缓存因此可能尚未建立。
     * 这里用独立配置初始化实体元数据，确保 SQL 片段和参数都按真实字段解析。
     */
    private static void initializeSysUserTableInfo() {
        if (TableInfoHelper.getTableInfo(SysUser.class) == null) {
            Configuration configuration = new Configuration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                    configuration, FileWatchAccessServiceTest.class.getName());
            assistant.setCurrentNamespace(FileWatchAccessServiceTest.class.getName());
            TableInfoHelper.initTableInfo(assistant, SysUser.class);
        }
    }

    @Test
    void activeUserQueryIsTenantScopedAndRequiresNormalUndeletedRecord() {
        SysUser user = new SysUser();
        when(sysUserMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaQueryWrapper<SysUser> query = invocation.getArgument(0);
            String sqlSegment = query.getSqlSegment();
            assertTrue(sqlSegment.contains("tenant_id"));
            assertTrue(sqlSegment.contains("id ="));
            assertTrue(sqlSegment.contains("deleted"));
            assertTrue(sqlSegment.contains("status"));
            Collection<Object> values = query.getParamNameValuePairs().values();
            assertTrue(values.contains(4101L));
            assertTrue(values.contains(41011L));
            assertTrue(values.contains(0));
            assertTrue(values.contains(1));
            return user;
        });

        assertTrue(accessService.isActiveUser(4101L, 41011L));
        verify(sysUserMapper).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void missingOrInvalidUserIsRejectedBeforeDelivery() {
        when(sysUserMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertFalse(accessService.isActiveUser(4101L, 41011L));
        assertFalse(accessService.isActiveUser(4101L, 0L));
        assertFalse(accessService.isActiveUser(null, 41011L));

        verify(sysUserMapper).selectOne(any(LambdaQueryWrapper.class));
        verifyNoInteractions(fileNodeMapper, teamFileAccessPolicy, teamMemberMapper,
                teamRoleMapper, teamSpaceMapper, folderPermissionService);
    }
}
