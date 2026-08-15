package com.stcloud.core.editor;

import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 编辑标记与保存锁服务（Redis 优先，memory 兜底）。
 * <ul>
 *   <li>编辑标记：editor:active:{nodeId} Redis Set（成员=用户标识），打开编辑器加入、关闭回调移除；滑动 TTL</li>
 *   <li>保存锁：editor:save-lock:{nodeId} SETNX + 短 TTL，串行化同文件并发保存回调</li>
 *   <li>幂等键：editor:save-dedup:{hash}，防止 OnlyOffice 重复回调重复落盘</li>
 * </ul>
 * 后端选择：stcloud.onlyoffice.lock-backend=redis（默认，多实例一致）/ memory（测试与单实例）。
 */
@Slf4j
@Service
public class EditorLockService {

    private static final String ACTIVE_PREFIX = "editor:active:";
    private static final String SAVE_LOCK_PREFIX = "editor:save-lock:";
    private static final String SAVE_DEDUP_PREFIX = "editor:save-dedup:";

    /** 编辑标记滑动 TTL：2 小时（每次打开/保存刷新），关闭回调主动移除 */
    private static final long ACTIVE_TTL_SECONDS = 7200L;
    /** 保存锁 TTL：10 秒 */
    private static final long SAVE_LOCK_TTL_SECONDS = 10L;

    private final StringRedisTemplate stringRedisTemplate;
    private final String lockBackend;

    // ==================== memory 兜底存储（测试/单实例） ====================
    /** 编辑标记：nodeId -> (用户标识 -> 过期时间戳) */
    private final Map<String, Map<String, Long>> memoryActive = new ConcurrentHashMap<>();
    /** 保存锁：key -> 过期时间戳 */
    private final Map<String, Long> memorySaveLock = new ConcurrentHashMap<>();
    /** 幂等键：key -> 过期时间戳 */
    private final Map<String, Long> memoryDedup = new ConcurrentHashMap<>();

    public EditorLockService(StringRedisTemplate stringRedisTemplate,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${stcloud.onlyoffice.lock-backend:redis}") String lockBackend) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockBackend = lockBackend;
    }

    private boolean redis() {
        return "redis".equalsIgnoreCase(lockBackend);
    }

    // ==================== 编辑标记 ====================

    /** 标记用户正在编辑（幂等；刷新滑动 TTL） */
    public void markEditing(Long nodeId, String editorUserId) {
        if (nodeId == null || editorUserId == null || editorUserId.isBlank()) {
            return;
        }
        String key = ACTIVE_PREFIX + nodeId;
        if (redis()) {
            try {
                stringRedisTemplate.opsForSet().add(key, editorUserId);
                stringRedisTemplate.expire(key, ACTIVE_TTL_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Redis 异常不阻断编辑打开；标记缺失时由保存锁与关闭回调兜底
                log.warn("Redis 编辑标记失败（降级为不标记）: nodeId={}, err={}", nodeId, e.getMessage());
            }
        } else {
            memoryActive.compute(key, (k, map) -> {
                Map<String, Long> m = map == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(map);
                m.put(editorUserId, System.currentTimeMillis() + ACTIVE_TTL_SECONDS * 1000);
                return m;
            });
        }
    }

    /** 文件是否处于编辑中（任意用户标记存在） */
    public boolean isEditing(Long nodeId) {
        if (nodeId == null) {
            return false;
        }
        String key = ACTIVE_PREFIX + nodeId;
        if (redis()) {
            try {
                Long count = stringRedisTemplate.opsForSet().size(key);
                return count != null && count > 0;
            } catch (Exception e) {
                log.warn("Redis 编辑标记查询失败，视为未编辑: nodeId={}, err={}", nodeId, e.getMessage());
                return false;
            }
        }
        Map<String, Long> members = memoryActive.get(key);
        if (members == null || members.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 惰性删除过期成员（滑动 TTL 到期自动失效）
        members.entrySet().removeIf(entry -> entry.getValue() <= now);
        return !members.isEmpty();
    }

    /** 移除用户的编辑标记（关闭回调） */
    public void removeEditingUser(Long nodeId, String editorUserId) {
        if (nodeId == null || editorUserId == null) {
            return;
        }
        String key = ACTIVE_PREFIX + nodeId;
        if (redis()) {
            try {
                stringRedisTemplate.opsForSet().remove(key, editorUserId);
            } catch (Exception e) {
                log.warn("Redis 编辑标记移除失败: nodeId={}, err={}", nodeId, e.getMessage());
            }
        } else {
            Map<String, Long> members = memoryActive.get(key);
            if (members != null) {
                members.remove(editorUserId);
            }
        }
    }

    /** 任一节点处于编辑中则抛出 FILE_EDITING（删除/移动/重命名/覆盖上传/版本恢复前调用） */
    public void assertNotEditing(Collection<Long> nodeIds) {
        if (nodeIds == null) {
            return;
        }
        for (Long nodeId : nodeIds) {
            if (isEditing(nodeId)) {
                throw new BusinessException(ResultCode.FILE_EDITING);
            }
        }
    }

    // ==================== 保存锁 ====================

    /** 尝试获取保存锁（SETNX + TTL）；成功返回 true */
    public boolean tryAcquireSaveLock(Long nodeId) {
        if (nodeId == null) {
            return false;
        }
        String key = SAVE_LOCK_PREFIX + nodeId;
        if (redis()) {
            try {
                Boolean ok = stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, String.valueOf(System.nanoTime()), SAVE_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                return Boolean.TRUE.equals(ok);
            } catch (Exception e) {
                log.warn("Redis 保存锁获取失败（视为可继续）: nodeId={}, err={}", nodeId, e.getMessage());
                return true;
            }
        }
        long now = System.currentTimeMillis();
        synchronized (memorySaveLock) {
            Long deadline = memorySaveLock.get(key);
            if (deadline != null && deadline > now) {
                return false;
            }
            memorySaveLock.put(key, now + SAVE_LOCK_TTL_SECONDS * 1000);
            return true;
        }
    }

    /** 释放保存锁 */
    public void releaseSaveLock(Long nodeId) {
        if (nodeId == null) {
            return;
        }
        String key = SAVE_LOCK_PREFIX + nodeId;
        if (redis()) {
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception e) {
                log.debug("Redis 保存锁释放失败: nodeId={}, err={}", nodeId, e.getMessage());
            }
        } else {
            memorySaveLock.remove(key);
        }
    }

    // ==================== 保存幂等 ====================

    /** 是否已处理过该保存回调（幂等键命中） */
    public boolean isSaveDeduped(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return false;
        }
        String key = SAVE_DEDUP_PREFIX + dedupKey;
        if (redis()) {
            try {
                return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
            } catch (Exception e) {
                log.warn("Redis 幂等键查询失败（视为未处理）: key={}, err={}", dedupKey, e.getMessage());
                return false;
            }
        }
        synchronized (memoryDedup) {
            Long deadline = memoryDedup.get(key);
            return deadline != null && deadline > System.currentTimeMillis();
        }
    }

    /** 登记已成功处理的保存回调（幂等键，TTL 后允许同内容再次保存） */
    public void markSaveDedup(String dedupKey, long ttlSeconds) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return;
        }
        String key = SAVE_DEDUP_PREFIX + dedupKey;
        if (redis()) {
            try {
                stringRedisTemplate.opsForValue().set(key, "1", ttlSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Redis 幂等键登记失败: key={}, err={}", dedupKey, e.getMessage());
            }
        } else {
            synchronized (memoryDedup) {
                memoryDedup.put(key, System.currentTimeMillis() + ttlSeconds * 1000);
            }
        }
    }
}
