package com.stcloud.core.util;

/**
 * 文件名清洗工具。
 *
 * <p>用于上传场景的文件名校验与规整：去除 Windows/常见文件系统不允许的非法字符与控制字符，
 * 修剪首尾空白，并在清洗后保留原文件扩展名。核心逻辑与云盘文件处理规则相关，
 * 任何调整均需同步考虑跨平台（Windows / Linux / macOS）的文件名兼容性。
 */
public final class FileNameSanitizer {

    /** 文件名中不允许出现的非法字符（Windows 保留字符）。 */
    private static final char[] ILLEGAL_CHARS = {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    /** Windows 保留设备名（大小写不敏感），清洗后禁止作为文件名使用。 */
    private static final String[] RESERVED_NAMES = {
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    private FileNameSanitizer() {
        // 工具类，禁止实例化
    }

    /**
     * 清洗文件名：去除非法字符与控制字符、修剪首尾空白、保留扩展名。
     *
     * <p>规则说明：
     * <ul>
     *   <li>非法字符集合：\ / : * ? " &lt; &gt; |（Windows 不允许出现在文件名中）；</li>
     *   <li>控制字符（U+0000~U+001F、U+007F）一并移除；</li>
     *   <li>首尾空白（空格、制表符等）在清洗后统一修剪；</li>
     *   <li>扩展名按最后一个“.”拆分，清洗仅作用于主文件名，清洗后重新拼接扩展名；</li>
     *   <li>清洗结果为空、仅含“.”或命中 Windows 保留设备名时，返回 null 表示不可用。</li>
     * </ul>
     *
     * @param fileName 原始文件名，可为 null
     * @return 清洗后的可用文件名；不可用时返回 null
     */
    public static String sanitize(String fileName) {
        if (fileName == null) {
            // null 输入直接判为不可用
            return null;
        }

        // 先移除控制字符（U+0000~U+001F、U+007F），避免其影响后续拆分与判断
        StringBuilder controlCleaned = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char ch = fileName.charAt(i);
            if (!isControlChar(ch)) {
                controlCleaned.append(ch);
            }
        }
        String trimmed = controlCleaned.toString().trim();
        if (trimmed.isEmpty()) {
            // 全为空白/控制字符时无可用内容
            return null;
        }

        // 按最后一个“.”拆分主文件名与扩展名，扩展名整体保留
        int lastDotIndex = trimmed.lastIndexOf('.');
        String baseName = lastDotIndex > 0 ? trimmed.substring(0, lastDotIndex) : trimmed;
        String extension = lastDotIndex > 0 ? trimmed.substring(lastDotIndex) : "";

        // 清洗主文件名中的非法字符
        StringBuilder cleanedBase = new StringBuilder(baseName.length());
        for (int i = 0; i < baseName.length(); i++) {
            char ch = baseName.charAt(i);
            if (!isIllegalChar(ch)) {
                cleanedBase.append(ch);
            }
        }

        // 清洗后的主文件名再次修剪首尾空白（如 "a b. " -> 主名 "a b " -> 修剪后 "a b"）
        String finalBase = cleanedBase.toString().trim();
        if (finalBase.isEmpty() || finalBase.equals(".")) {
            // 主文件名清洗后为空或仅剩“.”，视为不可用
            return null;
        }

        String result = finalBase + extension;
        if (isReservedName(finalBase)) {
            // Windows 保留设备名不可作为文件名
            return null;
        }
        return result;
    }

    /**
     * 判断字符是否为控制字符（U+0000~U+001F 或 U+007F）。
     */
    private static boolean isControlChar(char ch) {
        return ch <= 0x1F || ch == 0x7F;
    }

    /**
     * 判断字符是否属于文件名非法字符集合。
     */
    private static boolean isIllegalChar(char ch) {
        for (char illegal : ILLEGAL_CHARS) {
            if (ch == illegal) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断主文件名是否命中 Windows 保留设备名（大小写不敏感）。
     */
    private static boolean isReservedName(String name) {
        String upper = name.toUpperCase();
        for (String reserved : RESERVED_NAMES) {
            if (upper.equals(reserved)) {
                return true;
            }
        }
        return false;
    }
}
