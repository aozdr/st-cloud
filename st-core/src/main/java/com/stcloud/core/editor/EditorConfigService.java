package com.stcloud.core.editor;

import com.stcloud.core.editor.dto.EditorConfigResponse;

/**
 * OnlyOffice 编辑器配置生成服务：构建 config + JWT 签名 + 编辑标记登记。
 */
public interface EditorConfigService {

    /** 生成配置（用户信息取 UserContext；个人/团队调用方先完成权限判定） */
    EditorConfigResponse generateConfig(Long nodeId, boolean canEdit, boolean canDownload, boolean canPrint);

    /** 生成配置（显式用户信息，分享访客等匿名场景使用） */
    EditorConfigResponse generateConfig(Long nodeId, boolean canEdit, boolean canDownload, boolean canPrint,
                                        Long userId, String username, String editorUserId);

    /**
     * 生成历史版本只读预览配置：document.url 指向该版本对象，强制 view + edit=false，
     * 且不下发 callbackUrl（历史版本不提供保存通道，避免用旧内容覆盖当前版本）。
     */
    EditorConfigResponse generateVersionConfig(Long nodeId, Long versionId);
}
