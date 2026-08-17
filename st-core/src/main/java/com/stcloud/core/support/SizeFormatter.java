package com.stcloud.core.support;

/**
 * 文件大小人性化格式化工具。
 *
 * <p>将字节数转换为易读字符串：B / KB / MB / GB / TB，1024 进制，1 位小数。
 * 用于文件列表、上传下载进度等展示场景。</p>
 */
public final class SizeFormatter {

    /** 单位数组，索引即换算层级：0=B，1=KB，2=MB，3=GB，4=TB */
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    /** 1024 进制换算基数 */
    private static final long BASE = 1024L;

    /** 工具类：禁止实例化 */
    private SizeFormatter() {
    }

    /**
     * 将字节数格式化为人性化字符串。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>0 字节 → {@code "0 B"}</li>
     *   <li>小于 1024 字节 → 原样输出 {@code "n B"}（整数，不保留小数）</li>
     *   <li>大于等于 1024 字节 → 按 1024 进制逐级换算到 KB/MB/GB/TB，保留 1 位小数</li>
     *   <li>负数 → 非法输入，抛出 {@link IllegalArgumentException}</li>
     * </ul>
     *
     * @param bytes 字节数（非负）
     * @return 人性化大小字符串，如 {@code "0 B"}、{@code "512 B"}、{@code "1.0 KB"}、{@code "1.5 MB"}
     * @throws IllegalArgumentException 当 bytes 为负数时
     */
    public static String format(long bytes) {
        // 负数属于非法输入，直接拒绝，避免显示负大小误导用户
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes 不能为负数: " + bytes);
        }
        // 小于 1024 字节时按字节展示，保持整数可读性
        if (bytes < BASE) {
            return bytes + " B";
        }
        // 用 double 逐级除以 1024，直到落入可展示的单位层级（最大到 TB）
        double value = bytes;
        int unitIndex = 0;
        while (value >= BASE && unitIndex < UNITS.length - 1) {
            value /= BASE;
            unitIndex++;
        }
        // 保留 1 位小数，与展示约定一致
        return String.format("%.1f %s", value, UNITS[unitIndex]);
    }
}
