package com.stcloud.core.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SizeFormatter 单元测试。
 * 覆盖：0 值、字节边界（1023/1024）、KB/MB/GB/TB 换算、1 位小数、负数拒绝。
 */
class SizeFormatterTest {

    @Test
    void format_zero_returnsZeroB() {
        assertEquals("0 B", SizeFormatter.format(0L));
    }

    @Test
    void format_below1024_keepsBytes() {
        assertEquals("512 B", SizeFormatter.format(512L));
        assertEquals("1023 B", SizeFormatter.format(1023L));
    }

    @Test
    void format_1024_returnsOneKB() {
        assertEquals("1.0 KB", SizeFormatter.format(1024L));
    }

    @Test
    void format_kbAndMb_convertsWithOneDecimal() {
        assertEquals("1.5 KB", SizeFormatter.format(1536L));
        assertEquals("1.0 MB", SizeFormatter.format(1024L * 1024L));
    }

    @Test
    void format_gbAndTb_converts() {
        assertEquals("5.0 GB", SizeFormatter.format(5L * 1024L * 1024L * 1024L));
        assertEquals("2.0 TB", SizeFormatter.format(2L * 1024L * 1024L * 1024L * 1024L));
    }

    @Test
    void format_negative_throws() {
        assertThrows(IllegalArgumentException.class, () -> SizeFormatter.format(-1L));
    }
}
