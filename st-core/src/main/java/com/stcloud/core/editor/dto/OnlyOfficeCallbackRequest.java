package com.stcloud.core.editor.dto;

import lombok.Data;

import java.util.List;

/**
 * OnlyOffice 保存/关闭回调请求体。
 * status=2 自动保存；6 关闭并保存；7 强制保存；url 为编辑后文档下载地址。
 */
@Data
public class OnlyOfficeCallbackRequest {

    /** 文档唯一标识（生成 config 时的 document.key） */
    private String key;

    /** 回调状态：2-已保存 6-已关闭并保存 7-强制保存 */
    private Integer status;

    /** 编辑后文档下载地址 */
    private String url;

    /** 参与编辑的用户 id 列表（关闭回调返回，用于移除编辑标记） */
    private List<String> users;

    /** OnlyOffice JWT 签名令牌（JWT_ENABLED=true 时必填） */
    private String token;

    /** 自定义数据（当前未使用） */
    private String userdata;

    /** 文件扩展名（docx/xlsx/pptx） */
    private String filetype;
}
