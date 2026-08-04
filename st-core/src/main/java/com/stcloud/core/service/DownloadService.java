package com.stcloud.core.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.OutputStream;
import java.util.List;

/**
 * 文件下载服务
 */
public interface DownloadService {

    /**
     * 生成预签名下载 URL（有效期 1 小时）
     */
    String generateDownloadUrl(Long nodeId);

    /**
     * 流式下载文件到 HTTP 响应（支持 Range 断点续传、服务端限速）
     */
    void streamFile(Long nodeId, HttpServletRequest request, HttpServletResponse response);

    /**
     * 将多个文件/文件夹打包为 ZIP 下载
     */
    void downloadAsZip(List<Long> nodeIds, OutputStream outputStream);
}