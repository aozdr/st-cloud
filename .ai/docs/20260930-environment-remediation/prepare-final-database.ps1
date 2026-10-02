param([string]$Name='stcloud_review_fixes_20260930_full3')
$ErrorActionPreference='Stop'
if ($Name -notmatch '^stcloud_review_fixes_20260930_full\d+$') { throw '专用库名不合法' }
if (-not $env:MYSQL_PWD) { throw '需要 MYSQL_PWD' }
$classpath='C:/Users/aoz/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar'
$env:STCLOUD_TEST_MYSQL_DATABASE='stcloud'
$tables=& java --class-path $classpath .ai/scripts/MysqlJdbc.java 'SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME;'
if ($LASTEXITCODE -ne 0) { throw '读取结构失败' }
$tables=@($tables | Where-Object { $_ -ne 'TABLE_NAME' -and $_ -match '^\w+$' })
# 保留失败批次，只复制结构和默认租户/权限配置，无用户、文件或令牌。
$sql="CREATE DATABASE $Name CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
foreach ($table in $tables) { $sql+="CREATE TABLE $Name.$table LIKE stcloud.$table;" }
$sql+="INSERT INTO $Name.sys_tenant SELECT * FROM stcloud.sys_tenant WHERE id=1;"
foreach ($table in 'sys_role','sys_role_permission','sys_permission') { $sql+="INSERT INTO $Name.$table SELECT * FROM stcloud.$table;" }
& java --class-path $classpath .ai/scripts/MysqlJdbc.java $sql
if ($LASTEXITCODE -ne 0) { throw '建立独立库失败' }
"PREPARED $Name ($($tables.Count) tables; only default tenant and permission configuration copied)"
