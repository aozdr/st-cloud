package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import org.springframework.context.ApplicationEvent;

/**
 * 文件同步变更事件
 * <p>
 * 在文件创建/更新/移动/重命名/删除时发布，由 st-sync 模块监听并写入变更日志（sync_change_log），
 * 驱动增量同步的游标推进。与 FileIndexEvent 并行发布，互不影响。
 * <p>
 * 携带 oldPath 字段供 MOVE / RENAME 场景记录变更前路径，客户端据此执行本地移动/重命名。
 */
public class SyncChangeEvent extends ApplicationEvent {

    private final FileNode fileNode;
    private final ChangeType changeType;
    private final String oldPath;

    public SyncChangeEvent(Object source, FileNode fileNode, ChangeType changeType) {
        this(source, fileNode, changeType, null);
    }

    public SyncChangeEvent(Object source, FileNode fileNode, ChangeType changeType, String oldPath) {
        super(source);
        this.fileNode = fileNode;
        this.changeType = changeType;
        this.oldPath = oldPath;
    }

    public FileNode getFileNode() {
        return fileNode;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getOldPath() {
        return oldPath;
    }

    /**
     * 同步变更类型
     */
    public enum ChangeType {
        /** 新建文件/文件夹（含秒传、复制、从回收站恢复） */
        CREATE,
        /** 文件内容更新（分片合并、替换上传产生新版本） */
        UPDATE,
        /** 移动文件/文件夹（parent 变化） */
        MOVE,
        /** 重命名文件/文件夹（name 变化，parent 不变） */
        RENAME,
        /** 删除文件/文件夹（移入回收站） */
        DELETE
    }
}
