package com.stcloud.share;

import com.stcloud.common.context.TenantContext;
import com.stcloud.common.context.UserContext;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.share.dto.CreateShareRequest;
import com.stcloud.share.entity.FileShare;
import com.stcloud.share.mapper.FileShareMapper;
import com.stcloud.share.service.impl.ShareServiceImpl;
import com.stcloud.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分享码生成单元测试（S-06）：Mock Mapper 验证 4 位安全字符集与冲突重试逻辑。
 * <p>
 * 不启动 Spring 上下文，仅验证 generateShareCode 的生成/重试/超限分支：
 * 无冲突直接生成、冲突时重新生成、连续冲突超过最大重试次数抛业务异常且不落库。
 */
@ExtendWith(MockitoExtension.class)
class ShareServiceImplShareCodeUnitTest {

    private static final Long USER_ID = 1001L;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private FileNodeMapper fileNodeMapper;

    @Mock
    private FileService fileService;

    @Mock
    private StorageService storageService;

    @Mock
    private TeamService teamService;

    @InjectMocks
    private ShareServiceImpl shareService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        TenantContext.setTenantMode("SAAS");
        UserContext.setCurrentUser(UserContext.CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(1L)
                .username("unit-test")
                .build());
    }

    /** 当前用户自己的个人文件节点（spaceId 为空，绕过团队校验）。 */
    private FileNode ownFile() {
        FileNode node = new FileNode();
        node.setId(1L);
        node.setOwnerId(USER_ID);
        node.setSpaceId(null);
        node.setStatus(0);
        return node;
    }

    private CreateShareRequest request() {
        CreateShareRequest req = new CreateShareRequest();
        req.setFileNodeId(1L);
        req.setShareType(0);
        return req;
    }

    @Test
    @DisplayName("S-06 无冲突时生成 4 位安全字符集分享码")
    void generateShareCodeValidWhenNoConflict() {
        when(fileNodeMapper.selectById(1L)).thenReturn(ownFile());
        when(fileShareMapper.selectCount(any())).thenReturn(0L);

        shareService.createShare(request());

        ArgumentCaptor<FileShare> captor = ArgumentCaptor.forClass(FileShare.class);
        verify(fileShareMapper).insert((FileShare) captor.capture());
        String code = captor.getValue().getShareCode();
        assertEquals(4, code.length());
        assertTrue(code.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}"));
    }

    @Test
    @DisplayName("S-06 分享码冲突时重试生成，最终成功")
    void generateShareCodeRetriesOnConflict() {
        when(fileNodeMapper.selectById(1L)).thenReturn(ownFile());
        // 第一次查询冲突，第二次无冲突 → 触发重试后成功
        when(fileShareMapper.selectCount(any()))
                .thenReturn(1L)
                .thenReturn(0L);

        shareService.createShare(request());

        verify(fileShareMapper, times(2)).selectCount(any());
        ArgumentCaptor<FileShare> captor = ArgumentCaptor.forClass(FileShare.class);
        verify(fileShareMapper).insert((FileShare) captor.capture());
        String code = captor.getValue().getShareCode();
        assertEquals(4, code.length());
        assertTrue(code.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}"));
    }

    @Test
    @DisplayName("S-06 连续冲突超过最大重试次数后抛业务异常且不插入")
    void generateShareCodeThrowsAfterMaxRetries() {
        when(fileNodeMapper.selectById(1L)).thenReturn(ownFile());
        when(fileShareMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.createShare(request()));

        assertTrue(ex.getMessage().contains("分享码生成失败"));
        verify(fileShareMapper, never()).insert(any(FileShare.class));
        // 1 次初始生成 + 8 次重试 = 9 次冲突查询
        verify(fileShareMapper, times(9)).selectCount(any());
    }
}
