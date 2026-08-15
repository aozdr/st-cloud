package com.stcloud.core.convert;

import com.stcloud.core.dto.FileNodeVO;

/**
 * 文件格式转换服务（Word<->PDF，基于 OnlyOffice 转换引擎）
 */
public interface FileConvertService {

    /**
     * 将源文件转换为目标格式并落到源文件所在目录。
     *
     * @param nodeId   源文件节点 ID
     * @param fileName 目标文件名（可编辑；空则用默认名「原文件名-转换.目标后缀」）
     * @return 转换后新文件节点
     */
    FileNodeVO convert(Long nodeId, String fileName);
}
