package com.stcloud.core.editor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 在线编辑配置响应：editorUrl=OnlyOffice Document Server 基础地址（浏览器可达），
 * 前端据此拼接 /web-apps/apps/api/documents/api.js；config=Document Server config（含 token）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditorConfigResponse {

    private String editorUrl;
    private Map<String, Object> config;
}
