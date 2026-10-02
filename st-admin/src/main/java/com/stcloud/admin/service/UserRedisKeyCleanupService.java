package com.stcloud.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 停用后只按 key 名称清理用户独占状态，不读取 value 或共享集合成员。 */
@Service
@RequiredArgsConstructor
public class UserRedisKeyCleanupService {
    private static final int BATCH_SIZE = 128;
    private final StringRedisTemplate redis;

    /** refresh 单键由调用方先撤销；这里只补充活跃标记和用户权限缓存。 */
    public void clearUserOwnedKeys(Long userId) {
        if (userId == null || userId <= 0) return;
        // 用户 ID 必须占据完整固定段，不能用 *userId* 误删其他用户或同名节点的 key。
        scanAndDelete("team:active:*:" + userId,
                Pattern.compile("team:active:[0-9]+:" + userId));
        // 此格式来自 FolderPermissionService.cacheKey；其他缓存命名空间不能据此推断归属。
        scanAndDelete("stcloud:cache:*:*:" + userId + ":*",
                Pattern.compile("stcloud:cache:[0-9]+:[0-9]+:" + userId + ":[^:]*"));
    }

    private void scanAndDelete(String match, Pattern ownedKey) {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(match).count(BATCH_SIZE).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // SCAN 的 glob 只作筛选；删除前再完整校验格式，未知 key 保留。
                if (key == null || !ownedKey.matcher(key).matches()) continue;
                batch.add(key);
                if (batch.size() == BATCH_SIZE) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) redis.delete(batch);
        }
    }
}
