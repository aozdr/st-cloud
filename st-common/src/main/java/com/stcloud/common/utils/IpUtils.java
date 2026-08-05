package com.stcloud.common.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP 地址解析工具：兼容反向代理（X-Forwarded-For / X-Real-IP）。
 */
public final class IpUtils {

    private IpUtils() {
    }

    /**
     * 解析客户端真实 IP，依次取 X-Forwarded-For 首段、X-Real-IP、RemoteAddr。
     *
     * @param request HTTP 请求，为 null 时返回 null
     * @return 客户端 IP
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}