#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
  [string]$StateDirectory,
  [string]$ArchiveDirectory,
  [switch]$Apply,
  [string]$FailAfterArchiveForTaskId
)

$ErrorActionPreference = 'Stop'
$aiRoot = Split-Path $PSScriptRoot -Parent
if (-not $StateDirectory) { $StateDirectory = Join-Path $aiRoot 'state' }
if (-not $ArchiveDirectory) { $ArchiveDirectory = Join-Path $aiRoot 'archive\state-v1' }
$definitionPath = Join-Path $aiRoot 'loop\exit-criteria.yaml'
$loopctl = Join-Path $PSScriptRoot 'loopctl.ps1'

function Read-Scalar([string]$Raw, [string]$Name, [string]$Default) {
  $pattern = '(?m)^{0}:\s*[''"]?([^\r\n''"]+)' -f [regex]::Escape($Name)
  $match = [regex]::Match($Raw, $pattern)
  if ($match.Success) { return $match.Groups[1].Value.Trim() }
  return $Default
}

function Read-GoalObjective([string]$Raw, [string]$Fallback) {
  $match = [regex]::Match($Raw, '(?ms)^goal:\s*\r?\n(?:^[ \t]+.*\r?\n)*?^[ \t]+objective:\s*["'']?([^\r\n"'']+)')
  if ($match.Success) { return $match.Groups[1].Value.Trim() }
  return $Fallback
}

function Read-CanonicalCriteria([string]$Scale) {
  $ids = @(); $inScale = $false
  foreach ($line in Get-Content -LiteralPath $definitionPath -Encoding UTF8) {
    if ($line -match '^  (small|medium|large):\s*$') { $inScale = $Matches[1] -eq $Scale; continue }
    if ($inScale -and $line -match '^      - id:\s*([A-Z][A-Z_]*)') { $ids += $Matches[1] }
    if ($inScale -and $line -match '^catalog:\s*$') { break }
  }
  return $ids
}

function Read-Dependencies([string]$Scale, [string]$Id) {
  $lines = Get-Content -LiteralPath $definitionPath -Encoding UTF8
  $inScale = $false; $inCriterion = $false
  foreach ($line in $lines) {
    if ($line -match '^  (small|medium|large):\s*$') { $inScale = $Matches[1] -eq $Scale; $inCriterion = $false; continue }
    if ($inScale -and $line -match '^      - id:\s*([A-Z][A-Z_]*)') { $inCriterion = $Matches[1] -eq $Id; continue }
    if ($inCriterion -and $line -match '^        dependsOn:\s*\[(.*)\]') { return @($Matches[1].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
  }
  return @()
}

$plans = @()
foreach ($file in Get-ChildItem -LiteralPath $StateDirectory -Filter '*.yaml' -File | Sort-Object Name) {
  $raw = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
  if ($raw.TrimStart().StartsWith('{')) {
    try { $json = $raw | ConvertFrom-Json } catch { continue }
    if ($json.schemaVersion -eq 2) { continue }
  }
  $status = Read-Scalar $raw 'status' ''
  if ($status -notin @('running','incomplete','blocked_escalation')) { continue }
  $scale = Read-Scalar $raw 'scale' 'medium'
  if ($scale -notin @('small','medium','large')) { $scale = 'medium' }
  $taskId = Read-Scalar $raw 'taskId' ([IO.Path]::GetFileNameWithoutExtension($file.Name))
  $objective = Read-GoalObjective $raw "继续并重新验证迁移自 $($file.Name) 的未完成任务"
  $archivePath = Join-Path $ArchiveDirectory $file.Name
  $criteria = @(Read-CanonicalCriteria $scale | ForEach-Object { [pscustomobject][ordered]@{ id=$_; status='pending'; dependsOn=@(Read-Dependencies $scale $_) } })
  $state = [pscustomobject][ordered]@{
    schemaVersion=2; definitionVersion=2; taskId=$taskId; scale=$scale; status=$(if($status -eq 'incomplete'){'incomplete'}else{'running'})
    goal=[pscustomobject][ordered]@{ objective=$objective; scope="从 $($file.Name) 迁移；原始 State 见 .ai/archive/state-v1/$($file.Name)"; completionCriteria=@('恢复原未完成任务，并使用 Agent Loop V2 的当前门禁重新验收') }
    revision=[pscustomobject]@{ design=$null; code=$null }; exitCriteria=$criteria; artifacts=[pscustomobject]@{}; blockers=@(); acceptanceEvidence=@(); dispatchLedger=@(); proposals=@()
    history=@([pscustomobject][ordered]@{ eventId="migration-v1-v2-$taskId"; action='migrate-v1-to-v2'; actor='workflow-manager'; occurredAt=[DateTime]::UtcNow.ToString('o'); revision=[pscustomobject]@{design=$null;code=$null}; sourceRef=".ai/archive/state-v1/$($file.Name)" })
  }
  $plans += [pscustomobject]@{ Source=$file.FullName; Archive=$archivePath; State=$state }
}

if (-not $Apply) {
  $plans | ForEach-Object { "DRY_RUN source=$($_.Source) archive=$($_.Archive) taskId=$($_.State.taskId) scale=$($_.State.scale)" }
  exit 0
}
if ($plans.Count -eq 0) { 'UNCHANGED'; exit 0 }

foreach ($plan in $plans) {
  if (Test-Path -LiteralPath $plan.Archive) { throw "MIGRATION_ARCHIVE_EXISTS: $($plan.Archive)" }
}
if (-not $PSCmdlet.ShouldProcess("$($plans.Count) State", '迁移到 V2 并归档 V1')) { exit 0 }

$prepared = @(); $archived = @(); $migrated = @()
try {
  New-Item -ItemType Directory -Path $ArchiveDirectory -Force | Out-Null
  foreach ($plan in $plans) {
    $temp = "$($plan.Source).v2.$([Guid]::NewGuid().ToString('N')).tmp"
    $plan.State | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $temp -Encoding utf8NoBOM
    & pwsh -NoProfile -File $loopctl validate $temp
    if ($LASTEXITCODE -ne 0) { throw "MIGRATION_VALIDATION_FAILED: $($plan.Source)" }
    $prepared += [pscustomobject]@{ Plan=$plan; Temp=$temp }
  }
  foreach ($item in $prepared) {
    Copy-Item -LiteralPath $item.Plan.Source -Destination $item.Plan.Archive
    $archived += $item
    if ($FailAfterArchiveForTaskId -eq [string]$item.Plan.State.taskId) { throw "MIGRATION_FAULT_INJECTED: $FailAfterArchiveForTaskId" }
    Move-Item -LiteralPath $item.Temp -Destination $item.Plan.Source -Force
    $migrated += $item
    "MIGRATED source=$($item.Plan.Source) archive=$($item.Plan.Archive)"
  }
} catch {
  foreach ($item in @($migrated)) {
    if (Test-Path -LiteralPath $item.Plan.Archive) { Copy-Item -LiteralPath $item.Plan.Archive -Destination $item.Plan.Source -Force }
  }
  foreach ($item in @($archived)) { if (Test-Path -LiteralPath $item.Plan.Archive) { Remove-Item -LiteralPath $item.Plan.Archive -Force } }
  throw
} finally {
  foreach ($item in @($prepared)) { if (Test-Path -LiteralPath $item.Temp) { Remove-Item -LiteralPath $item.Temp -Force } }
}
