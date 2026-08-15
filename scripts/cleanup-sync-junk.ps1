param(
    [switch]$Apply,
    [string]$Token = "",
    [string]$ServerUrl = "http://127.0.0.1:8080",
    [string]$MysqlHost = "127.0.0.1",
    [string]$MysqlPort = "3306",
    [string]$MysqlUser = "root",
    [string]$MysqlPass = "123456",
    [string]$MysqlDb = "stcloud"
)

# ============================================================
# 同步冲突副本清理脚本（20260815-sync-refactor）
#
# 背景：旧版 keep_both 冲突处理会把冲突副本回流上传，云端产生大量
# 机器格式垃圾副本：xxx (本地-20260815141555).zip / xxx (冲突-...).zip。
#
# 用法：
#   1) 先 dry-run（默认）：仅列出匹配的垃圾节点，不删除
#      powershell -ExecutionPolicy Bypass -File scripts/cleanup-sync-junk.ps1
#   2) apply：调用 /api/admin/sync/cleanup-junk
#      该接口复用 RecycleBinService.permanentDeleteAdmin：
#      引用计数归零时删除 S3 物理对象（RustFS bucket: stcloud）、退还配额、清理 ES 索引。
#      powershell -ExecutionPolicy Bypass -File scripts/cleanup-sync-junk.ps1 -Apply -Token "<管理员JWT>"
#
# 注：本地桌面端数据库由同步引擎升级时自动全量重置（sync_version 门控），本脚本不处理本地库。
# ============================================================

$ErrorActionPreference = "Stop"

$junkSql = @"
SELECT fn.id AS node_id, fn.tenant_id, fn.owner_id, fn.name, fn.path, fn.storage_path, fn.file_size
FROM file_node fn
WHERE fn.deleted = 0
  AND fn.node_type = 1
  AND fn.name REGEXP '\\((本地|冲突)-[0-9]{14}(-[0-9]+)?\\)(\\.[^/\\\\]+)?$'
  AND EXISTS (
    SELECT 1 FROM sync_root sr
    JOIN file_node folder ON folder.id = sr.cloud_folder_node_id
    WHERE folder.path IS NOT NULL
      AND (fn.path = folder.path OR fn.path LIKE CONCAT(folder.path, '/%'))
  )
ORDER BY fn.id;
"@

function Invoke-MysqlQuery([string]$sql) {
    $args = @(
        "--host=$MysqlHost",
        "--port=$MysqlPort",
        "--user=$MysqlUser",
        "--password=$MysqlPass",
        "--database=$MysqlDb",
        "--default-character-set=utf8mb4",
        "-e", $sql
    )
    & mysql @args
    if ($LASTEXITCODE -ne 0) {
        throw "mysql query failed with exit code $LASTEXITCODE"
    }
}

Write-Host "=== 同步冲突副本清理 ==="
Write-Host "[1/2] 查询匹配的垃圾节点（dry-run 视图）..."

$rows = Invoke-MysqlQuery $junkSql
if (-not $rows) {
    Write-Host "未发现机器格式冲突副本，无需清理。"
} else {
    $rows | ForEach-Object { Write-Host $_ }
    $count = ($rows | Measure-Object -Line).Lines
    Write-Host "共匹配 $count 个节点。"
}

if (-not $Apply) {
    Write-Host ""
    Write-Host "dry-run 模式：未删除任何数据。确认无误后加 -Apply -Token <管理员JWT> 执行清理。"
    exit 0
}

if (-not $Token) {
    throw "apply 模式必须提供 -Token（管理员 JWT）。"
}

Write-Host ""
Write-Host "[2/2] 调用 $ServerUrl/api/admin/sync/cleanup-junk ..."

$headers = @{ Authorization = "Bearer $Token" }
$resp = Invoke-RestMethod -Method Post -Uri "$ServerUrl/api/admin/sync/cleanup-junk" -Headers $headers -ContentType "application/json"
$data = $resp.data
if ($data) {
    Write-Host "清理完成：扫描同步根 $($data.scannedRoots) 个，删除垃圾节点 $($data.foundJunkNodes) 个，跳过文件夹 $($data.skippedFolders) 个。"
    Write-Host "注：S3 物理对象在对应 file_object 引用计数归零时已由服务端删除（bucket: stcloud）。"
} else {
    Write-Host "响应异常：$($resp | ConvertTo-Json -Depth 5)"
}

Write-Host ""
Write-Host "复核剩余垃圾节点："
$remain = Invoke-MysqlQuery $junkSql
if (-not $remain) {
    Write-Host "已全部清理。"
} else {
    $remain | ForEach-Object { Write-Host $_ }
}
