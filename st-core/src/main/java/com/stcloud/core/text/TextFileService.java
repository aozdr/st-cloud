package com.stcloud.core.text;

/**
 * 文本文件内容保存服务（txt/md/代码等，方案 B：应用内轻量文本编辑器）
 */
public interface TextFileService {

    /**
     * 覆盖写入文件内容（权限由调用方校验；个人=owner，团队=edit 权限点）。
     * 落库链路与 OnlyOffice 保存一致：MD5 去重、配额差值、索引/同步事件。
     *
     * @param nodeId  文件节点 ID
     * @param content UTF-8 字节内容（非空，上限 2MB）
     */
    void overwriteContent(Long nodeId, byte[] content);
}
