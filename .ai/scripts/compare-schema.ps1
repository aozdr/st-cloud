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
    [string]$InitDir = "docker\mysql\init"
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:FailCount = 0

function Write-Section($msg) { Write-Host "`n===== $msg =====" -ForegroundColor Cyan }
function Write-Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Diff($msg) { Write-Host "  [DIFF] $msg" -ForegroundColor Yellow; $script:FailCount++ }

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
        $col = ($trimmed -split '\s+')[0]
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
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $MysqlPath
$psi.Arguments = "--host=$MysqlHost --port=$MysqlPort --user=$MysqlUser --password=$MysqlPass --database=$Database -e `"$query`""
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$proc = [System.Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEnd()
$proc.WaitForExit()
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
$psi2 = New-Object System.Diagnostics.ProcessStartInfo
$psi2.FileName = $MysqlPath
$psi2.Arguments = "--host=$MysqlHost --port=$MysqlPort --user=$MysqlUser --password=$MysqlPass --database=$Database -e `"SELECT applied_sql_files FROM schema_version;`""
$psi2.UseShellExecute = $false
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError = $true
$psi2.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$proc2 = [System.Diagnostics.Process]::Start($psi2)
$stdout2 = $proc2.StandardOutput.ReadToEnd()
$proc2.WaitForExit()
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