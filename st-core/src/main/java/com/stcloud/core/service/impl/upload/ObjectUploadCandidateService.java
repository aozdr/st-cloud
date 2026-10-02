package com.stcloud.core.service.impl.upload;

import com.stcloud.core.service.StorageService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** 持久候选状态与业务认领共用数据库锁；物理对象路径永不复用，S3 始终在事务外。 */
@Service
@Slf4j
public class ObjectUploadCandidateService {
    /** WRITING 写入中，READY 写完待提交，ADOPTED 已采用，DISCARDED 放弃，DELETING 回收墓碑，DELETED 已回收。 */
    public enum State { WRITING, READY, ADOPTED, DISCARDED, DELETING, DELETED }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;
    private final ObjectProvider<StorageService> storage;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "object-candidate-heartbeat"); thread.setDaemon(true); return thread;
    });
    @Value("${stcloud.upload.candidate.grace-period-ms:3600000}")
    private long gracePeriodMs = 3600000;
    @Value("${stcloud.upload.candidate.retry-interval-ms:60000}")
    private long retryIntervalMs = 60000;
    @Value("${stcloud.upload.candidate.batch-size:100}")
    private int batchSize = 100;

    public ObjectUploadCandidateService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                        ObjectProvider<StorageService> storage) {
        this.jdbc = jdbc; this.storage = storage;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private Timestamp later(long delay) { return Timestamp.from(Instant.now().plusMillis(Math.max(1, delay))); }
    public static boolean isManagedPath(String path) { return path != null && path.matches("[0-9]+/objects/[^/]+/[0-9a-fA-F-]{36}"); }
    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("对象存储操作禁止在数据库事务内执行");
    }
    public String register(Long tenantId, String md5) {
        String path = tenantId + "/objects/" + md5 + "/" + UUID.randomUUID();
        independent.executeWithoutResult(tx -> jdbc.update("INSERT INTO object_upload_candidate "
                + "(tenant_id,md5,storage_path,state,write_finished,next_check_at) VALUES (?,?,?,'WRITING',0,?)",
                tenantId, md5, path, later(gracePeriodMs)));
        return path;
    }
    /** 长时间限速 PUT 持续续活；续活失败时宁可禁止后续提交，也不能复用已被 GC 认领的路径。 */
    public String upload(Long tenantId, String md5, InputStream stream, long size, String contentType) {
        outsideTransaction();
        String path = register(tenantId, md5);
        long interval = Math.max(1, Math.min(30000, gracePeriodMs / 3));
        ScheduledFuture<?> pulse = heartbeat.scheduleWithFixedDelay(() -> {
            try { renew(tenantId, path); }
            catch (RuntimeException e) { log.warn("候选续活失败: path={}", path, e); }
        }, interval, interval, TimeUnit.MILLISECONDS);
        try {
            storage.getObject().uploadObject(path, stream, size, contentType);
            if (!finishWrite(tenantId, path)) throw new IllegalStateException("上传候选已被回收认领，请重新上传");
            return path;
        } catch (RuntimeException e) {
            discard(tenantId, path);
            throw e;
        } finally { pulse.cancel(false); }
    }
    public void renew(Long tenantId, String path) {
        independent.executeWithoutResult(tx -> jdbc.update("UPDATE object_upload_candidate SET updated_at=CURRENT_TIMESTAMP, next_check_at=? "
                + "WHERE tenant_id=? AND storage_path=? AND state='WRITING'", later(gracePeriodMs), tenantId, path));
    }
    /** 仅 PUT 明确成功才标记结束；迟到 PUT 不得把 DELETING 恢复为可提交状态。 */
    public boolean finishWrite(Long tenantId, String path) {
        return Boolean.TRUE.equals(independent.execute(tx -> {
            int ready = jdbc.update("UPDATE object_upload_candidate SET state='READY',write_finished=1,updated_at=CURRENT_TIMESTAMP,next_check_at=? "
                    + "WHERE tenant_id=? AND storage_path=? AND state='WRITING'", later(gracePeriodMs), tenantId, path);
            if (ready == 0) jdbc.update("UPDATE object_upload_candidate SET write_finished=1,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE tenant_id=? AND storage_path=? AND state IN ('DELETING','DISCARDED')", tenantId, path);
            return ready == 1;
        }));
    }
    /** 加入调用者的业务事务，READY→ADOPTED 与 GC CAS 互斥；无候选的新独立路径一律拒绝。 */
    public boolean claim(Long tenantId, String md5, String path) {
        if (!isManagedPath(path)) return false;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("候选认领必须加入业务事务");
        int changed = jdbc.update("UPDATE object_upload_candidate SET state='ADOPTED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE tenant_id=? AND md5=? AND storage_path=? AND state='READY' AND write_finished=1", tenantId, md5, path);
        if (changed == 1) return true;
        // 已采用的正常对象可以再次被节点/版本引用，但失败或回收中的候选绝不允许重用。
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM object_upload_candidate c JOIN file_object o "
                + "ON o.tenant_id=c.tenant_id AND o.md5=c.md5 AND o.storage_path=c.storage_path "
                + "WHERE c.tenant_id=? AND c.md5=? AND c.storage_path=? AND c.state='ADOPTED' AND o.status=0 AND o.deleted=0", Long.class, tenantId, md5, path);
        if (existing != null && existing > 0) return false;
        throw new IllegalStateException("上传候选不可认领，请重新上传");
    }
    /** 去重败者与节点/引用在同一事务转为 DISCARDED，不立即物理删除。 */
    public void discardClaim(Long tenantId, String path) {
        jdbc.update("UPDATE object_upload_candidate SET state='DISCARDED',updated_at=CURRENT_TIMESTAMP,next_check_at=? "
                + "WHERE tenant_id=? AND storage_path=? AND state='ADOPTED'", later(gracePeriodMs), tenantId, path);
    }
    public void discard(Long tenantId, String path) {
        try {
            independent.executeWithoutResult(tx -> jdbc.update("UPDATE object_upload_candidate SET state='DISCARDED',updated_at=CURRENT_TIMESTAMP,next_check_at=? "
                    + "WHERE tenant_id=? AND storage_path=? AND state IN ('WRITING','READY')", later(gracePeriodMs), tenantId, path));
        } catch (RuntimeException e) { log.warn("候选放弃标记失败，持久扫描仍会重试: path={}", path, e); }
    }
    private record Candidate(long id, long tenantId, String md5, String path, boolean finished) {}

    @Scheduled(fixedDelayString="${stcloud.upload.candidate.scan-interval-ms:60000}")
    public void collectGarbage() {
        outsideTransaction();
        List<Long> ids = jdbc.query("SELECT id FROM object_upload_candidate WHERE state IN ('WRITING','READY','DISCARDED','DELETING') "
                + "AND next_check_at<=CURRENT_TIMESTAMP ORDER BY next_check_at,id LIMIT ?", (rs, row) -> rs.getLong(1), Math.max(1, batchSize));
        for (Long id : ids) {
            try { collect(id); }
            catch (RuntimeException e) { log.warn("候选回收失败，将重试: id={}", id, e); }
        }
    }
    private void collect(Long id) {
        Candidate candidate = independent.execute(tx -> {
            // next_check_at 同时限流多个实例；已采用状态永不进入 GC。
            int claimed = jdbc.update("UPDATE object_upload_candidate SET state='DELETING',updated_at=CURRENT_TIMESTAMP,next_check_at=? "
                    + "WHERE id=? AND state IN ('WRITING','READY','DISCARDED','DELETING') AND next_check_at<=CURRENT_TIMESTAMP", later(retryIntervalMs), id);
            if (claimed != 1) return null;
            return jdbc.queryForObject("SELECT id,tenant_id,md5,storage_path,write_finished FROM object_upload_candidate WHERE id=?",
                    (rs, row) -> new Candidate(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getBoolean(5)), id);
        });
        if (candidate == null || !isManagedPath(candidate.path()) || protectedByReference(candidate)) return;
        outsideTransaction();
        storage.getObject().deleteObject(candidate.path());
        // 必须使用 DELETE 之前的结束快照。未知 PUT 即使本次返回 404 也保留墓碑，防止迟到 PUT 永久遗留。
        if (candidate.finished()) independent.executeWithoutResult(tx -> jdbc.update("UPDATE object_upload_candidate SET state='DELETED',updated_at=CURRENT_TIMESTAMP "
                + "WHERE id=? AND state='DELETING' AND write_finished=1", candidate.id()));
    }
    private boolean protectedByReference(Candidate c) {
        // 直接 JDBC 且每项显式 tenant_id，后台扫描不依赖请求上下文。回收站节点和全部历史版本均保护。
        return count("SELECT COUNT(*) FROM file_object WHERE tenant_id=? AND storage_path=? AND status=0 AND deleted=0", c.tenantId(), c.path()) > 0
                || count("SELECT COUNT(*) FROM file_node WHERE tenant_id=? AND storage_path=? AND deleted=0", c.tenantId(), c.path()) > 0
                || count("SELECT COUNT(*) FROM file_version WHERE tenant_id=? AND storage_path=?", c.tenantId(), c.path()) > 0
                || count("SELECT COUNT(*) FROM upload_session WHERE tenant_id=? AND file_md5=? AND status IN (0,1,4)", c.tenantId(), c.md5()) > 0;
    }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 1 : value; }
    @PreDestroy public void close() { heartbeat.shutdownNow(); }
}
