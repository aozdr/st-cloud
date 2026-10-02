SELECT id,username,status,tenant_id,security_version FROM sys_user WHERE id=2105297388782690305;
SELECT id,owner_id,parent_id,status,path,file_md5,object_id FROM file_node WHERE id IN (2105298513682440193,2105298513967652866,2105298514835873794) ORDER BY id;
SELECT o.id,o.storage_path,o.status,o.ref_count FROM file_object o JOIN file_node n ON n.object_id=o.id WHERE n.id=2105298514835873794;
SELECT j.id AS journal_id,j.change_type,j.file_node_id,j.path,j.old_path,j.event_log_id,e.status AS outbox_status,e.retry_count FROM sync_change_log j LEFT JOIN event_log e ON e.id=j.event_log_id WHERE j.user_id=2105297388782690305 ORDER BY j.id;
SELECT file_node_id,COUNT(*) AS preserved_version_count FROM file_version WHERE file_node_id=2105298514835873794 GROUP BY file_node_id;
