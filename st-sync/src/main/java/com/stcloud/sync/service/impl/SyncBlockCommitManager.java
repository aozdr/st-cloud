package com.stcloud.sync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stcloud.common.context.UserContext;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.service.FileObjectService;
import com.stcloud.core.service.VersionService;
import com.stcloud.core.service.impl.upload.UploadEventPublisher;
import com.stcloud.core.service.impl.upload.UploadManager;
import com.stcloud.sync.dto.BlockCheckRequest;
import com.stcloud.sync.dto.BlockUploadRequest;
import com.stcloud.sync.dto.BlockUploadResponse;
import com.stcloud.sync.entity.FileBlock;
import com.stcloud.sync.mapper.FileBlockMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 块级上传 DB 落库事务协作 bean（事务边界治理 F3 / TX-04）。
 * <p>
 * 职责：承接 blockUpload 完成后"必须在一个数据库事务内完成"的写操作——去重归属（acquireByPath）
 * + 节点更新 + 旧对象引用释放 + 版本快照 + 块布局重建 + 差值配额 + 同步事件。
 * S3 uploadPartCopy / completeMultipartUpload / 去重命中清理全部在事务外执行（调用方 SyncBlockServiceImpl）；
 * 本类方法经 Spring 代理跨 bean 调用保证 {@link Transactional} 生效，事务回滚时已合并的 S3 对象
 * 由调用方按"引用归零才删除"规则尽力清理，另有定时任务兜底。
 */
@Slf4j
@Component
public class SyncBlockCommitManager {

    @Resource
    private FileBlockMapper fileBlockMapper;
    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private FileObjectService fileObjectService;
    @Resource
    private VersionService versionService;
    @Resource
    private UploadEventPublisher uploadEventPublisher;
    @Resource
    private UploadManager uploadManager;

    /**
     * 块级上传落库（F3）：事务内完成 去重归属 + 节点更新 + 旧对象引用释放 + 版本快照
     * + 块布局重建 + 同步事件 + 差值配额。
     * 调用方已事务外完成 S3 复制/合并；本方法仅做 DB 写，网络耗时不再占用事务连接。
     *
     * @param node        当前文件节点（可变对象：事务内更新存储路径/对象ID/版本等，
     *                    调用方可据此判断去重命中并决定是否清理合并产物）
     * @param tenantId    租户ID
     * @param oldSize     原文件大小（差值配额用）
     * @param oldVersion  原版本号（块布局替换依据）
     * @param oldObjectId 原对象ID（引用释放用）
     * @param request     块级上传请求（新 md5/大小/块列表/存储路径）
     */
    @Transactional
    public BlockUploadResponse commitBlockUpload(FileNode node, Long tenantId, long oldSize,
                                                 int oldVersion, Long oldObjectId,
                                                 BlockUploadRequest request) {
        // 1. 去重归属：按新 md5 归属 file_object；同 md5 已存在则复用（引用 +1），
        //    未命中则以本次合并产物路径创建对象记录（仅 DB，物理对象已由 S3 合并产生）
        FileObject object = fileObjectService.acquireByPath(tenantId, request.getFileMd5(),
                request.getFileSize(), request.getStoragePath());

        // 2. 更新文件节点：指向最终对象（去重命中时指向已存在对象路径）
        node.setStoragePath(object != null ? object.getStoragePath() : request.getStoragePath());
        node.setObjectId(object != null ? object.getId() : null);
        node.setFileMd5(request.getFileMd5());
        node.setFileSize(request.getFileSize());
        node.setVersion(oldVersion + 1);
        node.setUploadStatus(2); // 已完成
        fileNodeMapper.updateById(node);

        // 旧对象引用释放（保留物理对象，可能被版本历史引用）
        if (oldObjectId != null && (object == null || !oldObjectId.equals(object.getId()))) {
            fileObjectService.release(oldObjectId);
        }

        // 3. 创建版本快照
        versionService.snapshotCurrentVersion(node);

        // 4. 重建新版本块布局：先删旧版本记录，再批量插入（与改造前行为一致，仅保留新版本块布局）
        fileBlockMapper.delete(new LambdaQueryWrapper<FileBlock>()
                .eq(FileBlock::getFileNodeId, node.getId())
                .eq(FileBlock::getVersion, oldVersion));
        String finalStoragePath = node.getStoragePath();
        for (BlockCheckRequest.BlockHash block : request.getBlocks()) {
            FileBlock fb = new FileBlock();
            fb.setTenantId(tenantId);
            fb.setFileNodeId(node.getId());
            fb.setVersion(oldVersion + 1);
            fb.setBlockIndex(block.getIndex());
            fb.setBlockMd5(block.getMd5());
            fb.setBlockSize(block.getSize());
            fb.setStoragePath(finalStoragePath);
            fileBlockMapper.insert(fb);
        }

        // 5. 发布同步变更事件（UPDATE）+ 索引事件
        uploadEventPublisher.publishUpdated(node);

        // 6. 按差值计配额
        long delta = request.getFileSize() - oldSize;
        uploadManager.consumeQuota(UserContext.getUserId(), node.getSpaceId(), delta);

        BlockUploadResponse resp = new BlockUploadResponse();
        resp.setFileId(String.valueOf(node.getId()));
        resp.setVersion(node.getVersion());
        log.info("块级上传落库完成: nodeId={}, version={}, blocks={}, delta={}",
                node.getId(), node.getVersion(), request.getBlocks().size(), delta);
        return resp;
    }
}
