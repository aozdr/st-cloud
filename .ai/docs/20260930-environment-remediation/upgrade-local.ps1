param([string]$Classpath='C:/Users/aoz/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar')
$ErrorActionPreference='Stop'
if(-not $env:MYSQL_PWD){throw '凭据需要 MYSQL_PWD，禁止写入日志'}
if($env:STCLOUD_TEST_MYSQL_DATABASE -and $env:STCLOUD_TEST_MYSQL_DATABASE -ne 'stcloud'){throw '本次目标只允许已核对的本机开发 stcloud'}
$env:STCLOUD_TEST_MYSQL_HOST='127.0.0.1'
$env:STCLOUD_TEST_MYSQL_DATABASE='stcloud'
function Query([string]$sql){
  $output=& java --class-path $Classpath .ai/scripts/MysqlJdbc.java $sql
  if($LASTEXITCODE -ne 0){throw 'MySQL 语句失败，停止后续迁移'}
  return $output
}
$target=Query 'SELECT DATABASE() AS db,@@hostname AS hostname,VERSION() AS version;'
if(($target -join "`n") -notmatch 'stcloud\s+dd39e994c2df\s+8\.0\.46'){throw '真实库身份发生变化，停止写入'}
$target
$scripts=@('42_file_orphan_candidate.sql','42_object_upload_candidate.sql','43_file_watch.sql','44_team_role_bigint.sql','45_user_security_version.sql','46_user_security_version_retry.sql','47_environment_gc_indexes_retry.sql')
foreach($file in $scripts){
  # 43 非幂等列修改只在四列全部缺失时执行，部分存在则停止而不是冒险重放。
  if($file -eq '43_file_watch.sql'){
    $cols=Query "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification' AND COLUMN_NAME IN ('event_id','node_id','space_id','change_type');"
    $count=$cols | Select-Object -Last 1
    if($count -eq '4'){
      $complete=Query "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('file_watch','file_watch_delivery');"
      $unique=Query "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification' AND INDEX_NAME='uk_notification_tenant_user_event' AND NON_UNIQUE=0;"
      if(($complete | Select-Object -Last 1) -eq '2' -and ($unique | Select-Object -Last 1) -eq '3'){"SKIP $file (真实结构完整，恢复本次迁移)";continue}
    }
    if($count -ne '0'){throw 'notification 部分新版结构存在，请制定针对性增量迁移'}
  }
  "APPLY $file"
  Query "@docker/mysql/init/$file"
}
# 核实旧初始化已经具有 32~36 和 edit 数据；补版本记账不重复改写已有用户数据。
$missing=Query "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='file_share' AND COLUMN_NAME IN ('allow_download','permissions')) OR (TABLE_NAME='team_folder_permission' AND COLUMN_NAME='permissions') OR (TABLE_NAME='file_version' AND COLUMN_NAME='source'));"
if(($missing | Select-Object -Last 1) -ne '4'){throw '旧基线字段不完整，不能补记版本'}
$unmapped=Query "SELECT (SELECT COUNT(*) FROM team_folder_permission WHERE permission IN (0,1) AND permissions LIKE CONCAT('%',CHAR(34),'view',CHAR(34),':true%') AND permissions NOT LIKE CONCAT('%',CHAR(34),'edit',CHAR(34),'%')) + (SELECT COUNT(*) FROM file_share WHERE permission=3 AND permissions LIKE CONCAT('%',CHAR(34),'view',CHAR(34),':true%') AND permissions NOT LIKE CONCAT('%',CHAR(34),'edit',CHAR(34),'%')) AS n;"
if(($unmapped | Select-Object -Last 1) -ne '0'){throw '旧 edit 数据未完成，不能补记37'}
$files='09b_remove_two_factor.sql,32_file_block.sql,33_share_allow_download.sql,34_team_folder_permission_permissions.sql,35_file_share_permissions.sql,36_editor_version_source.sql,37_add_edit_permission.sql,'+($scripts -join ',')
$existing=Query "SELECT COUNT(*) AS n FROM schema_version WHERE version_tag='20260930.1';"
if(($existing | Select-Object -Last 1) -ne '0'){throw '版本号已使用，禁止覆盖'}
Query "SET NAMES utf8mb4; INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES ('20260930.1','旧开发环境对齐冻结源码及独立候选','$files','codex-workflow-manager','执行42两份、43、44、45、46、47；09b/32-37经实际结构和edit数据审计补记，未重放旧数据脚本。开发库保留已有行。');"
Query 'SELECT version_tag,iteration_name,applied_sql_files FROM schema_version ORDER BY id;'
