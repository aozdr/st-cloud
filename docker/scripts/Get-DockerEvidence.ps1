param(
    [string]$DockerPath = (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
# 只保存运行身份、状态和挂载；完整 inspect 含凭据，禁止原样输出。
$infoTextOps = & $DockerPath info --format '{{json .}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker daemon unavailable.' }
$infoOps = $infoTextOps | ConvertFrom-Json
$namesOps = @('stcloud-mysql', 'stcloud-redis', 'stcloud-rustfs', 'stcloud-rocketmq-namesrv', 'stcloud-rocketmq-broker', 'stcloud-rocketmq-dashboard', 'stcloud-elasticsearch', 'stcloud-onlyoffice')
$containersOps = foreach ($nameOps in $namesOps) {
    $inspectTextOps = & $DockerPath inspect $nameOps
    if ($LASTEXITCODE -ne 0) { throw ('Cannot inspect ' + $nameOps) }
    $containerOps = ($inspectTextOps | ConvertFrom-Json)[0]
    $imageTextOps = & $DockerPath image inspect $containerOps.Image
    if ($LASTEXITCODE -ne 0) { throw ('Cannot inspect image for ' + $nameOps) }
    $imageOps = ($imageTextOps | ConvertFrom-Json)[0]
    [ordered]@{
        name = $nameOps
        imageReference = $containerOps.Config.Image
        imageId = $containerOps.Image
        repoDigests = @($imageOps.RepoDigests)
        created = $containerOps.Created
        started = $containerOps.State.StartedAt
        status = $containerOps.State.Status
        lastExitCode = $containerOps.State.ExitCode
        oom = $containerOps.State.OOMKilled
        restartCount = $containerOps.RestartCount
        healthConfigured = [bool]$containerOps.Config.Healthcheck
        health = $containerOps.State.Health.Status
        memoryLimitBytes = $containerOps.HostConfig.Memory
        architecture = $imageOps.Architecture
        os = $imageOps.Os
        mounts = @($containerOps.Mounts | ForEach-Object { [ordered]@{ type = $_.Type; name = $_.Name; destination = $_.Destination; writable = $_.RW } })
    }
}
$evidenceOps = [ordered]@{
    checkedAtUtc = [DateTime]::UtcNow.ToString('o')
    dockerVersion = $infoOps.ServerVersion
    cpu = $infoOps.NCPU
    memoryBytes = $infoOps.MemTotal
    containers = @($containersOps)
    historicalRootCause = 'UNKNOWN: 当前容器状态仅证明本次启动状态；没有故障时内核/daemon/OOM 时间线，不能反推历史原因。'
    brokerProtection = '当前 Broker /home/rocketmq/store 无挂载；禁止直接 compose up 重建。先取得停机与持久化迁移授权，完整备份并验证消息/位点后再更新。'
    healthcheckActivation = '配置探针由 Test-Dependencies.ps1 手动执行。旧容器没有新 Healthcheck；本轮没有重建容器。'
}
$jsonOps = $evidenceOps | ConvertTo-Json -Depth 10
if ($OutputPath) { [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $jsonOps, (New-Object Text.UTF8Encoding($false))) }
else { Write-Output $jsonOps }
