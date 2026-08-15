package com.stcloud.core.service;

import com.stcloud.core.dto.FileNodeVO;

/**
 * 新建空白文件服务（txt/docx/xlsx/pptx）
 */
public interface NewFileService {

    /**
     * 新建空白文件并落盘，创建即完成（status=NORMAL、uploadStatus=COMPLETED）。
     *
     * @param type      文件类型（白名单：txt/docx/xlsx/pptx）
     * @param parentId  父文件夹ID（0=根目录）
     * @param spaceId   团队空间ID（个人新建传 null）
     * @param fileName  文件名（可选，空则用默认名；无后缀自动补对应类型后缀）
     * @return 新建的文件节点 VO
     */
    FileNodeVO createBlankFile(String type, Long parentId, Long spaceId, String fileName);
}
