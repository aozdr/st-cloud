#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$tests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'unit') -Filter '*.tests.ps1' -File | Sort-Object Name)
$failures = 0
foreach ($test in $tests) {
  Write-Host "`n== $($test.Name) ==" -ForegroundColor Cyan
  & $test.FullName
  if ($LASTEXITCODE -ne 0) { $failures++ }
}
if ($failures -gt 0) { Write-Host "SUITES_FAILED=$failures"; exit 1 }
Write-Host "SUITES_PASSED=$($tests.Count)"; exit 0
