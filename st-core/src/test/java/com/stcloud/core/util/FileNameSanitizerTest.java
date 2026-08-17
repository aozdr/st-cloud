package com.stcloud.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * FileNameSanitizer 单元测试。
 *
 * <p>覆盖：非法字符去除、控制字符去除、首尾空白修剪、扩展名保留、
 * 全非法输入返回 null、Windows 保留设备名拦截。
 */
class FileNameSanitizerTest {

    @Test
    void sanitize_removesIllegalCharacters() {
        // 去除 Windows 非法字符：\ / : * ? " < > |
        assertEquals("报告v1.txt", FileNameSanitizer.sanitize("报\\告/:*?\"<>|v1.txt"));
    }

    @Test
    void sanitize_removesControlCharactersAndTrims() {
        // 去除控制字符（\u0007、\u001F）并修剪首尾空白
        assertEquals("设计文档.pdf", FileNameSanitizer.sanitize("  \u0007设计\u001F文档.pdf  "));
    }

    @Test
    void sanitize_preservesExtension() {
        // 扩展名整体保留：仅清洗主文件名部分，扩展名不被破坏
        assertEquals("照片.2026.jpg", FileNameSanitizer.sanitize("照片*.:2026.jpg"));
    }

    @Test
    void sanitize_returnsNullWhenNothingRemains() {
        // 输入全为非法字符/空白时，清洗后无可用内容，返回 null
        assertNull(FileNameSanitizer.sanitize(":/\\*?"));
        assertNull(FileNameSanitizer.sanitize("   "));
        assertNull(FileNameSanitizer.sanitize(null));
    }

    @Test
    void sanitize_rejectsWindowsReservedNames() {
        // Windows 保留设备名（大小写不敏感）不可作为文件名
        assertNull(FileNameSanitizer.sanitize("CON"));
        assertNull(FileNameSanitizer.sanitize("com1.txt"));
        assertNull(FileNameSanitizer.sanitize("Lpt9"));
    }

    @Test
    void sanitize_keepsNormalNamesUntouched() {
        // 正常文件名应原样保留
        assertEquals("项目说明.md", FileNameSanitizer.sanitize("项目说明.md"));
        assertEquals("a.b.c", FileNameSanitizer.sanitize("a.b.c"));
    }
}
