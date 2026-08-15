package com.stcloud.core.editor;

import com.stcloud.core.editor.dto.OnlyOfficeCallbackRequest;

/**
 * OnlyOffice 保存回调处理服务：验签 / 落盘 / 版本 / 事件 / 配额 / 幂等 / 编辑标记。
 */
public interface EditorCallbackService {

    /**
     * 处理回调；签名或业务校验失败抛 {@link EditorCallbackRejectedException}/{@link com.stcloud.common.exception.BusinessException}，
     * 由 Controller 映射为 OnlyOffice 可识别的 HTTP 状态。
     */
    void handleCallback(Long nodeId, OnlyOfficeCallbackRequest request);
}
