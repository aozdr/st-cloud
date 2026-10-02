#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent
$ctl = Join-Path $repo '.ai/scripts/loopctl.ps1'
$work = Join-Path $repo ('.ai/tests/loop-v2/.tmp-efficiency-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$ref = $work.Substring($repo.Length + 1).Replace('\','/')
$script:failed = 0
foreach ($name in @('design.md','requirement.md','verification.md','codereview.md','evidence.json')) { Set-Content (Join-Path $work $name) 'fixture evidence' -Encoding utf8NoBOM }
function Run([string[]]$Arguments) {
  $out = @(& pwsh -NoProfile -File $ctl @Arguments 2>&1 | ForEach-Object {$_.ToString()})
  [pscustomobject]@{ Code=$LASTEXITCODE; Text=$out -join "`n" }
}
function Expect([string]$Name, [bool]$Ok, [string]$Detail) {
  if ($Ok) { Write-Host "[PASS] $Name" } else { Write-Host "[FAIL] $Name -- $Detail"; $script:failed++ }
}
function Save([string]$Name, $Object) {
  $path = Join-Path $work $Name
  $Object | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
  return $path
}
function Read([string]$Path) { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function NewState([string]$Scale='medium', [string]$Name='state.json') {
  $path=Join-Path $work $Name
  $r=Run @('init',$path,'-TaskId','EFFICIENCY','-Scale',$Scale,'-Objective','validate lean workflow','-CompletionCriteria','AC-1')
  if ($r.Code) { throw $r.Text }
  $state=Read $path; $state.revision.design='d1'; $state.revision.code='c1'
  $state.artifacts=[pscustomobject]@{
    design=[pscustomobject]@{status='done';ref="$ref/design.md"}
    requirement=[pscustomobject]@{status='done';ref="$ref/requirement.md"}
    verification=[pscustomobject]@{status='done';ref="$ref/verification.md"}
    review=[pscustomobject]@{status='done';ref="$ref/codereview.md"}
  }
  return $state
}
function Proposal([string]$Id, [string]$Actor='manager') {
  $p=[pscustomobject]@{ taskId='EFFICIENCY'; criterionProposal=[pscustomobject]@{
    id=$Id;outcome='pass';by=$Actor;evidenceRef="$ref/evidence.json"
    validatedRevision=$(if($Id -in @('DESIGN','REQ_ANALYSIS','TECH_DESIGN')){'d1'}else{'c1'})
  }}
  if ($Id -eq 'VERIFIED') {
    $p | Add-Member verification ([pscustomobject]@{validatedRevision='c1';checks=@(
      @('tests','selfReview','knowledge') | ForEach-Object {[pscustomobject]@{dimension=$_;outcome='pass';evidenceRef="$ref/verification.md"}}
    )})
  }
  if ($Id -eq 'ACCEPT') { $p | Add-Member acceptanceEvidence @([pscustomobject]@{criterion='AC-1';evidenceRef="$ref/verification.md";validatedRevision='c1'}) }
  return $p
}
function Direct([string]$StatePath,[string]$Id) {
  $p=Save ('proposal-'+$Id+'.json') (Proposal $Id)
  Run @('evaluate-direct',$StatePath,'-ProposalPath',$p,'-Actor','manager')
}
try {
  $s=NewState; $path=Save 'medium.json' $s
  Expect 'EFF-01 默认 medium 是 V3 四项且无伪派发' ($s.definitionVersion -eq 3 -and $s.exitCriteria.Count -eq 4 -and $s.dispatchLedger.Count -eq 0) ''
  Expect 'EFF-02 init history 不嵌套完整 State' ((Get-Item (Join-Path $work 'state.json')).Length -lt 12000 -and $null -eq $s.history[0].after.history) ''
  foreach ($id in @('DESIGN','IMPLEMENTED','VERIFIED','ACCEPT')) {
    $r=Direct $path $id; Expect "EFF-03 medium 主线程 $id" ($r.Code -eq 0) $r.Text
  }
  $r=Run @('complete',$path); Expect 'EFF-04 medium 完成' ($r.Code -eq 0 -and (Read $path).status -eq 'done') $r.Text
  $before=Get-Content $path -Raw; $r=Run @('init',$path)
  Expect 'EFF-05 重复 init 不覆盖' ($r.Code -eq 0 -and $r.Text -eq 'UNCHANGED' -and (Get-Content $path -Raw) -eq $before) $r.Text
  Expect 'EFF-06 complete history 不递归膨胀' ((Get-Item $path).Length -lt 20000) ''

  foreach ($dimension in @('security','database','apiContract','irreversible')) {
    $bad=Read $path; $bad.risk.$dimension=$true
    $r=Run @('validate',(Save ('risk-'+$dimension+'.json') $bad))
    Expect "EFF-07 $dimension 风险不能降为 medium" ($r.Code -ne 0 -and $r.Text -match 'RISK_SCALE_MISMATCH') $r.Text
  }
  $bad=Read $path; $bad.PSObject.Properties.Remove('risk'); $r=Run @('validate',(Save 'no-risk.json' $bad))
  Expect 'EFF-08 风险分类不可缺失' ($r.Code -ne 0 -and $r.Text -match 'RISK_REQUIRED') $r.Text
  $bad=Read $path; $bad.risk.ui='false'; $r=Run @('validate',(Save 'string-risk.json' $bad))
  Expect 'EFF-09 风险字段拒绝字符串' ($r.Code -ne 0 -and $r.Text -match 'SCHEMA_VALIDATION_FAILED') $r.Text
  $bad=Read $path; $bad.risk.ui=$true; $r=Run @('validate',(Save 'ui-missing.json' $bad))
  Expect 'EFF-10 UI 变更需要体验证据' ($r.Code -ne 0 -and $r.Text -match 'VERIFICATION_CHECK_MISSING') $r.Text
  $bad.verification.checks+= [pscustomobject]@{dimension='ui';outcome='pass';evidenceRef="$ref/verification.md"}
  $r=Run @('validate',(Save 'ui-positive.json' $bad))
  Expect 'EFF-10B UI 维度证据完整可完成' ($r.Code -eq 0) $r.Text
  $bad=Read $path; $bad.verification.checks+= $bad.verification.checks[0]; $r=Run @('validate',(Save 'duplicate-check.json' $bad))
  Expect 'EFF-10C 重复验证维度拒绝' ($r.Code -ne 0 -and $r.Text -match 'VERIFICATION_CHECK_DUPLICATE') $r.Text
  $bad=Read $path; $bad.verification.checks[0].outcome='blocked'; $r=Run @('validate',(Save 'failed-check.json' $bad))
  Expect 'EFF-10D 有阻塞检查不能完成' ($r.Code -ne 0 -and $r.Text -match 'VERIFICATION_CHECK_FAILED') $r.Text
  $bad=Read $path; $bad.verification.checks[0].evidenceRef="$ref/missing.json"; $r=Run @('validate',(Save 'missing-evidence.json' $bad))
  Expect 'EFF-11 分维度证据必须真实' ($r.Code -ne 0 -and $r.Text -match 'EVIDENCE_NOT_FOUND') $r.Text
  $bad=Read $path; $bad.artifacts.design.ref="$ref/missing.md"; $r=Run @('validate',(Save 'missing-artifact.json' $bad))
  Expect 'EFF-12 必需产物不可缺失' ($r.Code -ne 0 -and $r.Text -match 'ARTIFACT_NOT_FOUND|CATALOG_ARTIFACT_MISSING') $r.Text
  $bad=Read $path; $bad.verification.validatedRevision='old'; $r=Run @('validate',(Save 'old-verification.json' $bad))
  Expect 'EFF-13 验证记录不能复用旧 revision' ($r.Code -ne 0 -and $r.Text -match 'REVISION_MISMATCH') $r.Text
  $bad=Read $path; $bad.acceptanceEvidence=@(); $r=Run @('complete',(Save 'missing-acceptance.json' $bad))
  Expect 'EFF-14 Goal 缺项拒绝完成' ($r.Code -ne 0 -and $r.Text -match 'ACCEPTANCE_EVIDENCE_INCOMPLETE') $r.Text
  $bad=Read $path; $bad.blockers=@([pscustomobject]@{fingerprint='unresolved';status='open';repairAttempts=@()}); $r=Run @('complete',(Save 'blocked.json' $bad))
  Expect 'EFF-15 open blocker 拒绝完成' ($r.Code -ne 0 -and $r.Text -match 'OPEN_BLOCKER') $r.Text
  $r=Run @('stale',$path,'-RevisionKind','code','-RevisionValue','c2')
  Expect 'EFF-16 code 变化失效验证与验收' ($r.Code -eq 0 -and (Read $path).exitCriteria[2].status -eq 'stale' -and (Read $path).status -eq 'running') $r.Text

  $large=NewState 'large' 'large-init.json'; $largePath=Save 'large.json' $large
  foreach ($id in @('REQ_ANALYSIS','TECH_DESIGN','IMPLEMENTED','VERIFIED')) {
    $r=Direct $largePath $id; Expect "EFF-17 large 可直接推进 $id" ($r.Code -eq 0) $r.Text
  }
  $r=Direct $largePath 'ACCEPT'; Expect 'EFF-18 测试已过仍需独立评审后验收' ($r.Code -ne 0 -and $r.Text -match 'DEPENDENCY_NOT_SATISFIED') $r.Text
  $r=Direct $largePath 'CODE_REVIEW'; Expect 'EFF-19 不允许主线程代替独立评审' ($r.Code -ne 0 -and $r.Text -match 'DIRECT_REVIEW_FORBIDDEN') $r.Text
  $large=Read $largePath; $large | Add-Member singleAgentAuthorization ([pscustomobject]@{
    taskId='EFFICIENCY';actor='manager';authorizedBy='user';authorizationQuote='fixture';evidenceRef="$ref/evidence.json";criteria=@('CODE_REVIEW')
  }); $null=Save 'large.json' $large; $r=Direct $largePath 'CODE_REVIEW'
  Expect 'EFF-20 V3 独立评审不能用单人授权绕过' ($r.Code -ne 0 -and $r.Text -match 'DIRECT_REVIEW_FORBIDDEN') $r.Text

  # 模拟真实已返回且 scope 已核验的独立审查结果，测试 Evaluate 身份和完成门禁。
  $large=Read $largePath; $large.PSObject.Properties.Remove('singleAgentAuthorization')
  $proposal=Proposal 'CODE_REVIEW' 'reviewer'; $proposal.criterionProposal | Add-Member dispatchId 'review-1'
  $proposal.taskId='review-task'; $proposal | Add-Member dispatchId 'review-1'
  $proposal.criterionProposal.evidenceRef="$ref/review-result.json"
  $proposalPath=Save 'review-result.json' $proposal
  $large.dispatchLedger=@([pscustomobject]@{dispatchId='review-1';taskId='review-task';idempotencyKey='review-key';role='reviewer';taskType='review';criterionId='CODE_REVIEW';childId='reviewer';status='returned';resultRef="$ref/review-result.json";scopeVerified=$true})
  $null=Save 'large.json' $large; $r=Run @('evaluate',$largePath,'-ProposalPath',$proposalPath)
  Expect 'EFF-21 独立评审 Evaluate 通过' ($r.Code -eq 0) $r.Text
  $r=Direct $largePath 'ACCEPT'; $r2=Run @('complete',$largePath)
  Expect 'EFF-22 large 测试与评审汇合后完成' ($r.Code -eq 0 -and $r2.Code -eq 0) ($r.Text+$r2.Text)
  foreach ($dimension in @('security','database','apiContract','irreversible')) {
    $bad=Read $largePath; $bad.risk.$dimension=$true; $r=Run @('validate',(Save ('large-'+$dimension+'.json') $bad))
    Expect "EFF-22B large $dimension 仍要求对应验证证据" ($r.Code -ne 0 -and $r.Text -match 'VERIFICATION_CHECK_MISSING') $r.Text
  }
  $bad=Read $largePath; ($bad.exitCriteria | Where-Object id -eq CODE_REVIEW) | Add-Member by 'manager' -Force
  $r=Run @('validate',(Save 'self-dispatch.json' $bad))
  Expect 'EFF-23 派发结果也拒绝实现者自评' ($r.Code -ne 0 -and $r.Text -match 'ROLE_SEPARATION_VIOLATION') $r.Text
  $r=Run @('stale',$largePath,'-RevisionKind','code','-RevisionValue','c2')
  Expect 'EFF-24 large 修复后独立评审失效' ($r.Code -eq 0 -and ((Read $largePath).exitCriteria | Where-Object id -eq CODE_REVIEW).status -eq 'stale') $r.Text

  $legacy=Join-Path $work 'v2.json'; $r=Run @('init',$legacy,'-DefinitionPath',(Join-Path $repo '.ai/loop/exit-criteria.v2.yaml'),'-TaskId','OLD','-Scale','medium','-Objective','legacy fixture','-CompletionCriteria','legacy acceptance')
  $r2=Run @('validate',$legacy)
  Expect 'EFF-25 V2 自动选择冻结定义' ($r.Code -eq 0 -and $r2.Code -eq 0 -and (Read $legacy).exitCriteria.Count -eq 8) ($r.Text+$r2.Text)
  $r=Run @('validate',$legacy,'-DefinitionPath',(Join-Path $repo '.ai/loop/exit-criteria.yaml'))
  Expect 'EFF-26 显式错误定义不静默纠正' ($r.Code -ne 0 -and $r.Text -match 'DEFINITION_VERSION_MISMATCH') $r.Text
  $bad=Read $legacy; $bad.definitionVersion=999; $r=Run @('validate',(Save 'unknown.json' $bad))
  Expect 'EFF-27 未知版本拒绝' ($r.Code -ne 0) $r.Text
  $r=Run @('init',(Join-Path $work 'bad-init.json'),'-TaskId','BAD','-Scale','medium','-Objective','risk','-CompletionCriteria','check','-SecuritySensitive')
  Expect 'EFF-28 init 即拒绝敏感风险降级且不写 State' ($r.Code -ne 0 -and -not (Test-Path (Join-Path $work 'bad-init.json'))) $r.Text
} finally {
  # 只清理本套件创建、已解析为仓库测试目录内部的临时目录。
  $testRoot=[IO.Path]::GetFullPath((Join-Path $repo '.ai/tests/loop-v2')).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
  if (-not [IO.Path]::GetFullPath($work).StartsWith($testRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'TEST_CLEANUP_PATH_ESCAPE' }
  Remove-Item -LiteralPath $work -Recurse -Force
}
if ($script:failed) { Write-Host "FAILED=$script:failed"; exit 1 }
Write-Host 'ALL PASSED'; exit 0
