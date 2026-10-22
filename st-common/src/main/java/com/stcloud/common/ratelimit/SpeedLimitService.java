package com.stcloud.common.ratelimit;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.entity.SysRateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 限速解析服务 - 解析当前用户生效的上传/下载速度上限(KB/s)。
 * 规则合并：用户规则 + 所属角色规则，各方向取最严格（最小且 >0 的值），全为 0 则不限速。
 */
@Component
@RequiredArgsConstructor
public class SpeedLimitService {

    private final SpeedLimitCache cache;

    public SpeedLimitResult resolve() {
        UserContext.CurrentUser user = UserContext.getCurrentUser();
        if (user == null || user.getUserId() == null) {
            return SpeedLimitResult.unlimited();
        }
        // 规则与版本必须来自同一份缓存快照，否则 TTL 边界会产出「新 ver + 旧 rate」（R-2）
        SpeedLimitCache.LimitSnapshot snapshot = cache.snapshot(user.getUserId(), user.getRoles());
        List<SysRateLimit> rules = new ArrayList<>();
        if (snapshot.userRule() != null) {
            rules.add(snapshot.userRule());
        }
        rules.addAll(snapshot.roleRules());
        int upload = mostRestrictive(rules, SysRateLimit::getUploadSpeedLimit);
        int download = mostRestrictive(rules, SysRateLimit::getDownloadSpeedLimit);
        return new SpeedLimitResult(upload, download, snapshot.version());
    }

    public static int capRate(int serverRateKb, Integer clientRateKb) {
        if (clientRateKb == null || clientRateKb <= 0) {
            return serverRateKb;
        }
        if (serverRateKb <= 0) {
            return clientRateKb;
        }
        return Math.min(serverRateKb, clientRateKb);
    }

    private int mostRestrictive(List<SysRateLimit> rules, java.util.function.ToIntFunction<SysRateLimit> getter) {
        return rules.stream()
                .mapToInt(getter)
                .filter(value -> value > 0)
                .min()
                .orElse(0);
    }
}
