# Disposable Docker MySQL only: 127.0.0.1:13306/stcloud.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$mysql = 'E:\utils\mysql-8.0.44-winx64\bin\mysql.exe'
$common = @('--host=127.0.0.1', '--port=13306', '--user=root', '--database=stcloud', '--default-character-set=utf8mb4', '--batch', '--skip-column-names')
$env:MYSQL_PWD = 'codex-test-mysql-only-20260925'

function Invoke-Sql([string]$sql) {
    $output = & $mysql @common -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Isolated SQL failed: $output" }
    return $output
}

try {
    $identity = Invoke-Sql 'SELECT DATABASE();'
    if (($identity -join '') -ne 'stcloud') { throw 'Unexpected database target' }
    $before = Invoke-Sql "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='stcloud' AND ((TABLE_NAME='team_member' AND COLUMN_NAME='role') OR (TABLE_NAME='team_invite' AND COLUMN_NAME='role') OR (TABLE_NAME='sys_user' AND COLUMN_NAME='security_version')) ORDER BY TABLE_NAME;"
    if (@($before | Where-Object { $_ -match 'tinyint' }).Count -ne 2 -or @($before | Where-Object { $_ -match 'security_version' }).Count -ne 0) {
        throw "Unexpected pre-migration schema: $before"
    }
    $migration44 = (Join-Path $repo 'docker/mysql/init/44_team_role_bigint.sql').Replace('\', '/')
    $migration45 = (Join-Path $repo 'docker/mysql/init/45_user_security_version.sql').Replace('\', '/')
    Invoke-Sql "source $migration44" | Out-Null
    Invoke-Sql "source $migration45" | Out-Null
    $after = Invoke-Sql "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='stcloud' AND ((TABLE_NAME='team_member' AND COLUMN_NAME='role') OR (TABLE_NAME='team_invite' AND COLUMN_NAME='role') OR (TABLE_NAME='sys_user' AND COLUMN_NAME='security_version')) ORDER BY TABLE_NAME;"
    if (@($after | Where-Object { $_ -match 'role' -and $_ -match 'bigint' }).Count -ne 2 -or @($after | Where-Object { $_ -match 'security_version' -and $_ -match 'bigint' }).Count -ne 1) {
        throw "Post-migration schema mismatch: $after"
    }
    # Earlier init scripts were executed automatically by Docker but several had no schema_version row.
    # Record these historical scripts plus the two new migrations only in this disposable database.
    $applied = '09b_remove_two_factor.sql,32_file_block.sql,33_share_allow_download.sql,34_team_folder_permission_permissions.sql,35_file_share_permissions.sql,36_editor_version_source.sql,37_add_edit_permission.sql,38_share_security_config.sql,39_reset_editing_permission.sql,40_upload_session.sql,41_file_node_version_uniqueness.sql,43_file_watch.sql,44_team_role_bigint.sql,45_user_security_version.sql'
    Invoke-Sql "INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES('20260925.1','Isolated test baseline and role/security migration','$applied','codex-test','Disposable container only; 44 and 45 applied after pre-compare');" | Out-Null
    $record = Invoke-Sql "SELECT version_tag,applied_sql_files FROM schema_version WHERE version_tag='20260925.1';"
    if (($record -join '') -notmatch '44_team_role_bigint.sql,45_user_security_version.sql') { throw 'Migration version not recorded' }
    Write-Output 'PASS isolated full 02-43 init, then 44/45 and unique schema_version registration'
    $after | ForEach-Object { Write-Output $_ }
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
