package com.stcloud.core.service;

/**
 * 解压进度回调：由调用方（如控制器）传入，用于向前端报告「已创建文件数 / 压缩包文件总数」。
 */
public interface ArchiveProgressReporter {

    /** 预扫描完成：total = 压缩包内待解压的文件条目总数 */
    default void begin(int total) {
    }

    /** 每成功创建一个解压文件后回调 */
    default void onFileExtracted() {
    }
}
