# 重复文件检测页面改造 - 设计文档

## 需求

1. 重复检测从弹窗改为独立页面
2. 展开后展示同 MD5 的文件列表（文件名、路径、创建时间）
3. 增加单组清理功能：保留创建时间最早的文件，其余移入回收站
4. 有历史版本的文件不删除（跳过，保留）

## 后端设计

### 新增接口

POST /api/file/duplicates/cleanup?md5={md5}
- 查询同 MD5 的文件列表（按 created_at ASC）
- 逐个检查是否有历史版本（file_version 表 file_node_id 关联）
- 有历史版本的文件跳过，不删除
- 保留第一个可删除文件（created_at 最早的），其余移入回收站
- 返回：{ kept: FileNodeVO, deletedCount: int, skippedCount: int }

### Mapper 变更

- findByMd5：改为按 created_at ASC 排序（保留最早的）
- FileNodeMapper 新增 hasVersions 方法：SELECT COUNT(*) FROM file_version WHERE file_node_id = #{nodeId}

## 前端设计

- 删除 DuplicateFilesDialog.tsx
- 重写 DuplicateFilesPage.tsx 为完整页面
- 展开组 -> 加载该 MD5 的文件列表 -> 每项显示文件名/路径/创建时间/是否有历史版本标记
- 每组展开后有"清理冗余副本"按钮，调用清理接口后刷新

## 业务规则

- 有历史版本的文件不可删除（清理时跳过）
- 如果一组中所有文件都有历史版本，则不可清理
- 清理操作将文件移入回收站（非永久删除），用户可恢复
