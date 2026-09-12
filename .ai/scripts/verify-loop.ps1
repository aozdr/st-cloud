#requires -Version 5.1
<#
.SYNOPSIS
  Agent Loop 静态校验脚本。
.DESCRIPTION
  以 .ai/loop/exit-criteria.yaml 为唯一事实源，校验：
    1. 退出标准定义自身（项数 / 依赖图无环 / 终点唯一为 ACCEPT / 起点唯一 / Agent 归属）
    2. 关键门禁 dependsOn 映射（等价旧版禁止项）
    3. 文档口径一致（三份文档声明的项数必须等于定义文件）
    4. State 诚信（产物 ref 存在 / done 前置 / 角色分离 / 僵尸任务）
    5. cross-ref 路径存在（带「回填标注」的文件跳过）
    6. 残留权威线性表述（WARN 供人工确认）
    7. Task / ADR / hook / 技能映射配置一致性
  退出码：0 = 全过；1 = 有 FAIL。
.NOTES
  对应 .ai/knowledge/loop-verification-checklist.md 的「一、静态校验」。
  预提交门禁：.ai/githooks/pre-commit（安装见 .ai/scripts/install-hooks.ps1）
#>
[CmdletBinding()]
param(
  [string]$Root
)

# $PSScriptRoot 在 param 默认值中尚未定义，移到体内求值
if (-not $Root) { $Root = Split-Path $PSScriptRoot -Parent }
$RepoRootPath = Split-Path $Root -Parent

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:FailCount = 0
$script:WarnCount = 0

function Write-Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:FailCount++ }
function Write-Warn2($msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow; $script:WarnCount++ }

function Split-List([string]$s) {
  if ($null -eq $s) { return @() }
  $t = $s.Trim()
  if ($t -eq '') { return @() }
  return @($t -split ',' | ForEach-Object { $_.Trim().Trim('"') } | Where-Object { $_ -ne '' })
}

function Test-IsPathRef([string]$r) {
  if ([string]::IsNullOrWhiteSpace($r)) { return $false }
  if ($r -notlike '.ai/*') { return $false }
  if ($r -match '[\s~+]') { return $false }
  return ($r -match '\.(md|yaml|yml|ps1|py)$')
}

Write-Host "Agent Loop 静态校验" -ForegroundColor Cyan
Write-Host "根目录: $Root`n"

# ---------- 载入唯一事实源 ----------
$defPath = Join-Path $Root 'loop\exit-criteria.yaml'
if (-not (Test-Path $defPath)) { Write-Fail "找不到 $defPath（退出标准唯一定义）"; exit 1 }
$defLines = Get-Content $defPath -Encoding UTF8

$scales = [ordered]@{}
$catalog = [ordered]@{}
$sod = @{ producers = @(); verifiers = @() }
$allowedStatus = @()
$section = 'top'
$curScale = $null
$curCrit = $null
$curCatalog = $null

foreach ($raw in $defLines) {
  $line = ($raw -replace "`t", '    ')
  if ($line -match '^\s*#') { continue }
  if ($line -match '^scales:\s*$') { $section = 'scales'; continue }
  if ($line -match '^catalog:\s*$') { $section = 'catalog'; continue }
  if ($line -match '^separationOfDuty:\s*$') { $section = 'sod'; continue }
  if ($line -match '^stateStatus:\s*$') { $section = 'stateStatus'; continue }
  if ($line -match '^(version|updated):') { continue }

  switch ($section) {
    'scales' {
      if ($line -match '^  (large|medium|small):\s*$') {
        $curScale = $Matches[1]
        $scales[$curScale] = @{ label = ''; docs = @{}; criteria = @() }
        continue
      }
      if ($curScale -and $line -match '^    label:\s*"?(.+?)"?\s*$') { $scales[$curScale].label = $Matches[1]; continue }
      if ($curScale -and $line -match '^      (stateModel|workflow|checklist):\s*"(.+)"\s*$') {
        $scales[$curScale].docs[$Matches[1]] = $Matches[2]; continue
      }
      if ($curScale -and $line -match '^      - id:\s*(\S+)\s*$') {
        $curCrit = @{ id = $Matches[1]; deps = @(); skippable = $false }
        $scales[$curScale].criteria += , $curCrit
        continue
      }
      if ($curScale -and $curCrit -and $line -match '^        dependsOn:\s*\[(.*)\]\s*$') {
        $curCrit.deps = Split-List $Matches[1]; continue
      }
      if ($curScale -and $curCrit -and $line -match '^        skippable:\s*(true|false)\s*$') {
        $curCrit.skippable = ($Matches[1] -eq 'true'); continue
      }
    }
    'catalog' {
      if ($line -match '^  ([A-Z][A-Z_]*):\s*$') {
        $curCatalog = $Matches[1]
        $catalog[$curCatalog] = @{ desc = ''; owner = ''; taskType = ''; artifacts = @(); grill = $false; userConfirm = $false; conditional = $false }
        continue
      }
      if ($curCatalog -and $line -match '^    desc:\s*"?(.+?)"?\s*$') { $catalog[$curCatalog].desc = $Matches[1]; continue }
      if ($curCatalog -and $line -match '^    owner:\s*(\S+)\s*$') { $catalog[$curCatalog].owner = $Matches[1]; continue }
      if ($curCatalog -and $line -match '^    taskType:\s*(\S+)\s*$') { $catalog[$curCatalog].taskType = $Matches[1]; continue }
      if ($curCatalog -and $line -match '^    artifacts:\s*\[(.*)\]\s*$') { $catalog[$curCatalog].artifacts = Split-List $Matches[1]; continue }
      if ($curCatalog -and $line -match '^    (grill|userConfirm|conditional):\s*(true|false)\s*$') {
        $catalog[$curCatalog][$Matches[1]] = ($Matches[2] -eq 'true'); continue
      }
    }
    'sod' {
      if ($line -match '^  producers:\s*\[(.*)\]\s*$') { $sod.producers = Split-List $Matches[1]; continue }
      if ($line -match '^  verifiers:\s*\[(.*)\]\s*$') { $sod.verifiers = Split-List $Matches[1]; continue }
    }
    'stateStatus' {
      if ($line -match '^  allowed:\s*\[(.*)\]\s*$') { $allowedStatus = Split-List $Matches[1]; continue }
    }
  }
}

# ---------- 1. 退出标准定义 ----------
Write-Host "[1/7] 退出标准定义（.ai/loop/exit-criteria.yaml）"
$expectedCounts = @{ large = 12; medium = 8; small = 4 }
if ($scales.Count -ne 3) {
  Write-Fail "解析到 $($scales.Count) 个规模档，预期 3（large/medium/small）"
} else {
  Write-Pass "解析到 3 个规模档：$(($scales.Keys) -join ' / ')"
}

foreach ($s in $scales.Keys) {
  $crits = $scales[$s].criteria
  $want = $expectedCounts[$s]
  if ($crits.Count -eq $want) { Write-Pass "$s 定义 $($crits.Count) 项退出标准" }
  else { Write-Fail "$s 定义 $($crits.Count) 项，预期 $want 项" }

  # id 唯一 + 在 catalog 中
  $ids = @($crits | ForEach-Object { $_.id })
  $dup = @($ids | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
  if (@($dup).Count -eq 0) { Write-Pass "$s 标准 id 无重复" } else { Write-Fail "$s 标准 id 重复：$($dup -join ', ')" }
  foreach ($id in $ids) {
    if (-not $catalog.Contains($id)) { Write-Fail "$s 的标准 $id 未在 catalog 中定义" }
  }

  # 依赖解析
  foreach ($c in $crits) {
    foreach ($d in $c.deps) {
      if ($ids -notcontains $d) { Write-Fail "$s 的 $($c.id) 依赖未知节点 $d" }
      if ($d -eq $c.id) { Write-Fail "$s 的 $($c.id) 自依赖" }
    }
  }

  # 起点 / 终点
  $allDeps = @($crits | ForEach-Object { $_.deps } | Sort-Object -Unique)
  $leaves = @($ids | Where-Object { $allDeps -notcontains $_ })
  if ($leaves.Count -eq 1 -and $leaves[0] -eq 'ACCEPT') { Write-Pass "$s 终点唯一：ACCEPT（最终收敛点）" }
  else { Write-Fail "$s 终点应为 [ACCEPT]，实际为 [$($leaves -join ', ')]" }
  $roots = @($crits | Where-Object { $_.deps.Count -eq 0 } | ForEach-Object { $_.id })
  if ($roots.Count -eq 1) { Write-Pass "$s 起点唯一：$($roots[0])" }
  else { Write-Fail "$s 起点应为 1 个，实际为 [$($roots -join ', ')]" }

  # 环检测（Kahn）
  $inDeg = @{}
  foreach ($c in $crits) { $inDeg[$c.id] = $c.deps.Count }
  $queue = [System.Collections.Generic.Queue[string]]::new()
  foreach ($k in @($inDeg.Keys)) { if ($inDeg[$k] -eq 0) { $queue.Enqueue($k) } }
  $processed = 0
  while ($queue.Count -gt 0) {
    $n = $queue.Dequeue(); $processed++
    foreach ($c in $crits) {
      if ($c.deps -contains $n) {
        $inDeg[$c.id]--
        if ($inDeg[$c.id] -eq 0) { $queue.Enqueue($c.id) }
      }
    }
  }
  if ($processed -eq $crits.Count) { Write-Pass "$s 依赖图无环（拓扑处理 $processed/$($crits.Count)）" }
  else { Write-Fail "$s 依赖图存在环，仅处理 $processed/$($crits.Count) 节点" }

  # 条件项统计
  $cond = @($crits | Where-Object { $_.skippable })
  if ($cond.Count -gt 0) { Write-Pass "$s 含条件项 $($cond.Count) 项：$(($cond | ForEach-Object { $_.id }) -join ', ')" }
}

$largeSecurity = @($scales['large'].criteria | Where-Object id -eq 'SECURITY_REVIEW')
$mediumSecurity = @($scales['medium'].criteria | Where-Object id -eq 'SECURITY_REVIEW')
if ($largeSecurity.Count -eq 1 -and -not $largeSecurity[0].skippable) { Write-Pass 'large SECURITY_REVIEW 必选' }
else { Write-Fail 'large SECURITY_REVIEW 不得标记 skippable' }
if ($mediumSecurity.Count -eq 1 -and $mediumSecurity[0].skippable) { Write-Pass 'medium SECURITY_REVIEW 可凭证据跳过' }
else { Write-Fail 'medium SECURITY_REVIEW 应标记 skippable' }

# Agent 归属（catalog owner 必填）
$noOwner = @($catalog.Keys | Where-Object { [string]::IsNullOrWhiteSpace($catalog[$_].owner) })
if (@($noOwner).Count -eq 0) { Write-Pass "catalog 全部 $($catalog.Count) 项均有 owner 归属" }
else { Write-Fail "catalog 缺 owner：$($noOwner -join ', ')" }
$usedIds = @($scales.Keys | ForEach-Object { $scales[$_].criteria } | ForEach-Object { $_.id })
$unused = @($catalog.Keys | Where-Object { $usedIds -notcontains $_ })
if (@($unused).Count -gt 0) { Write-Warn2 "catalog 中未被任何规模档引用的标准：$($unused -join ', ')" }

# ---------- 2. 关键门禁 dependsOn 映射 ----------
Write-Host "`n[2/7] 关键门禁 dependsOn 映射（等价旧版禁止项）"
$largeMap = @{}
foreach ($c in $scales['large'].criteria) { $largeMap[$c.id] = $c.deps }
$expected = @(
  @{ Rule = 'TECH_DESIGN 依赖 IMPACT_ANALYSIS+EXP_DESIGN'; Id = 'TECH_DESIGN'; Need = @('IMPACT_ANALYSIS', 'EXP_DESIGN') },
  @{ Rule = 'IMPLEMENTED 依赖 TECH_DESIGN+TESTCASES'; Id = 'IMPLEMENTED'; Need = @('TECH_DESIGN', 'TESTCASES') },
  @{ Rule = 'CODE_REVIEW 依赖 IMPLEMENTED'; Id = 'CODE_REVIEW'; Need = @('IMPLEMENTED') },
  @{ Rule = 'SECURITY_REVIEW 依赖 IMPLEMENTED'; Id = 'SECURITY_REVIEW'; Need = @('IMPLEMENTED') },
  @{ Rule = 'TEST_PASS 依赖 CODE_REVIEW+SECURITY_REVIEW'; Id = 'TEST_PASS'; Need = @('CODE_REVIEW', 'SECURITY_REVIEW') },
  @{ Rule = 'KNOWLEDGE 依赖 TEST_PASS+SECURITY_REVIEW+EXP_ACCEPT'; Id = 'KNOWLEDGE'; Need = @('TEST_PASS', 'SECURITY_REVIEW', 'EXP_ACCEPT') },
  @{ Rule = 'ACCEPT 依赖 KNOWLEDGE（验收为最终收敛点）'; Id = 'ACCEPT'; Need = @('KNOWLEDGE') }
)
foreach ($e in $expected) {
  $ok = $true
  foreach ($n in $e.Need) { if ($largeMap[$e.Id] -notcontains $n) { $ok = $false } }
  if ($ok) { Write-Pass $e.Rule } else { Write-Fail "$($e.Rule) | 实际依赖: [$($largeMap[$e.Id] -join ', ')]" }
}

# ---------- 3. 文档口径一致 ----------
Write-Host "`n[3/7] 文档口径一致（文档声明的项数必须等于定义文件）"
$docFiles = @{
  stateModel = 'knowledge\loop-state-model.md'
  workflow   = 'workflows\feature-development.md'
  checklist  = 'knowledge\loop-verification-checklist.md'
}
foreach ($s in $scales.Keys) {
  $want = $scales[$s].criteria.Count
  foreach ($k in $docFiles.Keys) {
    $phrase = $scales[$s].docs[$k]
    $rel = $docFiles[$k]
    $full = Join-Path $Root $rel
    if (-not (Test-Path $full)) { Write-Fail "缺少 $rel"; continue }
    if ([string]::IsNullOrWhiteSpace($phrase)) { Write-Fail "$s 未在定义文件中声明 $k 口径短语"; continue }
    $txt = Get-Content $full -Encoding UTF8 -Raw
    if ($txt -like "*$phrase*") {
      if ($phrase -match '(\d+)') {
        $num = [int]$Matches[1]
        if ($num -eq $want) { Write-Pass "$rel 口径一致：$phrase" }
        else { Write-Fail "$rel 口径不一致：'$phrase' 声明 $num 项，定义为 $want 项" }
      } else { Write-Pass "$rel 含口径短语：$phrase" }
    } else {
      Write-Fail "$rel 缺少口径短语：'$phrase'"
    }
  }
}

# ---------- 4. State 诚信 ----------
Write-Host "`n[4/7] State 诚信（产物存在 / done 前置 / 角色分离 / 僵尸任务）"
$stateDir = Join-Path $Root 'state'
$stateFiles = @(Get-ChildItem $stateDir -File -Filter *.yaml -ErrorAction SilentlyContinue)
if ($stateFiles.Count -eq 0) { Write-Warn2 "未发现 State 文件" }
$today = Get-Date
$staleDays = 14
$doneCount = 0
foreach ($sf in $stateFiles) {
  $lines = Get-Content $sf.FullName -Encoding UTF8
  if (($lines -join "`n") -match '(?m)^\s*["'']?schemaVersion["'']?\s*:\s*2\s*[,}]?') {
    $loopCtl = Join-Path $Root 'scripts\loopctl.ps1'
    $v2Output = @(& $loopCtl validate $sf.FullName 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -eq 0) {
      Write-Pass "$($sf.Name) V2 严格校验通过"
      $v2State = Get-Content -LiteralPath $sf.FullName -Encoding UTF8 -Raw | ConvertFrom-Json
      if ($v2State.status -eq 'done') { $doneCount++ }
    }
    else { Write-Fail "$($sf.Name) V2 严格校验失败：$($v2Output -join ' ')" }
    # V2 已由正式解析器完成校验；旧版正则解析器不支持 JSON-compatible YAML。
    continue
  }
  $st = $null; $iter = $null; $critStatus = @{}; $critBy = @{}; $arts = @{}
  $legacy = $false
  $sec = ''
  $curId = $null
  $curArt = $null
  foreach ($ln in $lines) {
    if ($ln -match '^([A-Za-z_]+):') {
      $key = $Matches[1]
      if ($key -eq 'exitCriteria') { $sec = 'crit' }
      elseif ($key -eq 'artifacts') { $sec = 'art' }
      elseif ($key -eq 'history') { $sec = 'hist' }
      else { $sec = 'top' }
      if ($key -eq 'status') { $st = ($ln -replace '^status:\s*', '').Trim() }
      if ($key -eq 'iteration') { $iter = ($ln -replace '^iteration:\s*', '').Trim() }
      if ($key -eq 'legacy') { $legacy = (($ln -replace '^legacy:\s*', '').Trim() -eq 'true') }
      continue
    }
    if ($sec -eq 'crit') {
      # 流式：- { id: X, desc: "...", status: done, dependsOn: [...] }
      if ($ln -match '^\s*-\s*\{(.*)\}\s*$') {
        $body = $Matches[1]
        if ($body -match 'id:\s*"?([A-Za-z_]+)"?') {
          $fid = $Matches[1]
          if ($body -match 'status:\s*"?([A-Za-z_]+)"?') { $critStatus[$fid] = $Matches[1] }
          if ($body -match 'by:\s*"?([^",}]+)"?') { $critBy[$fid] = $Matches[1].Trim().Trim('"') }
        }
        continue
      }
      # 块式：- id: X 换行 status: y
      if ($ln -match '^\s*-\s*id:\s*"?([A-Za-z_]+)"?') { $curId = $Matches[1]; continue }
      if ($curId -and $ln -match '^\s+status:\s*"?([A-Za-z_]+)"?') { $critStatus[$curId] = $Matches[1]; continue }
      if ($curId -and $ln -match '^\s+by:\s*"?([^"\r\n]+)"?') { $critBy[$curId] = $Matches[1].Trim().Trim('"'); continue }
    }
    if ($sec -eq 'art') {
      # 流式：key: { status: done, ref: "...", ... }
      if ($ln -match '^\s{2}([A-Za-z_]+):\s*\{(.*)\}\s*$') {
        $arts[$Matches[1]] = $Matches[2]
        $curArt = $null
        continue
      }
      # 块式：key: 换行 status/ref/userConfirmedAt
      if ($ln -match '^\s{2}([A-Za-z_]+):\s*$') {
        $curArt = $Matches[1]
        $arts[$curArt] = ''
        continue
      }
      if ($curArt) {
        if ($ln -match '^\s+status:\s*(\S+)') { $arts[$curArt] += " status:$($Matches[1])"; continue }
        if ($ln -match '^\s+ref:\s*"([^"]*)"') { $arts[$curArt] += ' ref:"' + $Matches[1] + '"'; continue }
        if ($ln -match '^\s+userConfirmedAt:\s*(\S+)') { $arts[$curArt] += " userConfirmedAt:$($Matches[1])"; continue }
      }
    }
  }

  $name = $sf.Name
  if ([string]::IsNullOrWhiteSpace($st)) { Write-Fail "$name 缺少顶层 status"; continue }
  if ($st -eq 'done') { $doneCount++ }
  if ($allowedStatus -notcontains $st) { Write-Fail "$name status='$st' 不在允许取值 [$($allowedStatus -join ', ')] 内" }

  # 产物 ref 存在性：status=done 表示"已落盘"，此时 ref 必须指向真实文件
  foreach ($k in $arts.Keys) {
    $body = $arts[$k]
    $aStatus = if ($body -match 'status:\s*([A-Za-z_]+)') { $Matches[1] } else { '' }
    if ($aStatus -ne 'done') { continue }
    if ($body -match 'ref:\s*"([^"]*)"') {
      $ref = $Matches[1]
      if (Test-IsPathRef $ref) {
        if (-not (Test-Path (Join-Path $RepoRootPath $ref))) { Write-Fail "$name artifact '$k' 标记 done 但文件不存在：$ref" }
      }
    }
  }

  if ($st -eq 'done') {
    $notDone = @($critStatus.Keys | Where-Object { $critStatus[$_] -ne 'done' })
    if ($critStatus.Count -eq 0) {
      if ($legacy) { Write-Pass "$name 历史 State（legacy=true，无 exitCriteria），跳过 done 前置校验" }
      else { Write-Fail "$name status=done 但未解析到 exitCriteria（历史任务请显式标注 legacy: true）" }
    }
    elseif (@($notDone).Count -gt 0) { Write-Fail "$name status=done 但存在未达标标准：$($notDone -join ', ')" }
    else { Write-Pass "$name done 前置满足（$($critStatus.Count) 项标准全 done）" }

    # 确认型产物必须有用户确认时间
    foreach ($k in @('prd', 'design')) {
      if (-not $arts.ContainsKey($k)) { continue }
      $body = $arts[$k]
      $aStatus = if ($body -match 'status:\s*([A-Za-z_]+)') { $Matches[1] } else { '' }
      if ($aStatus -ne 'done') { continue }
      if ($body -match 'userConfirmedAt:\s*null') { Write-Fail "$name 的确认型产物 '$k' 已 done 但 userConfirmedAt 为空" }
    }
  }

  # 角色分离（仅对含 by 字段的 State 强制；历史 State 无 by 字段则告警）
  if ($critBy.Count -gt 0) {
    $prodBy = @($sod.producers | ForEach-Object { if ($critBy.ContainsKey($_)) { $critBy[$_] } } | Where-Object { $_ })
    $verBy = @($sod.verifiers | ForEach-Object { if ($critBy.ContainsKey($_)) { $critBy[$_] } } | Where-Object { $_ })
    $overlap = @($prodBy | Where-Object { $verBy -contains $_ } | Sort-Object -Unique)
    if (@($overlap).Count -gt 0) { Write-Fail "$name 违反角色分离：$($overlap -join ', ') 同时承担实现与评审/验收" }
    else { Write-Pass "$name 角色分离满足" }
  } elseif ($st -eq 'done') {
    Write-Warn2 "$name 缺 exitCriteria[*].by，无法校验角色分离（历史 State）"
  }

  # 僵尸：运行中且长期未更新
  if ($st -in @('running', 'blocked_escalation')) {
    $age = ($today - $sf.LastWriteTime).Days
    if ($age -gt $staleDays) {
      Write-Fail "$name 停滞 $age 天（iteration=$iter，status=$st）：应回填 incomplete/abandoned 或收敛"
    } else {
      Write-Pass "$name 运行中且未超停滞阈值（$age 天）"
    }
  }
}
if ($stateFiles.Count -gt 0) {
  Write-Host "  [INFO] State 合计 $($stateFiles.Count)：done=$doneCount，非 done=$($stateFiles.Count - $doneCount)"
}

# ---------- 5. cross-ref 路径存在 ----------
Write-Host "`n[5/7] cross-ref 路径存在（带「回填标注」的文件跳过）"
$allMd = @(
  Get-ChildItem (Join-Path $Root 'agents') -Recurse -Filter *.md -ErrorAction SilentlyContinue
  Get-ChildItem (Join-Path $Root 'knowledge') -Recurse -Filter *.md -ErrorAction SilentlyContinue
  Get-ChildItem (Join-Path $Root 'templates') -Recurse -Filter *.md -ErrorAction SilentlyContinue
  Get-ChildItem (Join-Path $Root 'workflows') -Recurse -Filter *.md -ErrorAction SilentlyContinue
  Get-ChildItem (Join-Path $Root 'tasks') -Recurse -Filter *.md -ErrorAction SilentlyContinue
)
$refPattern = '\.ai/[A-Za-z0-9_./-]+\.(?:md|ps1|py)'
$missing = 0; $checked = 0; $skipped = 0
foreach ($f in $allMd) {
  $txt = Get-Content $f.FullName -Encoding UTF8 -Raw
  if ($txt -match '(?m)^\s*>\s*\*\*\[回填标注') { $skipped++; continue }
  foreach ($line in $txt -split "`r?`n") {
    # TASK 可预告尚未生成的产物，但必须显式标记，避免把普通拼写错误静默放过。
    if ($line -match '\[planned-output\]') { continue }
    foreach ($m in [regex]::Matches($line, $refPattern)) {
      $rel = $m.Value
      if ($rel -match 'xxx') { continue }
      $checked++
      $abs = Join-Path $Root ($rel -replace '^\.ai/', '')
      if (-not (Test-Path $abs)) { Write-Fail "悬空引用: $rel (in $($f.Name))"; $missing++ }
    }
  }
}
if ($missing -eq 0) { Write-Pass "checked=$checked 悬空引用=0（跳过回填标注文件 $skipped 个）" }

# ---------- 6. 残留线性表述（WARN）----------
Write-Host "`n[6/7] 残留权威线性表述（WARN 供人工确认）"
$keywords = @('退回上一阶段', '阶段间单向传递', '线性 15 步流水线', '退一格')
$authoritative = @('knowledge\loop-state-model.md', 'agents\workflow-manager.md', 'workflows\feature-development.md')
$kwHit = 0
foreach ($a in $authoritative) {
  $p = Join-Path $Root $a
  if (-not (Test-Path $p)) { continue }
  $lines = Get-Content $p -Encoding UTF8
  for ($i = 0; $i -lt $lines.Count; $i++) {
    foreach ($kw in $keywords) {
      if ($lines[$i] -match [regex]::Escape($kw)) {
        $kwHit++
        Write-Warn2 "${a}:$($i+1) 命中 '$kw' -- 若属旧版对比则合法: $($lines[$i].Trim())"
      }
    }
  }
}
if ($kwHit -eq 0) { Write-Pass "无线性关键词命中" }

# ---------- 7. Task / ADR / hook / 技能映射配置一致性 ----------
Write-Host "`n[7/7] Task / ADR / hook / 技能映射配置一致性"
$checks = @(
  @{ Path = 'templates\task-template.md'; Label = 'task-template.md 存在' },
  @{ Path = 'tasks'; Label = '.ai/tasks/ 目录存在' },
  @{ Path = 'decisions\ADR'; Label = '.ai/decisions/ADR/ 目录存在' },
  @{ Path = 'templates\adr-template.md'; Label = 'adr-template.md 存在' },
  @{ Path = 'knowledge\skill-mapping.md'; Label = 'skill-mapping.md 存在' },
  @{ Path = 'loop\exit-criteria.yaml'; Label = 'exit-criteria.yaml 存在' },
  @{ Path = 'githooks\pre-commit'; Label = 'pre-commit 门禁存在' }
)
foreach ($c in $checks) {
  $p = Join-Path $Root $c.Path
  if (Test-Path $p) { Write-Pass $c.Label } else { Write-Fail "缺少 .ai/$($c.Path)" }
}
$agentsMd = Join-Path $RepoRootPath 'AGENTS.md'
if ((Test-Path $agentsMd) -and ((Get-Content $agentsMd -Encoding UTF8 -Raw) -match '代码修改(强制|硬)约束')) { Write-Pass "AGENTS.md 含工程约束章节" }
else { Write-Fail "AGENTS.md 未发现工程约束章节" }
$engFiles = @('knowledge\role-context.md', 'agents\workflow-manager.md')
foreach ($e in $engFiles) {
  $p = Join-Path $Root $e
  if ((Test-Path $p) -and ((Get-Content $p -Encoding UTF8 -Raw) -match 'TASK')) { Write-Pass "$e 含 Task 驱动规则" }
  else { Write-Fail "$e 未发现 Task 驱动规则" }
}
$skillMap = Join-Path $Root 'knowledge\skill-mapping.md'
if ((Test-Path $skillMap) -and ((Get-Content $skillMap -Encoding UTF8 -Raw) -match 'grilling')) { Write-Pass "skill-mapping.md 引用 grilling（Grill Me 引擎）" }
else { Write-Fail "skill-mapping.md 未引用 grilling" }

# ---------- 汇总 ----------
Write-Host "`n========== 汇总 ==========" -ForegroundColor Cyan
Write-Host "FAIL=$script:FailCount  WARN=$script:WarnCount"
if ($script:FailCount -gt 0) { Write-Host "结果: FAIL" -ForegroundColor Red; exit 1 }
else { Write-Host "结果: PASS" -ForegroundColor Green; exit 0 }
