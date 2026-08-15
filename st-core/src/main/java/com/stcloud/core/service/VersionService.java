package com.stcloud.core.service;

import com.stcloud.core.dto.FileVersionVO;
import com.stcloud.core.entity.FileNode;
import com.stcloud.core.entity.FileVersion;

import java.util.List;

/**
 * 文件版本管理服务
 */
public interface VersionService {

    /**
     * 列出文件的历史版本
     */
    List<FileVersionVO> listVersions(Long fileNodeId);

    /**
     * 恢复文件到指定历史版本（生成新版本，不覆盖目标版本）
     */
    FileNode restoreVersion(Long fileNodeId, Long versionId);

    /**
     * 将文件节点当前内容快照为一个新的历史版本
     */
    void snapshotCurrentVersion(FileNode node);

    /**
     * 将文件节点当前内容快照为一个新的历史版本（指定来源：0-上传覆盖 / 1-编辑器保存）
     */
    void snapshotCurrentVersion(FileNode node, Integer source);

    /**
     * 裁剪编辑器保存版本：仅 source=1 参与，超过 limit 时删除最旧记录（D1 决策：上传覆盖版本不受影响）
     */
    void pruneEditorVersions(Long fileNodeId, int limit);

    /**
     * 获取文件最近的历史版本（用于替换上传中止时回退）
     */
    FileVersion getLatestVersion(Long fileNodeId);
}
