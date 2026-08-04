package com.stcloud.common.utils;

/**
 * 文件大小格式化工具：将字节数转为人类可读的字符串，如 1.5 GB。
 */
public final class FileSizeUtil {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};
    private static final long KB = 1024L;

    private FileSizeUtil() {
    }

    /**
     * 格式化字节数为人类可读字符串，自动选择合适单位。
     *
     * @param bytes 字节数，null 或负数视为 0
     * @return 如 "1.5 GB"、"500 B"
     */
    public static String format(Long bytes) {
        if (bytes == null || bytes < 0) {
            return "0 B";
        }
        if (bytes < KB) {
            return bytes + " B";
        }
        double size = bytes.doubleValue();
        int unitIndex = 0;
        while (size >= KB && unitIndex < UNITS.length - 1) {
            size /= KB;
            unitIndex++;
        }
        return String.format("%.2f %s", size, UNITS[unitIndex]);
    }
}
