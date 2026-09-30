$ErrorActionPreference = 'Stop'
$mysqlTool = 'E:/utils/mysql-8.0.44-winx64/bin/mysql.exe'
$reviewDatabase = 'stcloud_review_fixes_20260930'
$reviewDirectory = '.ai/docs/20260930-review-fixes'
$mysqlArgs = @('--host=127.0.0.1','--port=3306','--user=root',('--password=' + $env:STCLOUD_TEST_MYSQL_PASSWORD),'--default-character-set=utf8mb4','--batch')
function Invoke-ReviewSql([string]$sql) {
    $sql | & $mysqlTool @mysqlArgs "--database=$reviewDatabase"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL SQL执行失败' }
}
$exists = & $mysqlTool @mysqlArgs --skip-column-names -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$reviewDatabase'"
if ($LASTEXITCODE -ne 0 -or [int]$exists -ne 0) { throw '仅允许创建不存在的独立测试库，禁止重用或修改现有数据库' }
& $mysqlTool @mysqlArgs -e "CREATE DATABASE $reviewDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
if ($LASTEXITCODE -ne 0) { throw '创建独立测试库失败' }
New-Item -ItemType Directory -Path "$reviewDirectory/init-before" -Force | Out-Null
$baselineSql = Get-ChildItem docker/mysql/init -Filter '*.sql' | Where-Object { $_.Name -notlike '46_*' } | Sort-Object Name
foreach ($file in $baselineSql) {
    $sql = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    $sql = $sql -replace '(?i)CREATE DATABASE IF NOT EXISTS stcloud\b', "CREATE DATABASE IF NOT EXISTS $reviewDatabase"
    $sql = $sql -replace '(?i)USE stcloud\s*;', "USE $reviewDatabase;"
    if ($sql -match '(?i)USE stcloud\s*;' -or $sql -match '(?i)CREATE DATABASE IF NOT EXISTS stcloud\b') { throw '检测到共享库目标' }
    Invoke-ReviewSql $sql
    Copy-Item -LiteralPath $file.FullName -Destination "$reviewDirectory/init-before/$($file.Name)"
    Write-Output "APPLIED $($file.Name)"
}
$fileList = ($baselineSql.Name -join ',')
Invoke-ReviewSql "INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES('20260930.901','Review独立库真实基线回放','$fileList','workflow-manager','仅本任务独立测试库，逐个回放当前02至45迁移')"
& .ai/scripts/compare-schema.ps1 -Database $reviewDatabase -InitDir "$reviewDirectory/init-before" *> "$reviewDirectory/schema-before.log"
if ($LASTEXITCODE -ne 0) { throw '第一次schema对比未通过' }
Write-Output 'SCHEMA_BEFORE_EXIT=0'
# 第二次执行45不能重置已有版本；46对已迁移库执行两次同样不能改变数据。
Invoke-ReviewSql 'UPDATE sys_user SET security_version=17 WHERE id=1'
Invoke-ReviewSql (Get-Content docker/mysql/init/45_user_security_version.sql -Raw -Encoding UTF8)
Invoke-ReviewSql (Get-Content docker/mysql/init/46_user_security_version_retry.sql -Raw -Encoding UTF8)
Invoke-ReviewSql (Get-Content docker/mysql/init/46_user_security_version_retry.sql -Raw -Encoding UTF8)
$version = & $mysqlTool @mysqlArgs --database=$reviewDatabase --skip-column-names -e 'SELECT security_version FROM sys_user WHERE id=1'
if ([long]$version -ne 17) { throw '重复迁移改变了已有安全版本' }
Invoke-ReviewSql "INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES('20260930.902','安全版本迁移重试幂等修复','45_user_security_version.sql,46_user_security_version_retry.sql','workflow-manager','45二次、46两次成功，已有版本17保留；MySQL 8.0.44本机独立测试库')"
& .ai/scripts/compare-schema.ps1 -Database $reviewDatabase *> "$reviewDirectory/schema-after.log"
if ($LASTEXITCODE -ne 0) { throw '第二次schema对比未通过' }
Write-Output 'SCHEMA_AFTER_EXIT=0; SECURITY_VERSION=17'
Invoke-ReviewSql "SELECT version_tag,iteration_name,applied_sql_files,applied_by,notes FROM schema_version WHERE version_tag LIKE '20260930.%'"
