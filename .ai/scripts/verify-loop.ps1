#requires -Version 5.1
<#
.SYNOPSIS
  Agent Loop 配置静态校验脚本。
.DESCRIPTION
  校验 .ai 下 Loop 定义文件的内部一致性：
    1. 依赖图无环 + 终点仅 KNOWLEDGE + 起点唯一
    2. 12 项 exitCriteria 每个 Agent 有归属
    3. 关键门禁 dependsOn 映射存在（等价旧版 5 条禁止项）
    4. cross-ref 路径存在
    5. 无残留权威线性表述（WARN，供人工确认）
  退出码：0 = 全过；1 = 有 FAIL。
.NOTES
  对应 .ai/knowledge/loop-verification-checklist.md 的「一、静态校验」。
#>
[CmdletBinding()]
param(
  [string]$Root
)

# $PSScriptRoot 在 param 默认值中尚未定义，移到体内求值
if (-not $Root) { $Root = Split-Path $PSScriptRoot -Parent }

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:FailCount = 0
$script:WarnCount = 0

function Write-Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:FailCount++ }
function Write-Warn2($msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow; $script:WarnCount++ }

Write-Host "Agent Loop 静态校验" -ForegroundColor Cyan
Write-Host "根目录: $Root`n"

# ---------- 1. 依赖图校验 ----------
Write-Host "[1/5] 依赖图（无环 / 终点仅 KNOWLEDGE / 起点唯一）"
$stateModel = Join-Path $Root 'knowledge\loop-state-model.md'
if (-not (Test-Path $stateModel)) { Write-Fail "找不到 $stateModel"; exit 1 }
$content = Get-Content $stateModel -Encoding UTF8 -Raw

$depMap = @{}
$capture = $false
$rowRegex = '^\|\s*(REQ_ANALYSIS|IMPACT_ANALYSIS|EXP_DESIGN|TECH_DESIGN|TESTCASES|IMPLEMENTED|CODE_REVIEW|SECURITY_REVIEW|EXP_ACCEPT|TEST_PASS|QUALITY_GATE|KNOWLEDGE)\s*\|[^|]*\|\s*([^|]+?)\s*\|\s*$'
foreach ($line in ($content -split "`r?`n")) {
  if ($line -match '^### 大型任务') { $capture = $true; continue }
  if ($capture -and $line -match '^### ') { $capture = $false }
  if ($capture -and $line -match $rowRegex) {
    $id = $matches[1]; $depStr = $matches[2].Trim()
    $deps = if ($depStr -eq '-') { @() } else { @(($depStr -split ',') | ForEach-Object { $_.Trim() }) }
    $depMap[$id] = $deps
  }
}

if ($depMap.Count -ne 12) {
  Write-Fail "大型 exitCriteria 解析到 $($depMap.Count) 项，预期 12"
} else {
  Write-Pass "解析到 12 项 exitCriteria"
}

foreach ($id in $depMap.Keys) {
  foreach ($d in $depMap[$id]) {
    if (-not $depMap.ContainsKey($d)) { Write-Fail "$id 依赖未知节点 $d" }
  }
}

$allDeps = @($depMap.Values | ForEach-Object { $_ } | Sort-Object -Unique)
$leaves = @($depMap.Keys | Where-Object { $allDeps -notcontains $_ })
if ($leaves.Count -eq 1 -and $leaves[0] -eq 'KNOWLEDGE') {
  Write-Pass "终点仅 KNOWLEDGE"
} else {
  Write-Fail "终点应为 [KNOWLEDGE]，实际为 [$($leaves -join ', ')]"
}

$roots = @($depMap.Keys | Where-Object { $depMap[$_].Count -eq 0 })
if ($roots.Count -eq 1 -and $roots[0] -eq 'REQ_ANALYSIS') {
  Write-Pass "起点唯一：REQ_ANALYSIS"
} else {
  Write-Fail "起点应为 [REQ_ANALYSIS]，实际为 [$($roots -join ', ')]"
}

# 环检测：Kahn 拓扑（入度 = 节点依赖数）
$inDeg = @{}; foreach ($id in $depMap.Keys) { $inDeg[$id] = $depMap[$id].Count }
$queue = [System.Collections.Generic.Queue[string]]::new()
foreach ($k in $inDeg.Keys) { if ($inDeg[$k] -eq 0) { $queue.Enqueue($k) } }
$processed = 0
while ($queue.Count -gt 0) {
  $n = $queue.Dequeue(); $processed++
  foreach ($id in $depMap.Keys) {
    if ($depMap[$id] -contains $n) {
      $inDeg[$id]--
      if ($inDeg[$id] -eq 0) { $queue.Enqueue($id) }
    }
  }
}
if ($processed -eq $depMap.Count) { Write-Pass "依赖图无环（拓扑处理 $($processed)/$($depMap.Count)）" }
else { Write-Fail "依赖图存在环，仅处理 $($processed)/$($depMap.Count) 节点" }

# ---------- 2. Agent 归属校验 ----------
Write-Host "`n[2/5] exitCriteria 的 Agent 归属"
$agentsDir = Join-Path $Root 'agents'
$agentFiles = Get-ChildItem $agentsDir -Filter *.md
foreach ($id in $depMap.Keys) {
  $hit = $false
  foreach ($f in $agentFiles) {
    if ((Get-Content $f.FullName -Encoding UTF8 -Raw) -match [regex]::Escape($id)) { $hit = $true; break }
  }
  if ($hit) { Write-Pass "$id 有 Agent 归属" } else { Write-Fail "$id 未被任何 agents/*.md 引用" }
}

# ---------- 3. 关键门禁 dependsOn 映射 ----------
Write-Host "`n[3/5] 关键门禁 dependsOn 映射（等价旧版 5 条禁止项）"
$expected = @(
  @{ Rule='TECH_DESIGN 依赖 IMPACT_ANALYSIS+EXP_DESIGN'; Id='TECH_DESIGN'; Need=@('IMPACT_ANALYSIS','EXP_DESIGN') },
  @{ Rule='IMPLEMENTED 依赖 TECH_DESIGN+TESTCASES'; Id='IMPLEMENTED'; Need=@('TECH_DESIGN','TESTCASES') },
  @{ Rule='CODE_REVIEW 依赖 IMPLEMENTED'; Id='CODE_REVIEW'; Need=@('IMPLEMENTED') },
  @{ Rule='SECURITY_REVIEW 依赖 IMPLEMENTED'; Id='SECURITY_REVIEW'; Need=@('IMPLEMENTED') },
  @{ Rule='TEST_PASS 依赖 CODE_REVIEW+SECURITY_REVIEW'; Id='TEST_PASS'; Need=@('CODE_REVIEW','SECURITY_REVIEW') },
  @{ Rule='QUALITY_GATE 依赖 TEST_PASS+SECURITY_REVIEW+EXP_ACCEPT'; Id='QUALITY_GATE'; Need=@('TEST_PASS','SECURITY_REVIEW','EXP_ACCEPT') }
)
foreach ($e in $expected) {
  $ok = $true
  foreach ($n in $e.Need) { if ($depMap[$e.Id] -notcontains $n) { $ok = $false } }
  if ($ok) { Write-Pass $e.Rule } else { Write-Fail "$($e.Rule) | 实际依赖: [$($depMap[$e.Id] -join ', ')]" }
}

# ---------- 4. cross-ref 路径存在 ----------
Write-Host "`n[4/5] cross-ref 路径存在"
$allMd = Get-ChildItem $Root -Recurse -Filter *.md
$refPattern = '\.ai/[A-Za-z0-9_./-]+\.(?:md|ps1|py)'
$missing = 0; $checked = 0
foreach ($f in $allMd) {
  $txt = Get-Content $f.FullName -Encoding UTF8 -Raw
  foreach ($m in [regex]::Matches($txt, $refPattern)) {
    $rel = $m.Value
    if ($rel -match 'xxx') { continue }
    $checked++
    $abs = Join-Path $Root ($rel -replace '^\.ai/', '')
    if (-not (Test-Path $abs)) { Write-Fail "悬空引用: $rel (in $($f.Name))"; $missing++ }
  }
}
if ($missing -eq 0) { Write-Pass "checked=$checked 悬空引用=0" }

# ---------- 5. 残留线性表述（WARN）----------
Write-Host "`n[5/5] 残留权威线性表述（WARN 供人工确认）"
$keywords = @('退回上一阶段', '阶段间单向传递', '线性 15 步流水线', '退一格')
$authoritative = @('knowledge\loop-state-model.md', 'agents\workflow-manager.md', 'workflows\feature-development.md')
foreach ($a in $authoritative) {
  $p = Join-Path $Root $a
  if (-not (Test-Path $p)) { continue }
  $lines = Get-Content $p -Encoding UTF8
  for ($i = 0; $i -lt $lines.Count; $i++) {
    foreach ($kw in $keywords) {
      if ($lines[$i] -match [regex]::Escape($kw)) {
        Write-Warn2 "${a}:$($i+1) 命中 '$kw' -- 若属旧版对比则合法: $($lines[$i].Trim())"
      }
    }
  }
}
if ($script:WarnCount -eq 0) { Write-Pass "无线性关键词命中" }

# ---------- 汇总 ----------
Write-Host "`n========== 汇总 ==========" -ForegroundColor Cyan
Write-Host "FAIL=$script:FailCount  WARN=$script:WarnCount"
if ($script:FailCount -gt 0) { Write-Host "结果: FAIL" -ForegroundColor Red; exit 1 }
else { Write-Host "结果: PASS" -ForegroundColor Green; exit 0 }