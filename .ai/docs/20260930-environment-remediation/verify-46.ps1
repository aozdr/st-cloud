$ErrorActionPreference='Stop'
$classpath='C:/Users/aoz/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar'
$database='stcloud_env_46_20260930'
if(-not $env:MYSQL_PWD){throw '需要 MYSQL_PWD'}
$env:STCLOUD_TEST_MYSQL_DATABASE=$database
function Query([string]$sql){
  $value=& java --class-path $classpath .ai/scripts/MysqlJdbc.java $sql
  if($LASTEXITCODE -ne 0){throw '独立测试查询/迁移失败'}
  return $value
}
$rows=Query 'SELECT COUNT(*) AS n FROM sys_user;'
if(($rows | Select-Object -Last 1) -ne '0'){throw '独立夹具非空，禁止删除列以免影响已有值'}
Query '@.ai/docs/20260930-environment-remediation/test-46.sql'
$missing=Query "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_user' AND COLUMN_NAME='security_version';"
if(($missing | Select-Object -Last 1) -ne '0'){throw '无列夹具未生效'}
'NO_COLUMN_BRANCH_CONFIRMED'
Query '@docker/mysql/init/46_user_security_version_retry.sql'
$definition=Query "SELECT COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_user' AND COLUMN_NAME='security_version';"
if(($definition -join "`n") -notmatch 'bigint\s+NO\s+0'){throw '字段定义不正确'}
$definition
Query "INSERT INTO sys_user(id,tenant_id,username,password) VALUES (46001,1,'env46_fixture','fixture-only');"
$initial=Query 'SELECT security_version FROM sys_user WHERE id=46001;'
if(($initial | Select-Object -Last 1) -ne '0'){throw '新用户默认版本不为0'}
Query 'UPDATE sys_user SET security_version=73 WHERE id=46001;'
Query '@docker/mysql/init/46_user_security_version_retry.sql'
Query '@docker/mysql/init/46_user_security_version_retry.sql'
$preserved=Query 'SELECT security_version FROM sys_user WHERE id=46001;'
if(($preserved | Select-Object -Last 1) -ne '73'){throw '重复迁移重置原值'}
'REPEAT_EXECUTION_PRESERVED_73'
$allFiles=(Get-ChildItem docker/mysql/init -Filter '*.sql' | Sort-Object Name | ForEach-Object Name) -join ','
Query "SET NAMES utf8mb4; INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES ('20260930.1','独立结构克隆与46无列分支验证','$allFiles','codex-workflow-manager','其余结构从已对齐开发库CREATE TABLE LIKE克隆；本库46直接在无列夹具上执行并重复两次，73保留。无用户数据复制。');"
foreach($suffix in 'first','repeat'){
  & .ai/scripts/compare-schema.ps1 -Database $database -JdbcClasspath $classpath *> ".ai/docs/20260930-environment-remediation/schema-46-$suffix.log"
  if($LASTEXITCODE -ne 0){throw '46测试库schema对比失败'}
  "SCHEMA_46_$suffix EXIT=0"
}
