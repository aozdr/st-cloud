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

    /**
     * 以已有字节创建“已完成”文件（格式转换等场景复用）：
     * 归属/权限校验 → 重名自动序号 → 配额/容量预检 → 去重落盘 → 建节点 → 事件 → 扣配额。
     *
     * @param fileName 文件名（空则用默认名；无后缀自动补对应类型后缀）
     * @param suffix   目标文件后缀（如 pdf/docx）
     * @param content  文件字节内容
     * @param parentId 父文件夹ID（0=根目录）
     * @param spaceId  团队空间ID（个人路径传 null）
     * @return 创建的文件节点 VO
     */
    FileNodeVO createCompletedFile(String fileName, String suffix, byte[] content,
                                   Long parentId, Long spaceId);
}
