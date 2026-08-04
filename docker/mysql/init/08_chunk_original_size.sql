-- 替换上传按差值计费：file_chunk 记录原文件大小
USE stcloud;

ALTER TABLE file_chunk ADD COLUMN original_size BIGINT DEFAULT NULL COMMENT '替换上传时原文件大小(字节)，用于合并时按差值计费' AFTER storage_path;
