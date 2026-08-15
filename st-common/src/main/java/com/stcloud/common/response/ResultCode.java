package com.stcloud.common.response;

import lombok.Getter;

@Getter
public enum ResultCode {

    SUCCESS(200, "成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    CONFLICT(409, "资源冲突"),

    // 业务错误码 1xxx
    BUSINESS_ERROR(1000, "业务处理失败"),
    USER_ALREADY_EXISTS(1001, "用户已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_INCORRECT(1003, "密码错误"),
    TOKEN_EXPIRED(1004, "Token已过期"),
    TOKEN_INVALID(1005, "Token无效"),
    // 1006 已废弃：原两步验证相关错误码随 2FA 下线移除（见 09_remove_two_factor.sql）
    ROLE_NOT_FOUND(1007, "角色不存在"),
    PERMISSION_DENIED(1008, "无操作权限"),

    // 文件相关 2xxx
    FILE_NOT_FOUND(2001, "文件不存在"),
    FILE_ALREADY_EXISTS(2002, "文件已存在"),
    FILE_UPLOAD_FAILED(2003, "文件上传失败"),
    FILE_TOO_LARGE(2004, "文件超过大小限制"),
    FILE_TYPE_NOT_ALLOWED(2005, "文件类型不允许"),
    CHUNK_NOT_FOUND(2006, "分片不存在"),
    STORAGE_QUOTA_EXCEEDED(2007, "存储空间不足"),
    FILE_IN_RECYCLE(2008, "文件在回收站中"),
    CLOUD_CAPACITY_EXCEEDED(2009, "云盘总容量已达上限"),
    FILE_EDITING(2010, "文件正在编辑中，请关闭编辑器后重试"),
    EDITOR_SERVICE_ERROR(2011, "文档编辑服务暂不可用"),

    // 分享相关 3xxx
    SHARE_NOT_FOUND(3001, "分享不存在"),
    SHARE_EXPIRED(3002, "分享已过期"),
    SHARE_PASSWORD_ERROR(3003, "提取码错误"),
    SHARE_ACCESS_DENIED(3004, "无访问权限"),

    // 团队相关 4xxx
    TEAM_NOT_FOUND(4001, "团队空间不存在"),
    TEAM_MEMBER_EXISTS(4002, "成员已在团队中"),
    TEAM_MEMBER_NOT_FOUND(4003, "成员不存在"),
    TEAM_PERMISSION_DENIED(4004, "无团队操作权限"),
    TEAM_INVITE_NOT_FOUND(4005, "邀请链接不存在"),
    TEAM_INVITE_EXPIRED(4006, "邀请链接已过期"),
    TEAM_LAST_ADMIN(4007, "空间至少保留一名管理员"),
    TEAM_TRANSFER_TARGET_INVALID(4008, "移交目标必须是空间管理员"),

    // 系统错误 5xxx
    INTERNAL_ERROR(5000, "系统内部错误"),
    SERVICE_UNAVAILABLE(5001, "服务不可用"),
    STORAGE_SERVICE_ERROR(5002, "存储服务异常");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
