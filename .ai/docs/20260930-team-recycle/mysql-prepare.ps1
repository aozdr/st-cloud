$ErrorActionPreference = 'Stop'
$mysqlTool = 'E:/utils/mysql-8.0.44-winx64/bin/mysql.exe'
$reviewDatabase = 'stcloud_team_recycle_20260930'
$reviewDirectory = '.ai/docs/20260930-team-recycle'
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
$baselineSql = Get-ChildItem docker/mysql/init -Filter '*.sql' | Sort-Object Name
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
Invoke-ReviewSql "INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES('20260930.903','团队回收站独立库基线','$fileList','workflow-manager','仅本任务独立测试库，逐个回放当前02至46迁移')"

& .ai/scripts/compare-schema.ps1 -Database $reviewDatabase *> "$reviewDirectory/schema-before.log"
if ($LASTEXITCODE -ne 0) { throw "schema对比未通过" }
Write-Output "SCHEMA_BEFORE_EXIT=0"
