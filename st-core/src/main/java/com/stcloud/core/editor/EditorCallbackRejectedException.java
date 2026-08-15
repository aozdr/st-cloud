package com.stcloud.core.editor;

import lombok.Getter;

/**
 * 回调拒绝异常：携带 HTTP 状态码（401/403/400/404/500 等），
 * EditorController 据此返回 OnlyOffice 可识别的失败状态（验签失败 401/403，业务失败 500 重试）。
 */
@Getter
public class EditorCallbackRejectedException extends RuntimeException {

    private final int httpStatus;

    public EditorCallbackRejectedException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }
}
