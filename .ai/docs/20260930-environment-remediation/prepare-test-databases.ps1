$ErrorActionPreference='Stop'
if(-not $env:MYSQL_PWD){throw '需要 MYSQL_PWD'}
$classpath='C:/Users/aoz/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar'
$env:STCLOUD_TEST_MYSQL_DATABASE='stcloud'
$tables=& java --class-path $classpath .ai/scripts/MysqlJdbc.java 'SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME;'
if($LASTEXITCODE -ne 0){throw '读取开发结构失败'}
$tables=@($tables | Where-Object {$_ -ne 'TABLE_NAME' -and $_ -match '^\w+$'})
foreach($name in 'stcloud_review_fixes_20260930_env','stcloud_team_recycle_20260930_env','stcloud_env_46_20260930'){
  # 只克隆结构和 schema 审计记录，不复制用户、文件或令牌数据。
  $sql="CREATE DATABASE IF NOT EXISTS $name CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  foreach($table in $tables){$sql+="CREATE TABLE IF NOT EXISTS $name.$table LIKE stcloud.$table;"}
  & java --class-path $classpath .ai/scripts/MysqlJdbc.java $sql
  if($LASTEXITCODE -ne 0){throw "建立独立测试结构失败：$name"}
  "PREPARED $name ($($tables.Count) tables; no business rows copied)"
}
