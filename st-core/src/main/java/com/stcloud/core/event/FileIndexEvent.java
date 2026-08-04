package com.stcloud.core.event;

import com.stcloud.core.entity.FileNode;
import org.springframework.context.ApplicationEvent;

/**
 * 文件索引事件
 * <p>
 * 在文件上传完成后发布（INDEX），或在文件删除时发布（DELETE），
 * 由 st-search 模块监听并异步执行 ES 索引操作。
 */
public class FileIndexEvent extends ApplicationEvent {

    private final FileNode fileNode;
    private final ActionType actionType;

    public FileIndexEvent(Object source, FileNode fileNode, ActionType actionType) {
        super(source);
        this.fileNode = fileNode;
        this.actionType = actionType;
    }

    public FileNode getFileNode() {
        return fileNode;
    }

    public ActionType getActionType() {
        return actionType;
    }

    /**
     * 事件操作类型
     */
    public enum ActionType {
        /** 索引文件（上传/更新后） */
        INDEX,
        /** 删除索引（文件删除时） */
        DELETE,
        /** 更新元数据（移动/重命名时，仅更新 path/fileName，不重新解析内容） */
        UPDATE_META
    }
}
