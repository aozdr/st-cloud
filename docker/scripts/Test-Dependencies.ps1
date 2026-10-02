param(
    [string]$DockerPath,
    [string]$MavenRepository = (Join-Path $env:USERPROFILE '.m2/repository'),
    [string]$JavaPath = 'java',
    [string]$PythonPath = 'python',
    [string]$NameServer = '127.0.0.1:9876',
    [string]$S3Endpoint = 'http://127.0.0.1:9000',
    [string]$S3Bucket = 'stcloud',
    [switch]$WriteProbe
)
$ErrorActionPreference = 'Stop'
if (-not $DockerPath) {
    $dockerCommandOps = Get-Command docker -ErrorAction SilentlyContinue
    if ($dockerCommandOps) { $DockerPath = $dockerCommandOps.Source }
    else {
        $DockerPath = Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
        if (-not (Test-Path -LiteralPath $DockerPath)) { throw 'Docker CLI unavailable; specify -DockerPath.' }
    }
}
$composeOps = Join-Path $PSScriptRoot '../docker-compose.yml'
# 渲染配置仅留内存，不能把含环境变量凭据的完整 config 写入日志。
$configTextOps = & $DockerPath compose -f $composeOps config --format json
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration invalid.' }
$configOps = $configTextOps | ConvertFrom-Json
$failedOps = 0
foreach ($serviceOps in $configOps.services.PSObject.Properties) {
    $definitionOps = $serviceOps.Value
    $containerOps = $definitionOps.container_name
    try {
        $inspectTextOps = & $DockerPath inspect $containerOps
        if ($LASTEXITCODE -ne 0) { throw 'container unavailable' }
        $inspectOps = ($inspectTextOps | ConvertFrom-Json)[0]
        if (-not $inspectOps.State.Running) { throw 'container is not running' }
        $imageTextOps = & $DockerPath image inspect $definitionOps.image
        if ($LASTEXITCODE -ne 0) { throw 'configured pinned image is unavailable locally' }
        $imageOps = ($imageTextOps | ConvertFrom-Json)[0]
        if ($imageOps.Id -ne $inspectOps.Image) { throw 'running image differs from configured pin' }
        $probeOps = @($definitionOps.healthcheck.test)
        if ($probeOps[0] -eq 'CMD-SHELL') {
            # compose config 保留 $$；创建容器时才还原为 $，手动探针做同样还原。
            $shellOps = $probeOps[1].Replace('$$', '$')
            $probeOutputOps = & $DockerPath exec $containerOps sh -c $shellOps 2>&1
        } elseif ($probeOps[0] -eq 'CMD') {
            $probeArgsOps = @('exec', $containerOps) + @($probeOps | Select-Object -Skip 1)
            $probeOutputOps = & $DockerPath @probeArgsOps 2>&1
        } else { throw 'functional healthcheck is missing' }
        if ($LASTEXITCODE -ne 0) { throw 'functional healthcheck failed' }
        Write-Output ('DEPENDENCY_PASS ' + $serviceOps.Name + ' pinned=true functional=true')
    } catch {
        $failedOps++
        Write-Output ('DEPENDENCY_FAIL ' + $serviceOps.Name + ' ' + $_.Exception.Message)
    }
}

# 使用已有本机 Maven 缓存，不下载依赖、不运行 Maven 或修改共享构建缓存。
$jarRootsOps = @('org/apache/rocketmq', 'io/github/aliyunmq', 'io/opentelemetry', 'io/opentracing', 'io/netty', 'com/alibaba/fastjson', 'com/google/guava', 'com/google/code/gson', 'org/apache/commons/commons-lang3', 'commons-validator/commons-validator', 'com/github/luben/zstd-jni', 'org/lz4/lz4-java', 'commons-codec/commons-codec', 'org/slf4j/slf4j-api')
$jarsOps = foreach ($jarRootOps in $jarRootsOps) {
    Get-ChildItem -LiteralPath (Join-Path $MavenRepository $jarRootOps) -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object FullName |
        Select-Object -ExpandProperty FullName
}
try {
    if (-not $jarsOps) { throw 'cached RocketMQ client dependencies are unavailable' }
    $classpathOps = $jarsOps -join [IO.Path]::PathSeparator
    $mqArgsOps = @('-Xms32m', '-Xmx128m', '-Dfile.encoding=UTF-8', '-Duser.language=en', '--class-path', $classpathOps, (Join-Path $PSScriptRoot 'MqDependencyProbe.java'), $NameServer)
    if ($WriteProbe) { $mqArgsOps += '--write' }
    & $JavaPath @mqArgsOps
    if ($LASTEXITCODE -ne 0) { throw 'host MQ protocol or round trip probe failed' }
} catch { $failedOps++; Write-Output ('DEPENDENCY_FAIL MQ_HOST ' + $_.Exception.Message) }
$oldAccessOps = [Environment]::GetEnvironmentVariable('STCLOUD_S3_ACCESS_KEY', 'Process')
$oldSecretOps = [Environment]::GetEnvironmentVariable('STCLOUD_S3_SECRET_KEY', 'Process')
try {
    # 使用 Compose 已解析的凭据验证同一份配置；只传进程环境，不写命令或日志。
    $env:STCLOUD_S3_ACCESS_KEY = $configOps.services.rustfs.environment.RUSTFS_ACCESS_KEY
    $env:STCLOUD_S3_SECRET_KEY = $configOps.services.rustfs.environment.RUSTFS_SECRET_KEY
    $s3ArgsOps = @((Join-Path $PSScriptRoot 's3-dependency-probe.py'), '--endpoint', $S3Endpoint, '--bucket', $S3Bucket)
    if ($WriteProbe) { $s3ArgsOps += '--write' }
    & $PythonPath @s3ArgsOps
    if ($LASTEXITCODE -ne 0) { throw 'authenticated S3 or round trip probe failed' }
} catch { $failedOps++; Write-Output ('DEPENDENCY_FAIL S3_HOST ' + $_.Exception.Message) }
finally {
    [Environment]::SetEnvironmentVariable('STCLOUD_S3_ACCESS_KEY', $oldAccessOps, 'Process')
    [Environment]::SetEnvironmentVariable('STCLOUD_S3_SECRET_KEY', $oldSecretOps, 'Process')
}

if ($failedOps -gt 0) { Write-Output ('DEPENDENCIES_FAILED count=' + $failedOps); exit 1 }
Write-Output ('DEPENDENCIES_PASS writeProbe=' + [bool]$WriteProbe)
exit 0
