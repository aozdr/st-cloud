package com.stcloud.core.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.core.dto.FileNodeVO;
import com.stcloud.core.dto.FileTreeNodeVO;
import com.stcloud.core.dto.StorageInfoVO;
import com.stcloud.core.entity.FileNode;

import java.util.List;

/**
 * 文件/目录管理服务
 */
public interface FileService {

    // ==================== 目录管理 ====================

    FileNodeVO createFolder(Long parentId, String folderName);

    IPage<FileNodeVO> listDirectory(Long parentId, int page, int size);

    List<FileNodeVO> searchFiles(String keyword);

    FileNodeVO rename(Long nodeId, String newName);

    void move(List<Long> nodeIds, Long targetParentId);

    void copy(List<Long> nodeIds, Long targetParentId);

    void deleteToRecycleBin(List<Long> nodeIds);

    // ==================== 文件信息查询 ====================

    FileNodeVO getNodeDetail(Long nodeId);

    List<FileTreeNodeVO> getFolderTree();

    StorageInfoVO getStorageInfo();

    // ==================== 跨服务辅助方法 ====================

    /**
     * 校验并获取父目录路径
     */
    String validateAndGetParentPath(Long parentId);

    /**
     * 解决同名冲突，返回不冲突的名称
     */
    String resolveNameConflict(Long parentId, String name);

    /**
     * 根据文件名推断 Content-Type
     */
    String guessContentType(String fileName);

    /**
     * 提取文件后缀（小写，不含点）
     */
    String extractSuffix(String fileName);

    /**
     * 增加指定 MD5 文件的引用计数
     */
    void incrementRefCount(String md5);

    /**
     * 根据 ID 和所有者获取文件节点（含权限校验）
     */
    FileNode getNodeByIdAndOwner(Long nodeId);

    /**
     * 将实体转为 VO
     */
    FileNodeVO toVO(FileNode node);

    // ==================== 团队空间文件操作（复用核心逻辑，按 spaceId 过滤） ====================

    /** 团队空间：列出目录（根目录 parentId 为 null 或 0） */
    IPage<FileNodeVO> listTeamFiles(Long spaceId, Long parentId, int page, int size);

    /** 团队空间：创建文件夹 */
    FileNodeVO createTeamFolder(Long spaceId, Long parentId, String folderName);

    /** 团队空间：重命名（校验 spaceId 归属） */
    FileNodeVO renameTeamFile(Long spaceId, Long nodeId, String newName);

    /** 团队空间：删除至回收站（校验 spaceId 归属） */
    void deleteTeamFiles(Long spaceId, List<Long> nodeIds);

    /** 团队空间：移动（校验 spaceId 归属） */
    void moveTeamFiles(Long spaceId, List<Long> nodeIds, Long targetParentId);

    /** 团队空间：复制（校验 spaceId 归属） */
    void copyTeamFiles(Long spaceId, List<Long> nodeIds, Long targetParentId);

    /** 团队空间：校验节点属于指定 spaceId */
    void validateTeamNode(Long spaceId, Long nodeId);

    /** 团队空间：获取文件夹树（按 spaceId 过滤） */
    List<FileTreeNodeVO> getTeamFolderTree(Long spaceId);
    /** 根据路径解析文件夹（个人，按 ownerId 过滤） */
    FileNodeVO resolveByPath(String path);

    /** 根据路径解析文件夹（团队空间，按 spaceId 过滤） */
    FileNodeVO resolveTeamByPath(Long spaceId, String path);
    /** 团队空间：根据 ID 获取节点（不校验 ownerId） */
    FileNodeVO getTeamNodeById(Long spaceId, Long nodeId);

    /** 校验节点可访问：自身及所有祖先必须处于正常态，否则抛 FORBIDDEN。 */
    void validateAccessible(Long nodeId);

    /** 递归收集指定节点的全部子孙（按 parentId，不依赖 path 前缀）。 */
    List<FileNode> collectDescendants(Long nodeId);
}
