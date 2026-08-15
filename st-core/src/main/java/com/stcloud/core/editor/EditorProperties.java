package com.stcloud.core.editor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * OnlyOffice 在线编辑配置（stcloud.onlyoffice.*）。
 * 签名密钥仅经环境变量 STCLOUD_ONLYOFFICE_SECRET 注入，不入源码。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "stcloud.onlyoffice")
public class EditorProperties {

    /** 前端 iframe 加载地址（OnlyOffice Document Server，浏览器可达） */
    private String url = "http://localhost:8081";

    /** 后端对外可达地址（docker 内用 host.docker.internal 访问宿主；生产改为公网可达地址） */
    private String publicBaseUrl = "http://host.docker.internal:8080";

    /** OnlyOffice JWT 签名密钥（HS256，至少 32 字节）；留空则拒绝签发/校验回调 */
    private String jwtSecret = "";

    /** 回调下载内容大小上限（字节），默认 200MB，防超大文件投毒 */
    private long maxSaveSize = 200L * 1024 * 1024;

    /** 回调 url 允许下载的主机白名单（SSRF 防护）；留空时自动取 url 配置的主机 + localhost */
    private List<String> allowedCallbackHosts = new ArrayList<>();

    /** 编辑器保存产生的版本上限（D1：仅 source=1 参与裁剪） */
    private int editorVersionLimit = 20;

    /** 编辑标记/保存锁后端：redis-分布式（默认）/ memory-单机内存（测试与单实例降级） */
    private String lockBackend = "redis";
}
