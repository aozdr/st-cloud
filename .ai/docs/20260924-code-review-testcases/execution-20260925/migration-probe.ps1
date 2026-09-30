# Isolated container only: 127.0.0.1:13306/stcloud_test.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$mysql = 'E:\utils\mysql-8.0.44-winx64\bin\mysql.exe'
$common = @('--host=127.0.0.1', '--port=13306', '--user=root', '--database=stcloud_test', '--default-character-set=utf8mb4', '--batch', '--skip-column-names')
$env:MYSQL_PWD = 'codex-test-mysql-only-20260925'

function Invoke-Sql([string]$sql) {
    $output = & $mysql @common -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Isolated SQL failed: $output" }
    return $output
}

try {
    $identity = Invoke-Sql 'SELECT DATABASE(), @@port;'
    if (($identity -join '') -notmatch 'stcloud_test') { throw 'Unexpected database target' }
    $fixture = Get-Content (Join-Path $PSScriptRoot 'migration-fixture.sql') -Raw
    Invoke-Sql $fixture | Out-Null
    $before = Invoke-Sql "SELECT TABLE_NAME, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='stcloud_test' AND COLUMN_NAME='role' ORDER BY TABLE_NAME;"
    if (@($before | Where-Object { $_ -match 'tinyint' }).Count -ne 2) { throw "Pre-migration role type mismatch: $before" }
    $migration44 = (Join-Path $repo 'docker/mysql/init/44_team_role_bigint.sql').Replace('\', '/')
    $migration45 = (Join-Path $repo 'docker/mysql/init/45_user_security_version.sql').Replace('\', '/')
    Invoke-Sql "source $migration44" | Out-Null
    Invoke-Sql "source $migration45" | Out-Null
    $columns = Invoke-Sql "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,LENGTH(COLUMN_COMMENT) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='stcloud_test' AND ((TABLE_NAME='team_member' AND COLUMN_NAME='role') OR (TABLE_NAME='team_invite' AND COLUMN_NAME='role') OR (TABLE_NAME='sys_user' AND COLUMN_NAME='security_version')) ORDER BY TABLE_NAME;"
    $roleColumns = @($columns | Where-Object { $_ -match 'role' -and $_ -match 'bigint' -and $_ -match "`tNO`t2`t[1-9][0-9]*$" })
    $versionColumns = @($columns | Where-Object { $_ -match 'security_version' -and $_ -match 'bigint' -and $_ -match "`tNO`t0`t[1-9][0-9]*$" })
    if ($roleColumns.Count -ne 2 -or $versionColumns.Count -ne 1) { throw "Post-migration column mismatch: $columns" }
    $oldRows = Invoke-Sql 'SELECT id,role FROM team_member ORDER BY id; SELECT id,role FROM team_invite ORDER BY id;'
    $expectedOld = @("1`t0", "2`t1", "3`t2", "4`t9", "1`t0", "2`t1", "3`t2", "4`t9")
    if (($oldRows -join '|') -ne ($expectedOld -join '|')) { throw "Old role rows changed: $oldRows" }
    Invoke-Sql "INSERT INTO team_member(id,role) VALUES(9007199254740993,9007199254740995); INSERT INTO team_invite(id,role) VALUES(9007199254740995,9007199254740993); INSERT INTO team_member(id) VALUES(5); INSERT INTO team_invite(id) VALUES(5); INSERT INTO sys_user(id,username) VALUES(3,'new'); UPDATE sys_user SET security_version=security_version+1 WHERE id=1;" | Out-Null
    $large = Invoke-Sql 'SELECT role FROM team_member WHERE id=9007199254740993; SELECT role FROM team_invite WHERE id=9007199254740995; SELECT role FROM team_member WHERE id=5; SELECT role FROM team_invite WHERE id=5; SELECT id,username,security_version FROM sys_user ORDER BY id;'
    $expected = @('9007199254740995','9007199254740993','2','2',"1`tactive`t1","2`tdisabled`t0","3`tnew`t0")
    if (($large -join '|') -ne ($expected -join '|')) { throw "Large ID/default/version mismatch: $large" }
    Write-Output 'PASS TCDB-01: role BIGINT, old rows, large ID and default 2'
    Write-Output 'PASS TCDB-02: security_version BIGINT, default 0 and increment'
    Write-Output 'Isolated post-migration schema:'
    $columns | ForEach-Object { Write-Output $_ }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
