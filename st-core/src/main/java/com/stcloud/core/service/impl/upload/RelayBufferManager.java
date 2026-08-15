package com.stcloud.core.service.impl.upload;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.config.UploadRelayConfig;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 中转上传缓冲管理器：按 uploadId 隔离临时文件，累积小块至 multipart 分片下限(5MB)后 uploadPart。
 * 末片(不足 5MB)在 finalize 时上传。临时文件用完即删（finalize/失败/超时），防止磁盘泄漏。
 * 超时清理：惰性扫描（createSession 时）+ 定时任务（@Scheduled），超时会话同时 abort S3 multipart。
 */
@Slf4j
@Component
public class RelayBufferManager {

    @Resource
    private UploadRelayConfig config;

    @Resource
    private UploadStorageManager storageManager;

    private final ConcurrentMap<String, RelaySession> sessions = new ConcurrentHashMap<>();

    /** 中转会话状态 */
    static final class RelaySession {
        final String uploadId;
        final Path tempFile;
        final long rateBytes;
        final String storagePath;
        final String s3UploadId;
        final long relayChunkSize;
        OutputStream out;
        long accumulated;
        int nextPartNumber = 1;
        long lastActiveMs;
        int lastSeq;

        RelaySession(String uploadId, Path tempFile, long rateBytes,
                     String storagePath, String s3UploadId, long relayChunkSize) throws IOException {
            this.uploadId = uploadId;
            this.tempFile = tempFile;
            this.rateBytes = rateBytes;
            this.storagePath = storagePath;
            this.s3UploadId = s3UploadId;
            this.relayChunkSize = relayChunkSize;
            this.out = new BufferedOutputStream(
                    Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            this.lastActiveMs = System.currentTimeMillis();
        }
    }

    /** 创建中转会话（init 时调用），同时惰性清理过期会话 */
    public synchronized void createSession(String uploadId, long rateBytes,
                                           String storagePath, String s3UploadId, long relayChunkSize) {
        cleanupExpired();
        if (sessions.containsKey(uploadId)) {
            return;
        }
        try {
            Path tempFile = config.getTempDirFile().toPath().resolve(sanitize(uploadId) + ".tmp");
            sessions.put(uploadId, new RelaySession(uploadId, tempFile, rateBytes,
                    storagePath, s3UploadId, relayChunkSize));
        } catch (IOException e) {
            log.error("创建中转临时文件失败: uploadId={}", uploadId, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /** 获取会话的有效限速(字节/秒，0=不限速)，relayChunk pacing 使用 */
    public long getRate(String uploadId) {
        RelaySession session = sessions.get(uploadId);
        return session != null ? session.rateBytes : 0L;
    }

    /** 获取会话的中转小块上限（字节），用于 Content-Length 校验；会话缺失返回 0（不限制） */
    public long getRelayChunkSize(String uploadId) {
        RelaySession session = sessions.get(uploadId);
        return session != null ? session.relayChunkSize : 0L;
    }

    /**
     * 追加一个小块到缓冲。累积达到分片下限时触发 uploadPart。
     * 返回触发的 partNumber（0=未触发）。
     */
    public synchronized int appendChunk(String uploadId, byte[] data, int offset, int length,
                                        String storagePath, String s3UploadId) {
        RelaySession session = getSession(uploadId);
        try {
            session.out.write(data, offset, length);
            session.accumulated += length;
            session.lastActiveMs = System.currentTimeMillis();
        } catch (IOException e) {
            log.error("写入中转临时文件失败: uploadId={}", uploadId, e);
            abortSession(uploadId);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
        if (session.accumulated >= config.getPartMinSize()) {
            return flushPart(uploadId, storagePath, s3UploadId);
        }
        return 0;
    }

    /**
     * 请求级 seq 原子认领（幂等）：同一 uploadId 的 seq 单调递增，客户端顺序发送。
     * 已确认过的 seq（<= lastSeq）返回 false，调用方跳过整个请求，不重复写字节。
     */
    public synchronized boolean tryAcquireSeq(String uploadId, int seq) {
        RelaySession session = getSession(uploadId);
        if (seq <= session.lastSeq) {
            return false;
        }
        session.lastSeq = seq;
        return true;
    }

    /** finalize：上传末片（余量，无 5MB 下限），返回末片 partNumber（0=无余量） */
    public synchronized int finalize(String uploadId, String storagePath, String s3UploadId) {
        RelaySession session = getSession(uploadId);
        int lastPart = 0;
        try {
            session.out.flush();
            session.out.close();
            if (session.accumulated > 0) {
                lastPart = session.nextPartNumber;
                try (InputStream in = Files.newInputStream(session.tempFile)) {
                    storageManager.uploadPart(storagePath, s3UploadId, lastPart, in, session.accumulated);
                }
            }
            log.info("中转 finalize 完成: uploadId={}, lastPart={}", uploadId, lastPart);
        } catch (IOException e) {
            log.error("中转 finalize 失败: uploadId={}", uploadId, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        } finally {
            cleanup(uploadId);
        }
        return lastPart;
    }

    /** 将当前缓冲作为一个 part 上传到 S3，重置累积 */
    private int flushPart(String uploadId, String storagePath, String s3UploadId) {
        RelaySession session = getSession(uploadId);
        try {
            session.out.flush();
            session.out.close();
            int partNumber = session.nextPartNumber++;
            try (InputStream in = Files.newInputStream(session.tempFile)) {
                storageManager.uploadPart(storagePath, s3UploadId, partNumber, in, session.accumulated);
            }
            log.debug("中转 flushPart: uploadId={}, part={}, size={}", uploadId, partNumber, session.accumulated);
            // 截断临时文件，继续累积下一 part
            session.out = new BufferedOutputStream(
                    Files.newOutputStream(session.tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            session.accumulated = 0;
            return partNumber;
        } catch (IOException e) {
            log.error("中转 flushPart 失败: uploadId={}", uploadId, e);
            abortSession(uploadId);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /** 清理指定会话的临时文件 */
    public void cleanup(String uploadId) {
        RelaySession session = sessions.remove(uploadId);
        if (session != null) {
            try {
                session.out.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(session.tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    /** 惰性清理过期会话（超时未活跃） */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (var entry : sessions.entrySet()) {
            RelaySession s = entry.getValue();
            if (now - s.lastActiveMs > config.getSessionTimeoutMs()) {
                log.warn("中转会话超时清理: uploadId={}", entry.getKey());
                abortSession(entry.getKey());
            }
        }
    }

    /** 定时清理超时会话：abort S3 multipart + 删除临时文件（TASK-02） */
    @Scheduled(fixedDelayString = "${stcloud.upload.relay.cleanup-interval-ms:60000}")
    public void scheduledCleanup() {
        cleanupExpired();
    }

    /** 中止并清理指定会话：先 abort S3 multipart（幂等），再删除临时文件 */
    private void abortSession(String uploadId) {
        RelaySession session = sessions.get(uploadId);
        if (session != null && session.storagePath != null && session.s3UploadId != null) {
            try {
                storageManager.abortMultipart(session.storagePath, session.s3UploadId);
            } catch (Exception e) {
                log.warn("中转会话 abort S3 失败: uploadId={}, error={}", uploadId, e.getMessage());
            }
        }
        cleanup(uploadId);
    }

    private RelaySession getSession(String uploadId) {
        RelaySession session = sessions.get(uploadId);
        if (session == null) {
            throw new BusinessException(ResultCode.CHUNK_NOT_FOUND);
        }
        return session;
    }

    /** 文件名净化：仅保留字母数字，防路径穿越 */
    private static String sanitize(String uploadId) {
        return uploadId.replaceAll("[^a-zA-Z0-9]", "");
    }

    @PreDestroy
    public void destroy() {
        sessions.keySet().forEach(this::abortSession);
    }
}
