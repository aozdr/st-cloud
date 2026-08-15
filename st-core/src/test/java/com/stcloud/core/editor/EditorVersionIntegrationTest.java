package com.stcloud.core.editor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.AbstractIntegrationTest;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.FileVersion;
import com.stcloud.core.event.ReliableEventPublisher;
import com.stcloud.core.mapper.EventLogMapper;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.FileVersionMapper;
import com.stcloud.core.mapper.TeamStorageMapper;
import com.stcloud.core.mapper.UserQuotaMapper;
import com.stcloud.core.service.CloudStorageService;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.FileService;
import com.stcloud.core.service.StorageService;
import com.stcloud.core.service.impl.FileObjectServiceImpl;
import com.stcloud.core.service.impl.VersionServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 编辑器版本 source 与裁剪集成测试（H2 + 真实 Mapper）。
 * 覆盖 TC-15/16（版本来源标记）、TC-17（仅裁剪 source=1 且上限 20）、TC-19（编辑中版本恢复被拦截）。
 */
@Import(EditorVersionIntegrationTest.EditorVersionTestConfig.class)
class EditorVersionIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class EditorVersionTestConfig {

        @Bean
        EditorLockService editorLockService() {
            return new EditorLockService(null, "memory");
        }

        @Bean
        StorageService storageService() {
            return mock(StorageService.class);
        }

        @Bean
        FileObjectService fileObjectService(FileObjectMapper fileObjectMapper, StorageService storageService) {
            FileObjectServiceImpl svc = new FileObjectServiceImpl();
            ReflectionTestUtils.setField(svc, "fileObjectMapper", fileObjectMapper);
            ReflectionTestUtils.setField(svc, "storageService", storageService);
            return svc;
        }

        @Bean
        FileService fileService() {
            return mock(FileService.class);
        }

        @Bean
        CloudStorageService cloudStorageService() {
            return mock(CloudStorageService.class);
        }

        @Bean
        RocketMQTemplate rocketMQTemplate() {
            return mock(RocketMQTemplate.class);
        }

        @Bean
        ReliableEventPublisher reliableEventPublisher(EventLogMapper eventLogMapper,
                                                       ApplicationEventPublisher eventPublisher,
                                                       ObjectMapper objectMapper) {
            return new ReliableEventPublisher(eventLogMapper, eventPublisher, objectMapper);
        }

        @Bean
        VersionServiceImpl versionService(FileVersionMapper fileVersionMapper,
                                          FileNodeMapper fileNodeMapper,
                                          FileService fileService,
                                          FileObjectService fileObjectService,
                                          ReliableEventPublisher reliableEventPublisher,
                                          UserQuotaMapper userQuotaMapper,
                                          TeamStorageMapper teamStorageMapper,
                                          CloudStorageService cloudStorageService,
                                          EditorLockService editorLockService) {
            VersionServiceImpl svc = new VersionServiceImpl();
            ReflectionTestUtils.setField(svc, "fileVersionMapper", fileVersionMapper);
            ReflectionTestUtils.setField(svc, "fileNodeMapper", fileNodeMapper);
            ReflectionTestUtils.setField(svc, "fileService", fileService);
            ReflectionTestUtils.setField(svc, "fileObjectService", fileObjectService);
            ReflectionTestUtils.setField(svc, "reliableEventPublisher", reliableEventPublisher);
            ReflectionTestUtils.setField(svc, "userQuotaMapper", userQuotaMapper);
            ReflectionTestUtils.setField(svc, "teamStorageMapper", teamStorageMapper);
            ReflectionTestUtils.setField(svc, "cloudStorageService", cloudStorageService);
            ReflectionTestUtils.setField(svc, "editorLockService", editorLockService);
            return svc;
        }
    }

    @Autowired
    private VersionServiceImpl versionService;
    @Autowired
    private FileVersionMapper fileVersionMapper;
    @Autowired
    private FileNodeMapper fileNodeMapper;
    @Autowired
    private FileService fileService;
    @Autowired
    private EditorLockService editorLockService;

    private FileVersion insertVersion(Long nodeId, int versionNum, int source) {
        FileVersion v = new FileVersion();
        v.setTenantId(1L);
        v.setFileNodeId(nodeId);
        v.setVersionNum(versionNum);
        v.setFileSize(100L);
        v.setFileMd5("md5-" + nodeId + "-" + versionNum + "-" + source);
        v.setStoragePath("t1/v" + versionNum);
        v.setModifierId(1L);
        v.setSource(source);
        v.setCreatedAt(LocalDateTime.now().minusMinutes(versionNum));
        fileVersionMapper.insert(v);
        return v;
    }

    @Test
    void snapshotWithSource1_marksEditorVersion() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        node.setFileMd5("md5-before-edit");
        node.setStoragePath("t1/before-edit.docx");
        fileNodeMapper.updateById(node);
        setUpUser(1L, 1L);

        versionService.snapshotCurrentVersion(node, 1);

        FileVersion v = fileVersionMapper.selectOne(new LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileNodeId, node.getId()));
        assertNotNull(v);
        assertEquals(1, v.getSource(), "编辑器保存版本 source 应为 1");
    }

    @Test
    void snapshotDefault_sourceIs0() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        node.setFileMd5("md5-before-edit");
        node.setStoragePath("t1/before-edit.docx");
        fileNodeMapper.updateById(node);
        setUpUser(1L, 1L);

        versionService.snapshotCurrentVersion(node);

        FileVersion v = fileVersionMapper.selectOne(new LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileNodeId, node.getId()));
        assertNotNull(v);
        assertEquals(0, v.getSource(), "上传覆盖版本 source 应为 0");
    }

    /** 匿名回调（无 UserContext）快照版本：modifier_id 兜底为文件 owner，避免 NOT NULL 约束失败（20260815 实测） */
    @Test
    void snapshotAnonymous_usesOwnerAsModifier() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        node.setFileMd5("md5-anon");
        node.setStoragePath("t1/anon.docx");
        fileNodeMapper.updateById(node);
        // 不调用 setUpUser：模拟 OnlyOffice 保存回调（匿名请求，UserContext 为空）

        versionService.snapshotCurrentVersion(node, 1);

        FileVersion v = fileVersionMapper.selectOne(new LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileNodeId, node.getId()));
        assertNotNull(v);
        assertEquals(1L, v.getModifierId(), "匿名场景 modifier_id 应兜底为文件 owner");
        assertEquals(1, v.getSource());
    }

    @Test
    void prune_onlyTrimsEditorVersionsBeyondLimit() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        Long nodeId = node.getId();
        // 3 条上传覆盖版本（source=0，不受裁剪）+ 25 条编辑器版本（source=1，超上限 20）
        for (int i = 1; i <= 3; i++) {
            insertVersion(nodeId, i, 0);
        }
        for (int i = 4; i <= 28; i++) {
            insertVersion(nodeId, i, 1);
        }

        versionService.pruneEditorVersions(nodeId, 20);

        Long editorCount = fileVersionMapper.selectCount(new LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileNodeId, nodeId).eq(FileVersion::getSource, 1));
        Long uploadCount = fileVersionMapper.selectCount(new LambdaQueryWrapper<FileVersion>()
                .eq(FileVersion::getFileNodeId, nodeId).eq(FileVersion::getSource, 0));
        assertEquals(20L, editorCount, "编辑器版本裁剪到上限 20（D1）");
        assertEquals(3L, uploadCount, "上传覆盖版本不受裁剪影响（D1）");
    }

    @Test
    void restoreVersion_blockedWhileEditing() {
        FileNode node = insertFileNode(1L, 1L, "报告.docx", 0);
        insertVersion(node.getId(), 1, 0);
        setUpUser(1L, 1L);
        when(fileService.getNodeByIdAndOwner(node.getId())).thenReturn(node);
        editorLockService.markEditing(node.getId(), "1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> versionService.restoreVersion(node.getId(), node.getId()));
        assertEquals(ResultCode.FILE_EDITING.getCode(), ex.getCode());
    }
}
