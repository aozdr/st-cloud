package com.stcloud.core.service.impl;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.entity.FileObject;
import com.stcloud.core.entity.OrphanObjectCandidate;
import com.stcloud.core.mapper.FileNodeMapper;
import com.stcloud.core.mapper.FileObjectMapper;
import com.stcloud.core.mapper.OrphanObjectCandidateMapper;
import com.stcloud.core.mapper.UploadSessionMapper;
import com.stcloud.core.service.impl.upload.UploadStorageManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规范对象孤儿候选协调器。
 *
 * <p>核心安全规则：失败路径只登记候选；真正删除前必须重新确认 NORMAL file_object、有效
 * file_node 引用和活动 upload_session 三者均不存在。所有外部存储调用均在数据库操作之外执行。</p>
 */
@Slf4j
@Service
public class OrphanObjectCleanupService {

    private static final int CANDIDATE_PENDING = 1;
    private static final int CANDIDATE_DELETING = 2;

    @Resource
    private OrphanObjectCandidateMapper candidateMapper;
    @Resource
    private FileObjectMapper fileObjectMapper;
    @Resource
    private FileNodeMapper fileNodeMapper;
    @Resource
    private UploadSessionMapper uploadSessionMapper;
    @Resource
    private UploadStorageManager storageManager;

    @Value("${stcloud.upload.orphan.grace-ms:3600000}")
    private long graceMs;

    /** 在规范对象写入前登记活动请求；若候选已被领取删除，拒绝复用同一路径。 */
    public void beginUpload(Long tenantId, String md5, String storagePath) {
        OrphanObjectCandidate candidate = new OrphanObjectCandidate();
        candidate.setTenantId(tenantId);
        candidate.setMd5(md5);
        candidate.setStoragePath(storagePath);
        if (candidateMapper.insertIfAbsent(candidate) == 1) {
            return;
        }
        OrphanObjectCandidate existing = candidateMapper.selectByTenantAndPath(tenantId, storagePath);
        if (existing == null || existing.getStatus() == CANDIDATE_DELETING
                || candidateMapper.incrementActive(existing.getId()) != 1) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "规范对象正在回收，请稍后重试");
        }
    }

    /** 提交事务成功后对象已有业务引用，候选记录可以安全移除。 */
    public void markCommitted(Long tenantId, String storagePath) {
        OrphanObjectCandidate candidate = candidateMapper.selectByTenantAndPath(tenantId, storagePath);
        if (candidate != null) {
            candidateMapper.deleteCandidate(candidate.getId());
        }
    }

    /** 提交失败或外部写入失败，释放活动计数并进入宽限期候选。 */
    public void markFailed(Long tenantId, String storagePath) {
        try {
            OrphanObjectCandidate candidate = candidateMapper.selectByTenantAndPath(tenantId, storagePath);
            if (candidate == null) {
                return;
            }
            candidateMapper.decrementActive(candidate.getId());
            candidateMapper.markPendingIfInactive(candidate.getId());
        } catch (RuntimeException e) {
            // 清理协调失败不能覆盖原始上传错误；候选记录保留给后续人工/定时补偿。
            log.warn("登记规范对象孤儿候选失败: tenantId={}, storagePath={}", tenantId, storagePath, e);
        }
    }

    @Scheduled(fixedDelayString = "${stcloud.upload.orphan.cleanup-interval-ms:60000}")
    public void scheduledCleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusNanos(Math.max(0L, graceMs) * 1_000_000L);
        List<OrphanObjectCandidate> candidates = candidateMapper.selectDue(cutoff, 100);
        for (OrphanObjectCandidate candidate : candidates) {
            if (candidateMapper.claimForDelete(candidate.getId(), cutoff) != 1) {
                continue;
            }
            cleanupClaimed(candidate);
        }
    }

    private void cleanupClaimed(OrphanObjectCandidate candidate) {
        try {
            FileObject normal = fileObjectMapper.selectByTenantAndMd5(candidate.getTenantId(), candidate.getMd5());
            long nodeRefs = fileNodeMapper.countValidRefsByTenantAndPath(candidate.getTenantId(),
                    candidate.getMd5(), candidate.getStoragePath());
            long activeSessions = uploadSessionMapper.countActiveByTenantAndPath(candidate.getTenantId(),
                    candidate.getStoragePath());
            if (normal != null || nodeRefs > 0 || activeSessions > 0) {
                // 复核发现新引用或新会话，宁可延迟回收也不能误删共享对象。
                candidateMapper.releaseDelete(candidate.getId());
                return;
            }
            if (!storageManager.deleteObjectQuietly(candidate.getStoragePath())) {
                candidateMapper.releaseDelete(candidate.getId());
                return;
            }
            candidateMapper.deleteCandidate(candidate.getId());
            log.info("已回收宽限期后的规范对象孤儿: tenantId={}, md5={}, storagePath={}",
                    candidate.getTenantId(), candidate.getMd5(), candidate.getStoragePath());
        } catch (RuntimeException e) {
            candidateMapper.releaseDelete(candidate.getId());
            log.warn("规范对象孤儿回收失败，保留候选: tenantId={}, storagePath={}",
                    candidate.getTenantId(), candidate.getStoragePath(), e);
        }
    }
}
