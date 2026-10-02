#requires -Version 5.1
<#
.SYNOPSIS
  校验当前定义和指定 State；全量历史审计需要 -AuditHistory。
#>
[CmdletBinding()]
param([string]$Root, [string[]]$StatePath = @(), [switch]$AuditHistory, [string]$BaseRevision)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path $PSScriptRoot -Parent }
$repoRoot = Split-Path $Root -Parent
$gitRoot = $repoRoot.Replace('\','/')
$script:failures = 0
function Check([bool]$Ok, [string]$Message) {
  if ($Ok) { Write-Host "[PASS] $Message" }
  else { Write-Host "[FAIL] $Message"; $script:failures++ }
}
$scales=[ordered]@{}; $catalog=@{}; $section=''; $scale=''; $node=$null; $entry=''
foreach ($line in Get-Content -LiteralPath (Join-Path $Root 'loop/exit-criteria.yaml') -Encoding UTF8) {
  if ($line -match '^scales:') { $section='scales'; continue }
  if ($line -match '^catalog:') { $section='catalog'; continue }
  if ($line -match '^[A-Za-z]') { $section='other'; continue }
  if ($section -eq 'scales') {
    if ($line -match '^  (small|medium|large):') { $scale=$Matches[1]; $scales[$scale]=@(); continue }
    if ($line -match '^      - id: ([A-Z_]+)') { $node=@{id=$Matches[1];deps=@()}; $scales[$scale]+=,$node; continue }
    if ($line -match '^        dependsOn: \[(.*)\]') { $node.deps=@($Matches[1] -split ',' | ForEach-Object {$_.Trim()} | Where-Object {$_}); continue }
  }
  if ($section -eq 'catalog') {
    if ($line -match '^  ([A-Z_]+):') { $entry=$Matches[1]; $catalog[$entry]=@{owner=''}; continue }
    if ($line -match '^    owner: (\w+)') { $catalog[$entry].owner=$Matches[1] }
  }
}
Check ($scales.Count -eq 3) '规模定义完整'
foreach ($name in $scales.Keys) {
  $nodes=$scales[$name]; $ids=@($nodes | ForEach-Object {$_.id})
  Check (@($ids | Group-Object | Where-Object Count -gt 1).Count -eq 0) "$name ID 唯一"
  foreach ($item in $nodes) {
    Check ($catalog.ContainsKey($item.id) -and $catalog[$item.id].owner -in @('executor','reviewer','tester')) "$name/$($item.id) catalog 归属"
    foreach ($dep in $item.deps) { Check ($ids -contains $dep -and $dep -ne $item.id) "$name/$($item.id) 依赖 $dep 有效" }
  }
  $allDeps=@($nodes | ForEach-Object {$_.deps} | Sort-Object -Unique)
  $leaves=@($ids | Where-Object {$allDeps -notcontains $_})
  Check ($leaves.Count -eq 1 -and $leaves[0] -eq 'ACCEPT') "$name 最终收敛为 ACCEPT"
  $resolved=@()
  do {
    $before=$resolved.Count
    foreach ($item in $nodes) {
      if ($resolved -contains $item.id) { continue }
      if (@($item.deps | Where-Object {$resolved -notcontains $_}).Count -eq 0) { $resolved+=,$item.id }
    }
  } while ($resolved.Count -gt $before)
  Check ($resolved.Count -eq $ids.Count) "$name DAG 无环"
}
$large=$scales['large']
$review=@($large | Where-Object {$_.id -eq 'CODE_REVIEW'})
$verified=@($large | Where-Object {$_.id -eq 'VERIFIED'})
$accept=@($large | Where-Object {$_.id -eq 'ACCEPT'})
Check ($review.Count -eq 1 -and $catalog['CODE_REVIEW'].owner -eq 'reviewer') '大型独立评审保留'
Check ($verified.Count -eq 1 -and $verified[0].deps -contains 'IMPLEMENTED' -and $verified[0].deps -notcontains 'CODE_REVIEW') '测试在实现后即可推进'
Check ($accept.Count -eq 1 -and $accept[0].deps -contains 'CODE_REVIEW' -and $accept[0].deps -contains 'VERIFIED') '大型验收汇合评审与测试'
$schema=Get-Content -LiteralPath (Join-Path $Root 'schema/loop-state.schema.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Check (@($schema.properties.definitionVersion.enum) -contains 3 -and @($schema.properties.definitionVersion.enum) -contains 2) 'State 支持 V3/V2'
Check (Test-Path -LiteralPath (Join-Path $Root 'loop/exit-criteria.v2.yaml')) '冻结 V2 定义存在'

# 日常门禁校验本次变更的 State；CI 使用事件的 base SHA，避免干净 checkout 漏检。
if (-not $AuditHistory -and $StatePath.Count -eq 0) {
  if (-not $BaseRevision -and $env:GITHUB_EVENT_PATH -and (Test-Path -LiteralPath $env:GITHUB_EVENT_PATH)) {
    $event = Get-Content -LiteralPath $env:GITHUB_EVENT_PATH -Raw | ConvertFrom-Json
    $candidate = if ($event.pull_request) { $event.pull_request.base.sha } else { $event.before }
    if ($candidate -match '^[0-9a-fA-F]{40}$' -and $candidate -notmatch '^0+$') { $BaseRevision = $candidate }
  }
  $changed = @()
  foreach ($gitArguments in @(@('diff','--name-only','--','.ai/state'),@('diff','--cached','--name-only','--','.ai/state'),@('ls-files','--others','--exclude-standard','--','.ai/state'))) {
    $found = @(& git -c "safe.directory=$gitRoot" -C $gitRoot @gitArguments 2>&1)
    if ($LASTEXITCODE -ne 0) { Check $false '无法枚举当前变更 State；显式传入 -StatePath'; continue }
    $changed += $found
  }
  if ($BaseRevision) {
    $found = @(& git -c "safe.directory=$gitRoot" -C $gitRoot diff --name-only $BaseRevision HEAD -- .ai/state 2>&1)
    if ($LASTEXITCODE -ne 0) { Check $false '无法读取 State 基线差异' } else { $changed += $found }
  } elseif ($env:GITHUB_ACTIONS -eq 'true') {
    # 首次推送无 base；只检查当前结构版本，不读取旧协议。
    $changed += @(& git -c "safe.directory=$gitRoot" -C $gitRoot ls-files -- .ai/state/*.yaml .ai/state/*.json)
  }
  $StatePath = @($changed | Where-Object { $_ -match '^\.ai/state/.+\.(yaml|json)$' -and (Test-Path -LiteralPath (Join-Path $repoRoot $_)) } | Sort-Object -Unique)
}

# 默认只读取当前路由；历史任务不成为每次修改的前置扫描。
$core=@('AGENTS.md','.ai/agents/workflow-manager.md','.ai/knowledge/agent-loop-runbook.md',
 '.ai/knowledge/loop-state-model.md','.ai/knowledge/agent-dispatch-protocol.md',
 '.ai/knowledge/document-management.md','.ai/knowledge/loop-verification-checklist.md',
 '.ai/knowledge/role-context.md','.ai/knowledge/agent-output-standard.md',
 '.ai/workflows/feature-development.md','.ai/templates/task-template.md',
 '.ai/knowledge/conventions.md','.ai/knowledge/skill-mapping.md','.codex/agent-skills.md')
if ($AuditHistory) {
  $core+=@(Get-ChildItem (Join-Path $Root 'knowledge'),(Join-Path $Root 'templates'),(Join-Path $Root 'tasks') -Recurse -File -Filter *.md | ForEach-Object {$_.FullName})
  $StatePath+=@(Get-ChildItem (Join-Path $Root 'state') -File | Where-Object {$_.Extension -in @('.yaml','.json')} | ForEach-Object {$_.FullName})
}
foreach ($file in $core | Select-Object -Unique) {
  $path=if ([IO.Path]::IsPathRooted($file)) {$file} else {Join-Path $repoRoot $file}
  Check (Test-Path -LiteralPath $path -PathType Leaf) "入口：$file"
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
  $raw=Get-Content -LiteralPath $path -Raw -Encoding UTF8
  if ($raw -match '(?m)^\s*>\s*\*\*\[回填标注') { continue }
  foreach ($line in $raw -split '\r?\n') {
    if ($line -match '\[planned-output\]') { continue }
    foreach ($match in [regex]::Matches($line,'\.ai/[A-Za-z0-9_./-]+\.(?:md|ps1|json|yaml)')) {
      if ($match.Value -match 'xxx') { continue }
      Check (Test-Path -LiteralPath (Join-Path $repoRoot $match.Value)) "引用：$($match.Value)"
    }
  }
}
foreach ($file in $StatePath | Select-Object -Unique) {
  $path=if ([IO.Path]::IsPathRooted($file)) {$file} else {Join-Path $repoRoot $file}
  if ($AuditHistory -and (Test-Path -LiteralPath $path) -and (Get-Content -LiteralPath $path -Raw).TrimStart() -notlike '{*') {
    Write-Host "[INFO] 非 V2/V3 历史 State 仅供审计：$file"; continue
  }
  $result=@(& (Join-Path $Root 'scripts/loopctl.ps1') validate $path 2>&1 | ForEach-Object {$_.ToString()})
  Check ($LASTEXITCODE -eq 0) "State $file：$($result -join ' ')"
}
Write-Host "FAIL=$script:failures"
if ($script:failures) { exit 1 }; exit 0
