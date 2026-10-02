#requires -Version 5.1
<#
.SYNOPSIS
  H2 schema.sql 与 MySQL 实际 schema 列对比脚本。
.DESCRIPTION
  对比 st-core/src/test/resources/schema.sql（H2 测试库）与运行中 MySQL 的列集差异，
  并输出 schema_version 表中未记录的待执行 SQL 文件清单。
  退出码：0 = PASS 无差异；1 = 有差异待处理。
#>
[CmdletBinding()]
param(
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 3306,
    [string]$MysqlUser = "root",
    [string]$MysqlPass = "123456",
    [string]$Database = "stcloud",
    [string]$MysqlPath = "E:\utils\mysql-8.0.44-winx64\bin\mysql.exe",
    [string]$SchemaSql = "st-core\src\test\resources\schema.sql",
    [string]$InitDir = "docker\mysql\init",
    [string]$DockerContainer = "",
    [string]$DockerPath = "docker",
    [string]$JdbcClasspath = "",
    [string]$JavaPath = "java"
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:FailCount = 0

function Write-Section($msg) { Write-Host "`n===== $msg =====" -ForegroundColor Cyan }
function Write-Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Diff($msg) { Write-Host "  [DIFF] $msg" -ForegroundColor Yellow; $script:FailCount++ }

# Docker 模式使用容器已有密码环境变量，不把凭据输出到日志；查询失败不得伪报 schema 对齐。
function Invoke-SchemaQuery([string]$Query) {
    if ($JdbcClasspath) {
        # Windows 旧环境未装 mysql CLI 时复用 Maven 缓存驱动；不把密码写入命令行。
        $jdbcSource = Join-Path $PSScriptRoot 'MysqlJdbc.java'
        $jdbcProcess = New-Object System.Diagnostics.ProcessStartInfo
        $jdbcProcess.FileName = $JavaPath
        $jdbcProcess.Arguments = "--class-path `"$JdbcClasspath`" `"$jdbcSource`" `"$Query`""
        $jdbcProcess.EnvironmentVariables['MYSQL_PWD'] = $MysqlPass
        $jdbcProcess.EnvironmentVariables['STCLOUD_TEST_MYSQL_HOST'] = $MysqlHost
        $jdbcProcess.EnvironmentVariables['STCLOUD_TEST_MYSQL_PORT'] = [string]$MysqlPort
        $jdbcProcess.EnvironmentVariables['STCLOUD_TEST_MYSQL_USER'] = $MysqlUser
        $jdbcProcess.EnvironmentVariables['STCLOUD_TEST_MYSQL_DATABASE'] = $Database
        $jdbcProcess.UseShellExecute = $false
        $jdbcProcess.RedirectStandardOutput = $true
        $jdbcProcess.RedirectStandardError = $true
        $jdbcProcess.StandardOutputEncoding = [System.Text.Encoding]::UTF8
        $child = [System.Diagnostics.Process]::Start($jdbcProcess)
        $errorRead = $child.StandardError.ReadToEndAsync()
        $queryText = $child.StandardOutput.ReadToEnd()
        $child.WaitForExit()
        $queryError = $errorRead.GetAwaiter().GetResult()
        if ($child.ExitCode -ne 0) { throw "JDBC MySQL query failed (exit $($child.ExitCode)): $queryError" }
        return $queryText
    }
    if ($DockerContainer) {
        $queryOutput = & $DockerPath exec $DockerContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --user="$1" --database="$2" -e "$3"' -- $MysqlUser $Database $Query 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Docker MySQL query failed (exit $LASTEXITCODE): $queryOutput" }
        return ($queryOutput -join "`n")
    }
    $queryProcess = New-Object System.Diagnostics.ProcessStartInfo
    $queryProcess.FileName = $MysqlPath
    $queryProcess.Arguments = "--host=$MysqlHost --port=$MysqlPort --user=$MysqlUser --database=$Database -e `"$Query`""
    $queryProcess.EnvironmentVariables['MYSQL_PWD'] = $MysqlPass
    $queryProcess.UseShellExecute = $false
    $queryProcess.RedirectStandardOutput = $true
    $queryProcess.RedirectStandardError = $true
    $queryProcess.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $queryChild = [System.Diagnostics.Process]::Start($queryProcess)
    $errorRead = $queryChild.StandardError.ReadToEndAsync()
    $queryText = $queryChild.StandardOutput.ReadToEnd()
    $queryChild.WaitForExit()
    $queryError = $errorRead.GetAwaiter().GetResult()
    if ($queryChild.ExitCode -ne 0) { throw "MySQL query failed (exit $($queryChild.ExitCode)): $queryError" }
    return $queryText
}
# ---------- 1. Parse H2 schema.sql ----------
Write-Section "1. Parse H2 schema.sql"
if (-not (Test-Path $SchemaSql)) {
    Write-Host "  [FAIL] schema.sql not found: $SchemaSql" -ForegroundColor Red; exit 1
}
$sqlLines = Get-Content $SchemaSql -Encoding UTF8
$h2Tables = @{}
$currentTable = $null
$currentCols = $null
foreach ($line in $sqlLines) {
    $trimmed = $line.Trim()
    if ($trimmed -match '^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(') {
        $currentTable = $Matches[1]
        $currentCols = [System.Collections.Generic.HashSet[string]]([System.StringComparer]::OrdinalIgnoreCase)
        continue
    }
    if ($currentTable -and $trimmed -match '^\);?\s*$') {
        $h2Tables[$currentTable] = $currentCols
        $currentTable = $null; $currentCols = $null
        continue
    }
    if ($currentTable -and $currentCols) {
        # MySQL/H2 schema may quote reserved identifiers (for example `read`).
        # Normalize identifier quotes before comparing against INFORMATION_SCHEMA.
        $col = (($trimmed -split '\s+')[0]).Trim('`', '"', '[', ']')
        $upper = $trimmed.ToUpper()
        if ($upper -match '^(PRIMARY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN|CHECK)') { continue }
        if ($col -match '^\w+$' -and $col -cnotin @('IF','NOT','EXISTS','ENGINE','DEFAULT','CHARSET','COLLATE','COMMENT')) {
            [void]$currentCols.Add($col)
        }
    }
}
Write-Host "  H2 schema.sql: $($h2Tables.Count) tables"

# ---------- 2. Query MySQL columns ----------
Write-Section "2. Query MySQL schema"
$mysqlTables = @{}
$query = "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='$Database' ORDER BY TABLE_NAME, ORDINAL_POSITION;"
$stdout = Invoke-SchemaQuery $query
foreach ($line in ($stdout -split "`n")) {
    $parts = $line -split "`t"
    if ($parts.Count -ge 2 -and $parts[0].Trim() -ne 'TABLE_NAME') {
        $tbl = $parts[0].Trim(); $col = $parts[1].Trim()
        if ($tbl -and $col) {
            if (-not $mysqlTables.ContainsKey($tbl)) {
                $mysqlTables[$tbl] = [System.Collections.Generic.HashSet[string]]([System.StringComparer]::OrdinalIgnoreCase)
            }
            [void]$mysqlTables[$tbl].Add($col)
        }
    }
}
Write-Host "  MySQL: $($mysqlTables.Count) tables"

# ---------- 3. Compare column differences ----------
Write-Section "3. Column diff (shared tables)"
$commonTables = $h2Tables.Keys | Where-Object { $mysqlTables.ContainsKey($_) }
foreach ($tbl in ($commonTables | Sort-Object)) {
    $h2Cols = $h2Tables[$tbl]
    $mysqlCols = $mysqlTables[$tbl]
    $onlyH2 = @($h2Cols | Where-Object { -not $mysqlCols.Contains($_) } | Sort-Object)
    $onlyMysql = @($mysqlCols | Where-Object { -not $h2Cols.Contains($_) } | Sort-Object)
    if ($onlyH2.Count -gt 0) { Write-Diff "[$tbl] H2 only: $($onlyH2 -join ', ')" }
    if ($onlyMysql.Count -gt 0) { Write-Diff "[$tbl] MySQL only: $($onlyMysql -join ', ')" }
    if ($onlyH2.Count -eq 0 -and $onlyMysql.Count -eq 0) { Write-Pass "[$tbl] aligned ($($h2Cols.Count) cols)" }
}
$h2Only = $h2Tables.Keys | Where-Object { -not $mysqlTables.ContainsKey($_) } | Sort-Object
foreach ($tbl in $h2Only) { Write-Diff "[$tbl] table only in H2, missing in MySQL" }
$mysqlOnly = $mysqlTables.Keys | Where-Object { -not $h2Tables.ContainsKey($_) } | Sort-Object
foreach ($tbl in $mysqlOnly) { Write-Host "  [INFO] [$tbl] only in MySQL (H2 schema.sql may not need it)" -ForegroundColor DarkGray }

# ---------- 4. Pending SQL files ----------
Write-Section "4. Pending SQL files (not in schema_version)"
$allSqlFiles = Get-ChildItem $InitDir -Filter "*.sql" | Sort-Object Name | Select-Object -ExpandProperty Name
$stdout2 = Invoke-SchemaQuery 'SELECT applied_sql_files FROM schema_version;'
$appliedSet = [System.Collections.Generic.HashSet[string]]([System.StringComparer]::OrdinalIgnoreCase)
foreach ($line in ($stdout2 -split "`n")) {
    foreach ($f in ($line -split ',')) { $f = $f.Trim(); if ($f -and $f -ne 'applied_sql_files') { [void]$appliedSet.Add($f) } }
}
$pending = @($allSqlFiles | Where-Object { -not $appliedSet.Contains($_) } | Sort-Object)
if ($pending.Count -gt 0) {
    Write-Diff "Pending SQL files (not recorded in schema_version):"
    foreach ($f in $pending) { Write-Host "      - $f" -ForegroundColor Yellow }
} else {
    Write-Pass "All SQL files recorded in schema_version"
}

# ---------- Summary ----------
Write-Section "Summary"
if ($script:FailCount -eq 0) {
    Write-Host "  Result: PASS (no diff)" -ForegroundColor Green
    exit 0
} else {
    Write-Host "  Result: $script:FailCount diff(s) need attention" -ForegroundColor Red
    exit 1
}
