#requires -Version 7.0
[CmdletBinding()]
param([switch]$SkipIntegrationTests)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$steps = @(
  @{ Name = '静态与 State 门禁'; File = Join-Path $PSScriptRoot 'verify-loop.ps1'; Arguments = @() },
  @{ Name = 'Worktree 资源对账'; File = Join-Path $PSScriptRoot 'worktree.ps1'; Arguments = @('reconcile','-Strict') }
)
if (-not $SkipIntegrationTests) {
  $steps += @(
    @{ Name = 'Loop V2 反例与状态机测试'; File = Join-Path $repoRoot '.ai\tests\loop-v2\run-tests.ps1'; Arguments = @() },
    @{ Name = 'Worktree V2 集成测试'; File = Join-Path $repoRoot '.ai\tests\worktree-v2\Run-Tests.ps1'; Arguments = @() }
  )
}

Push-Location $repoRoot
try {
  foreach ($step in $steps) {
    Write-Host "`n== $($step.Name) ==" -ForegroundColor Cyan
    & pwsh -NoProfile -File $step.File @($step.Arguments)
    if ($LASTEXITCODE -ne 0) { throw "LOOP_GATE_FAILED: $($step.Name) (exit=$LASTEXITCODE)" }
  }
  Write-Host "`nLOOP_GATE_PASS" -ForegroundColor Green
  exit 0
} catch {
  Write-Error $_
  exit 1
} finally {
  Pop-Location
}
