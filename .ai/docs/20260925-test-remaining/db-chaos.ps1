# 在唯一临时容器中验证部分 DDL 已提交后的恢复与大角色 ID 缩列风险。
$ErrorActionPreference = 'Stop'
$container = 'codex-stcloud-remain-db-20260925'
$password = $env:STCLOUD_TEST_MYSQL_PASSWORD
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$existing = & docker ps -a --filter "name=^/$container`$" --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw '无法确认测试容器状态' }
if ($existing) { throw "容器名已存在，拒绝复用或清理：$container" }

function Invoke-Sql([string]$sql) {
    $output = & docker exec -e "MYSQL_PWD=$password" $container mysql -uroot -Dstcloud_test --batch --skip-column-names -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$output" }
    return $output
}
function Assert-SqlFailure([string]$sql) {
    $output = & docker exec -e "MYSQL_PWD=$password" $container mysql -uroot -Dstcloud_test --batch --skip-column-names -e $sql 2>&1
    if ($LASTEXITCODE -eq 0) { throw '预期 SQL 失败，但已成功' }
    return $output
}
function Assert-Equal([string]$actual, [string]$expected, [string]$label) {
    if ($actual -ne $expected) { throw "$label 不符合预期：[$actual] != [$expected]" }
}

try {
    $started = & docker run --rm -d --name $container -p 127.0.0.1:13307:3306 -e "MYSQL_ROOT_PASSWORD=$password" -e MYSQL_DATABASE=stcloud_test mysql:8.0
    if ($LASTEXITCODE -ne 0 -or -not $started) { throw '临时 MySQL 容器启动失败' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        # mysqladmin ping 在临时初始化实例上即使认证失败也可能返回成功；等待目标库真正可认证。
        & docker exec -e "MYSQL_PWD=$password" $container mysql -uroot -Dstcloud_test -e 'SELECT 1;' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw '临时 MySQL 容器未就绪' }
    $identity = Invoke-Sql 'SELECT DATABASE(), @@port;'
    Assert-Equal ($identity -join '') "stcloud_test`t3306" '临时库身份'

    $fixture = Get-Content (Join-Path $repository '.ai/docs/20260924-code-review-testcases/execution-20260925/migration-fixture.sql') -Raw
    Invoke-Sql $fixture | Out-Null
    Invoke-Sql @'
CREATE TABLE schema_version (
  version_tag VARCHAR(20) PRIMARY KEY,
  iteration_name VARCHAR(200) NOT NULL,
  applied_sql_files TEXT NOT NULL,
  applied_by VARCHAR(100) NOT NULL,
  notes TEXT
);
'@ | Out-Null
    $before = Invoke-Sql 'SELECT role FROM team_member ORDER BY id;'
    Assert-Equal ($before -join ',') '0,1,2,9' '迁移前角色'

    $sql44 = Get-Content (Join-Path $repository 'docker/mysql/init/44_team_role_bigint.sql') -Raw
    $sql45 = Get-Content (Join-Path $repository 'docker/mysql/init/45_user_security_version.sql') -Raw
    Invoke-Sql $sql44 | Out-Null
    # 注入 45 的列冲突：44 的 DDL 已经独立提交，整个迁移批次尚未记录版本。
    Invoke-Sql 'ALTER TABLE sys_user ADD COLUMN security_version INT NULL;' | Out-Null
    $failure = Assert-SqlFailure $sql45
    if (($failure -join '') -notmatch 'Duplicate column') { throw "不是预期的 45 故障：$failure" }
    Assert-Equal ((Invoke-Sql "SELECT COUNT(*) FROM schema_version WHERE version_tag='20260925.2';") -join '') '0' '失败后的版本登记'
    Assert-Equal ((Invoke-Sql "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='stcloud_test' AND TABLE_NAME='team_member' AND COLUMN_NAME='role';") -join '') 'bigint' '44 已提交'
    Assert-Equal ((Invoke-Sql 'SELECT role FROM team_member ORDER BY id;') -join ',') '0,1,2,9' '44 后旧角色'
    Invoke-Sql 'ALTER TABLE sys_user DROP COLUMN security_version;' | Out-Null
    Invoke-Sql $sql45 | Out-Null
    Invoke-Sql "INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES('20260925.2','Isolated failure recovery','44_team_role_bigint.sql,45_user_security_version.sql','codex-test','44 committed; 45 retried after injected duplicate column');" | Out-Null
    $duplicate = Assert-SqlFailure "INSERT INTO schema_version(version_tag,iteration_name,applied_sql_files,applied_by,notes) VALUES('20260925.2','duplicate','45_user_security_version.sql','codex-test','duplicate');"
    if (($duplicate -join '') -notmatch 'Duplicate entry') { throw "版本唯一性检查异常：$duplicate" }
    Assert-Equal ((Invoke-Sql "SELECT COUNT(*) FROM schema_version WHERE version_tag='20260925.2';") -join '') '1' '恢复后的唯一版本'
    Assert-Equal ((Invoke-Sql 'SELECT id,username,security_version FROM sys_user ORDER BY id;') -join '|') "1`tactive`t0|2`tdisabled`t0" '恢复后存量用户'
    Write-Output 'PASS TCDB-05: 44 已提交、45 故障且未误登记、恢复后唯一版本及原数据保留'

    Invoke-Sql 'INSERT INTO team_member(id,role) VALUES(9007199254740993,9007199254740995);' | Out-Null
    Assert-Equal ((Invoke-Sql 'SELECT role FROM team_member WHERE id=9007199254740993;') -join '') '9007199254740995' '自定义大角色'
    $rollback = Assert-SqlFailure 'ALTER TABLE team_member MODIFY COLUMN role TINYINT NOT NULL DEFAULT 2;'
    if (($rollback -join '') -notmatch 'Out of range') { throw "缩列并非预期的越界失败：$rollback" }
    Assert-Equal ((Invoke-Sql "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='stcloud_test' AND TABLE_NAME='team_member' AND COLUMN_NAME='role';") -join '') 'bigint' '缩列拒绝后列类型'
    Assert-Equal ((Invoke-Sql 'SELECT role FROM team_member WHERE id=9007199254740993;') -join '') '9007199254740995' '缩列拒绝后大角色'
    Write-Output 'PARTIAL TCDB-06: 大角色精确持久化、TINYINT 缩列失败且原值保留；旧版 Integer 服务与客户端未演练'
} finally {
    $owned = & docker ps -a --filter "name=^/$container`$" --format '{{.Names}}'
    if ($LASTEXITCODE -eq 0 -and ($owned -join '') -eq $container) {
        & docker stop $container | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "未能停止临时容器：$container" }
    }
}
