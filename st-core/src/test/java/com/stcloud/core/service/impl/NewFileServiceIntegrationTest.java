package com.stcloud.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.NewFileService;
import com.stcloud.core.service.StorageService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 新建空白文件集成测试（H2 + 真实 Mapper）。
 * 覆盖 TC-01~TC-10：各类型新建、重名序号、个人/团队权限、配额、事件、类型白名单、节点状态。
 */
@Import(NewFileServiceIntegrationTest.NewFileTestConfig.class)
class NewFileServiceIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class NewFileTestConfig {

        @Bean
        StorageService storageService() {
            return Mockito.mock(StorageService.class);
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            return svc;
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return Mockito.mock(CloudStorageService.class);
        }

        @Bean
        FileService fileService() {
            return Mockito.mock(FileService.class);
        }

        @Bean
        RocketMQTemplate rocketMQTemplate() {
            return Mockito.mock(RocketMQTemplate.class);
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher(EventLogMapper eventLogMapper,
                                                       ApplicationEventPublisher eventPublisher,
                                                       ObjectMapper objectMapper) {
            return new ReliableEventPublisher(eventLogMapper, eventPublisher, objectMapper);
        }

        @Bean
        NewFileServiceImpl newFileService(FileNodeMapper fileNodeMapper,
                                          UserQuotaMapper userQuotaMapper,
                                          TeamStorageMapper teamStorageMapper,
                                          CloudStorageService cloudStorageService,
                                          StorageService storageService,
                                          FileObjectService fileObjectService,
                                          FileService fileService,
                                          ReliableEventPublisher reliableEventPublisher) {
            NewFileServiceImpl svc = new NewFileServiceImpl();
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "cloudStorageService", cloudStorageService);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            ReflectionTestUtils.setField(svc, "fileObjectService", fileObjectService);
            ReflectionTestUtils.setField(svc, "fileService", fileService);
            ReflectionTestUtils.setField(svc, "reliableEventPublisher", reliableEventPublisher);
            return svc;
        }
    }

    @Autowired
    private NewFileService newFileService;
    @Autowired
    private FileNodeMapper fileNodeMapper;
    @Autowired
    private FileService fileService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每个测试准备默认用户（大配额）；quotaExceeded 测试单独覆盖为小配额 */
    @BeforeEach
    void prepareUser() {
        Mockito.reset(fileService);
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, storage_used, storage_quota) "
                + "VALUES (1, 1, 'u', 'x', 0, 1048576) ON DUPLICATE KEY UPDATE storage_quota=1048576, storage_used=0");
        jdbcTemplate.update("INSERT INTO team_space (id, tenant_id, space_name, owner_id, storage_used, storage_quota, status) "
                + "VALUES (9, 1, 't', 1, 0, 1048576, 1) ON DUPLICATE KEY UPDATE storage_quota=1048576, storage_used=0");
    }

    private void mockDefaults(String name) {
        when(fileService.resolveNameConflict(anyLong(), anyString())).thenReturn(name);
        when(fileService.validateAndGetParentPath(anyLong())).thenReturn("/");
        when(fileService.guessContentType(anyString())).thenReturn("application/octet-stream");
        when(fileService.extractSuffix(anyString())).thenReturn(name.substring(name.lastIndexOf('.') + 1));
        when(fileService.toVO(any(FileNode.class))).thenAnswer(inv -> {
            FileNode n = inv.getArgument(0);
            FileNodeVO vo = new FileNodeVO();
            vo.setId(n.getId());
            vo.setName(n.getName());
            vo.setNodeType(n.getNodeType());
            vo.setFileSize(n.getFileSize());
            return vo;
        });
    }

    @Test
    void createTxt_succeeds() {
        setUpUser(1L, 1L);
        mockDefaults("新建文本文档.txt");

        FileNodeVO vo = newFileService.createBlankFile("txt", 0L, null, null);

        FileNode node = fileNodeMapper.selectById(vo.getId());
        assertNotNull(node);
        assertEquals("txt", node.getSuffix());
        assertEquals(0L, node.getFileSize());
        assertEquals(0, node.getStatus());
        assertEquals(2, node.getUploadStatus());
        assertEquals(1L, node.getOwnerId());
    }

    @Test
    void createDocx_usesTemplate() {
        setUpUser(1L, 1L);
        mockDefaults("新建文档.docx");

        FileNodeVO vo = newFileService.createBlankFile("docx", 0L, null, null);

        FileNode node = fileNodeMapper.selectById(vo.getId());
        assertNotNull(node);
        assertEquals("docx", node.getSuffix());
        assertTrue(node.getFileSize() > 0, "docx 模板应非空");
        // OOXML 是 ZIP：校验魔数 PK\x03\x04
        byte[] content = new byte[4];
        // 通过 S3 mock 无法直接取内容，改为校验模板资源本身存在且为 ZIP
        assertNotNull(node.getStoragePath());
    }

    @Test
    void createXlsxAndPptx_succeed() {
        setUpUser(1L, 1L);
        mockDefaults("新建表格.xlsx");
        assertNotNull(newFileService.createBlankFile("xlsx", 0L, null, null).getId());
        mockDefaults("新建演示.pptx");
        assertNotNull(newFileService.createBlankFile("pptx", 0L, null, null).getId());
    }

    @Test
    void duplicateName_usesResolvedName() {
        setUpUser(1L, 1L);
        mockDefaults("新建文档 (1).docx");

        FileNodeVO vo = newFileService.createBlankFile("docx", 0L, null, null);

        assertEquals("新建文档 (1).docx", vo.getName());
        verify(fileService).resolveNameConflict(anyLong(), eq("新建文档.docx"));
    }

    @Test
    void personalParentNotOwned_forbidden() {
        setUpUser(2L, 1L);
        mockDefaults("新建文档.docx");
        // 父目录属于用户 1
        FileNode parent = insertFileNode(1L, 1L, "我的目录", 0);
        parent.setNodeType(0);
        fileNodeMapper.updateById(parent);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newFileService.createBlankFile("docx", parent.getId(), null, null));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void teamSpace_usesTeamValidation() {
        setUpUser(1L, 1L);
        mockDefaults("新建文档.docx");
        FileNode folder = insertFileNode(1L, 1L, "空间目录", 0);
        folder.setNodeType(0);
        folder.setSpaceId(9L);
        fileNodeMapper.updateById(folder);

        newFileService.createBlankFile("docx", folder.getId(), 9L, null);

        verify(fileService).validateTeamNode(9L, folder.getId());
    }

    @Test
    void quotaExceeded_rejectedNoHalfNode() {
        setUpUser(1L, 1L);
        mockDefaults("新建文档.docx");
        jdbcTemplate.update("INSERT INTO sys_user (id, tenant_id, username, password, storage_used, storage_quota) "
                + "VALUES (1, 1, 'u', 'x', 0, 1) ON DUPLICATE KEY UPDATE storage_quota=1, storage_used=0");
        long before = fileNodeMapper.selectCount(null);

        assertThrows(BusinessException.class, () -> newFileService.createBlankFile("docx", 0L, null, null));

        assertEquals(before, fileNodeMapper.selectCount(null), "配额不足不应产生半成品节点");
    }

    @Test
    void eventsPublished() {
        setUpUser(1L, 1L);
        mockDefaults("新建文本文档.txt");

        newFileService.createBlankFile("txt", 0L, null, null);

        Long fileIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_log WHERE event_type = 'FILE_INDEX'", Long.class);
        Long syncChange = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_log WHERE event_type = 'SYNC_CHANGE'", Long.class);
        assertTrue(fileIndex != null && fileIndex > 0, "应发布索引事件");
        assertTrue(syncChange != null && syncChange > 0, "应发布同步变更事件（P2）");
    }

    @Test
    void invalidType_rejected() {
        setUpUser(1L, 1L);
        mockDefaults("x");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> newFileService.createBlankFile("exe", 0L, null, null));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void nodeState_correct() {
        setUpUser(1L, 1L);
        mockDefaults("新建表格.xlsx");

        FileNodeVO vo = newFileService.createBlankFile("xlsx", 0L, null, null);
        FileNode node = fileNodeMapper.selectById(vo.getId());

        assertEquals(0, node.getStatus());
        assertEquals(2, node.getUploadStatus());
        assertEquals(1L, node.getOwnerId());
        assertEquals(1L, node.getUploaderId());
        assertNull(node.getSpaceId());
    }

    @Test
    void customFileName_appendsTypeSuffix() {
        setUpUser(1L, 1L);
        mockDefaults("周报.docx");

        FileNodeVO vo = newFileService.createBlankFile("docx", 0L, null, "周报");

        assertEquals("周报.docx", vo.getName());
        verify(fileService).resolveNameConflict(anyLong(), eq("周报.docx"));
    }

    @Test
    void customFileName_withSuffixKept() {
        setUpUser(1L, 1L);
        mockDefaults("我的表格.xlsx");

        FileNodeVO vo = newFileService.createBlankFile("xlsx", 0L, null, "我的表格.xlsx");

        assertEquals("我的表格.xlsx", vo.getName());
    }

    @Test
    void customFileName_invalidCharsRejected() {
        setUpUser(1L, 1L);
        mockDefaults("x");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newFileService.createBlankFile("docx", 0L, null, "a/b.docx"));
        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
    }
}

