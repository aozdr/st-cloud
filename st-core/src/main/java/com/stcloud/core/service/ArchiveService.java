package com.stcloud.core.service;

import java.util.List;
import java.util.Map;

/**
 * 在线解压服务：支持 ZIP 格式压缩包的浏览与解压
 */
public interface ArchiveService {

    /**
     * 浏览压缩包内容列表（仅 ZIP 格式）
     *
     * @param nodeId 压缩包文件节点ID
     * @return 条目列表，每项含 name、size、isDirectory、path
     */
    List<Map<String, Object>> listArchiveContents(Long nodeId);

    /**
     * 解压压缩包到指定目录
     *
     * @param nodeId         压缩包文件节点ID
     * @param targetFolderId 目标文件夹ID（0=根目录）
     * @return 解压出的文件数量
     */
    int extractArchive(Long nodeId, Long targetFolderId);

    /** 带进度回调的解压（控制器异步任务使用） */
    int extractArchive(Long nodeId, Long targetFolderId, ArchiveProgressReporter reporter);
}
