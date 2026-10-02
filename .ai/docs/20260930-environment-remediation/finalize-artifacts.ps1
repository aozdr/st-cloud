$ErrorActionPreference='Stop'
$base='.ai/docs/20260930-environment-remediation'
$original=Join-Path $base 'codereview.md'
$archive=Join-Path $base 'codereview-r1.md'
$oldHash=(Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash
if(-not (Test-Path -LiteralPath $archive)){
  Copy-Item -LiteralPath $original -Destination $archive
  if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $oldHash){throw '首轮代码报告副本不一致'}
}
$raw=Get-Content -Raw -Encoding UTF8 -LiteralPath $original
if($raw.StartsWith('# 独立代码审查：env-code-r1')){
  $prefix=@'
# 当前代码审查索引 env-code-r2

主线程索引：当前独立复审见 [codereview-r2.md](codereview-r2.md) 和 `.ai/runtime/results/DISPATCH-env-code-review-02.json`，CODE_REVIEW=pass 已由主线程 Evaluate。首轮 CR01–CR03 的团队锁/租户传播问题修复并独立复审通过。本索引不新增审查结论，最终测试和运行范围见 testreport.md、acceptance.md。

首轮失败原文另以字节相同副本保留在 [codereview-r1.md](codereview-r1.md)，下面也保留原文。历史 fail 仅对应 env-code-r1，不代表当前复审结论。

---

'@
  [IO.File]::WriteAllText((Join-Path (Get-Location).Path $original),$prefix+"`n"+$raw,[Text.UTF8Encoding]::new($false))
}
$statePath='.ai/state/20260930-environment-remediation.yaml'
$state=Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json
$state.artifacts.'exp-review.md'.ref="$base/exp-review.md"
$state.artifacts.'codereview.md'.ref="$base/codereview.md"
$eventId='canonical-artifact-bindings-20261001'
if(-not @($state.history | Where-Object eventId -eq $eventId).Count){
  $state.history=@($state.history)+@([pscustomobject]@{
    eventId=$eventId; action='repair-artifact-bindings'; actor='workflow-manager'; occurredAt=[DateTime]::UtcNow.ToString('o'); revision=$state.revision
    detail='完成DryRun发现catalog要求固定文件名；补正exp-review/codereview索引，保留原独立报告和字节相同r1副本，不修改criterion结果或产品修订。'
    preservedCodeReviewR1Sha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
  })
}
$state | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -LiteralPath $statePath
& .ai/scripts/loopctl.ps1 complete $statePath -DryRun
if($LASTEXITCODE -ne 0){throw '完成DryRun仍有门禁缺口'}
