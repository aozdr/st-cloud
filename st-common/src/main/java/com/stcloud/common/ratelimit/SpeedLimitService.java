package com.stcloud.common.ratelimit;

import com.stcloud.common.context.UserContext;
import com.stcloud.common.entity.SysRateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 限速解析服务 - 解析当前用户生效的上传/下载速度上限(KB/s)
 * 规则合并:用户规则 + 所属角色规则,各方向取最严格(最小且>0的值),全为0则不限速
 */
@Component
@RequiredArgsConstructor
public class SpeedLimitService {

    private final SpeedLimitCache cache;

    /**
     * 解析当前登录用户生效的限速
     */
    public SpeedLimitResult resolve() {
        UserContext.CurrentUser user = UserContext.getCurrentUser();
        if (user == null || user.getUserId() == null) {
            return SpeedLimitResult.unlimited();
        }
        List<SysRateLimit> rules = new ArrayList<>();
        SysRateLimit userRule = cache.getUserRule(user.getUserId());
        if (userRule != null) {
            rules.add(userRule);
        }
        List<SysRateLimit> roleRules = cache.getRoleRules(user.getRoles());
        if (roleRules != null) {
            rules.addAll(roleRules);
        }
        int upload = mostRestrictive(rules, SysRateLimit::getUploadSpeedLimit);
        int download = mostRestrictive(rules, SysRateLimit::getDownloadSpeedLimit);
        return new SpeedLimitResult(upload, download);
    }

    /**
     * 计算服务端限速与客户端自设限速的生效值（KB/s，0=不限速）。
     * 客户端只能把自己限得更慢：clientRate>0 且 < serverRate 时取 clientRate，否则取 serverRate。
     */
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
                .filter(v -> v > 0)
                .min()
                .orElse(0);
    }
}