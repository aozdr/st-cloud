package com.stcloud.preview.service;

import com.stcloud.preview.dto.PreviewResultVO;

public interface PreviewService {

    /**
     * 获取文件预览
     */
    PreviewResultVO preview(Long nodeId);

    /**
     * 获取历史版本预览（不读取、不改动当前版本）
     */
    PreviewResultVO previewVersion(Long nodeId, Long versionId);

    /**
     * 获取缩略图URL
     */
    String getThumbnailUrl(Long nodeId, String size);

    /**
     * 获取视频播放URL
     */
    PreviewResultVO getVideoPreview(Long nodeId);
}
