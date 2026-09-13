#requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent
$LoopCtl = Join-Path $RepoRoot '.ai\scripts\loopctl.ps1'
$RunId = [Guid]::NewGuid().ToString('N')
$Work = Join-Path $RepoRoot ".ai\tests\loop-v2\.tmp-state-engine-$RunId"
New-Item -ItemType Directory -Path $Work | Out-Null
$WorkRef = $Work.Substring($RepoRoot.Length + 1).Replace('\','/')
$EvidenceRefRoot = "$WorkRef/evidence"
$EvidenceDir = Join-Path $Work 'evidence'
New-Item -ItemType Directory -Path $EvidenceDir | Out-Null
foreach ($name in @('DESIGN','TESTCASES','IMPLEMENTED','CODE_REVIEW','TEST_PASS','KNOWLEDGE','ACCEPT','security-skip','skip','ac-1','ac-2','proposal')) {
  Set-Content -LiteralPath (Join-Path $EvidenceDir "$name.json") -Value '{}' -Encoding utf8NoBOM
}
$ArtifactDocNames = @('design.md','testcases.md','changereport.md','codereview.md','testreport.md')
foreach ($name in $ArtifactDocNames) {
  Set-Content -LiteralPath (Join-Path $Work $name) -Value '{}' -Encoding utf8NoBOM
}
$script:Failed = 0
$Now = '2026-09-11T00:00:00Z'

function New-Criterion([string]$Id, [string[]]$DependsOn) {
  [pscustomobject][ordered]@{ id = $Id; status = 'pending'; dependsOn = @($DependsOn) }
}

function New-MediumState {
  [pscustomobject][ordered]@{
    schemaVersion = 2; definitionVersion = 2; taskId = 'TEST-LOOP-V2'; scale = 'medium'; status = 'running'
    goal = [pscustomobject]@{ objective = '验证状态引擎'; scope = '.ai/tests'; completionCriteria = @('AC-1','AC-2') }
    revision = [pscustomobject]@{ design = 'design-r1'; code = 'code-r1' }
    exitCriteria = @(
      (New-Criterion DESIGN @()), (New-Criterion TESTCASES @('DESIGN')),
      (New-Criterion IMPLEMENTED @('DESIGN','TESTCASES')), (New-Criterion CODE_REVIEW @('IMPLEMENTED')),
      (New-Criterion SECURITY_REVIEW @('IMPLEMENTED')), (New-Criterion TEST_PASS @('CODE_REVIEW','SECURITY_REVIEW')),
      (New-Criterion KNOWLEDGE @('TEST_PASS')), (New-Criterion ACCEPT @('KNOWLEDGE'))
    )
    artifacts = [pscustomobject]@{}; blockers = @(); acceptanceEvidence = @(); dispatchLedger = @(); proposals = @(); history = @()
  }
}

function New-DesignAttempt([string]$DispatchId, [string]$Status = 'returned', [string]$IdempotencyKey = 'design-key') {
  [pscustomobject][ordered]@{
    dispatchId=$DispatchId; taskId='design-task'; idempotencyKey=$IdempotencyKey
    role='executor'; taskType='design'; criterionId='DESIGN'; childId='executor'
    status=$Status; resultRef="$EvidenceRefRoot/proposal.json"; scopeVerified=$true
  }
}

function Complete-Criterion($Criterion, [string]$Actor = 'reviewer') {
  $Criterion.status = 'done'
  $Criterion | Add-Member by $Actor -Force
  $Criterion | Add-Member dispatchId ("dispatch-" + $Criterion.id) -Force
  $Criterion | Add-Member evidenceRef ($EvidenceRefRoot + '/' + $Criterion.id + '.json') -Force
  $revision = if ($Criterion.id -in @('DESIGN','TESTCASES')) { 'design-r1' } else { 'code-r1' }
  $Criterion | Add-Member validatedRevision $revision -Force
  $Criterion | Add-Member completedAt $Now -Force
  if ($Criterion.id -eq 'DESIGN') {
    $Criterion | Add-Member userConfirmedAt $Now -Force
    $Criterion | Add-Member confirmedBy 'user' -Force
    $Criterion | Add-Member confirmationArtifact '.ai/docs/20260911-agent-loop-v2/design.md' -Force
  }
}

function Complete-State($State) {
  foreach ($criterion in $State.exitCriteria) {
    if ($criterion.id -eq 'SECURITY_REVIEW') {
      $criterion.status = 'skipped'; $criterion | Add-Member skipReason '无安全敏感面' -Force
      $criterion | Add-Member approvedBy 'security-reviewer' -Force; $criterion | Add-Member evidenceRef "$EvidenceRefRoot/security-skip.json" -Force
    } else { Complete-Criterion $criterion $(if ($criterion.id -eq 'IMPLEMENTED') { 'executor' } else { 'reviewer' }) }
  }
  $artifactMap = [ordered]@{}
  $artifactDocByCriterion = @{
    DESIGN = 'design.md'
    TESTCASES = 'testcases.md'
    IMPLEMENTED = 'changereport.md'
    CODE_REVIEW = 'codereview.md'
    TEST_PASS = 'testreport.md'
  }
  foreach ($criterion in $State.exitCriteria | Where-Object status -eq 'done') {
    $artifactRef = if ($artifactDocByCriterion.ContainsKey([string]$criterion.id)) { "$WorkRef/$($artifactDocByCriterion[[string]$criterion.id])" } else { [string]$criterion.evidenceRef }
    $artifactMap[('artifact-' + $criterion.id)] = [pscustomobject][ordered]@{
      status = 'done'; ref = $artifactRef; provides = @([string]$criterion.id)
    }
  }
  $State.artifacts = [pscustomobject]$artifactMap
  $State.acceptanceEvidence = @(
    [pscustomobject]@{ criterion = 'AC-1'; evidenceRef = "$EvidenceRefRoot/ac-1.json"; validatedRevision = 'code-r1' },
    [pscustomobject]@{ criterion = 'AC-2'; evidenceRef = "$EvidenceRefRoot/ac-2.json"; validatedRevision = 'code-r1' }
  )
  return $State
}

function Save-Fixture([string]$Name, $State) {
  $path = Join-Path $Work $Name
  $State | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path -Encoding utf8NoBOM
  return $path
}

function Invoke-Loop([string[]]$Arguments) {
  $output = @(& pwsh -NoProfile -File $LoopCtl @Arguments 2>&1 | ForEach-Object { $_.ToString() })
  [pscustomobject]@{ ExitCode = $LASTEXITCODE; Text = $output -join "`n" }
}

function Expect([string]$Name, [bool]$Condition, [string]$Detail) {
  if ($Condition) { Write-Host "[PASS] $Name" -ForegroundColor Green }
  else { Write-Host "[FAIL] $Name -- $Detail" -ForegroundColor Red; $script:Failed++ }
}

try {
  $valid = Save-Fixture 'valid-medium.yaml' (New-MediumState)
  $r = Invoke-Loop @('validate', $valid)
  Expect 'TC-SCH-01 合法 medium' ($r.ExitCode -eq 0) $r.Text

  $s = New-MediumState; $s.exitCriteria = @($s.exitCriteria | Where-Object id -ne ACCEPT)
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-FG-01.yaml' $s))
  Expect 'TC-FG-01 缺 ACCEPT' ($r.ExitCode -ne 0 -and $r.Text -match 'CRITERIA_SET_MISMATCH') $r.Text

  $s = New-MediumState; $s.exitCriteria += (New-Criterion QUALITY_GATE @('ACCEPT'))
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-FG-02.yaml' $s))
  Expect 'TC-FG-02 额外标准' ($r.ExitCode -ne 0 -and $r.Text -match 'CRITERIA_SET_MISMATCH') $r.Text

  $s = New-MediumState; ($s.exitCriteria | Where-Object id -eq TEST_PASS).dependsOn = @('CODE_REVIEW')
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-FG-03.yaml' $s))
  Expect 'TC-FG-03 错误依赖' ($r.ExitCode -ne 0 -and $r.Text -match 'DEPENDENCY_MISMATCH') $r.Text

  $s = New-MediumState; $design = $s.exitCriteria | Where-Object id -eq DESIGN; $design | Add-Member confirmationRequired $true -Force; Complete-Criterion $design; $design.PSObject.Properties.Remove('confirmedBy')
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-FG-04.yaml' $s))
  Expect 'TC-FG-04 缺用户确认' ($r.ExitCode -ne 0 -and $r.Text -match 'CONFIRMATION_EVIDENCE_MISSING') $r.Text

  $s = New-MediumState; $design = $s.exitCriteria | Where-Object id -eq DESIGN; $design | Add-Member confirmationRequired $false -Force; Complete-Criterion $design
  foreach ($field in @('userConfirmedAt','confirmedBy','confirmationArtifact')) { $design.PSObject.Properties.Remove($field) }
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-FG-04B.yaml' $s))
  Expect 'TC-FG-04B 条件确认关闭' ($r.ExitCode -eq 0) $r.Text

  $s = Complete-State (New-MediumState); ($s.exitCriteria | Where-Object id -eq ACCEPT).by = 'executor'
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-FG-05.yaml' $s))
  Expect 'TC-FG-05 职责分离' ($r.ExitCode -ne 0 -and $r.Text -match 'ROLE_SEPARATION_VIOLATION') $r.Text

  $s = Complete-State (New-MediumState); $s.status = 'done'; $s.blockers = @([pscustomobject]@{ fingerprint='same'; status='open'; repairAttempts=@() })
  $r = Invoke-Loop @('complete', (Save-Fixture 'TC-FG-06.yaml' $s))
  Expect 'TC-FG-06 open blocker' ($r.ExitCode -ne 0 -and $r.Text -match 'OPEN_BLOCKER') $r.Text

  $s = New-MediumState; $sec = $s.exitCriteria | Where-Object id -eq SECURITY_REVIEW
  $sec.status='skipped'; $sec | Add-Member skipReason '无安全敏感面'; $sec | Add-Member approvedBy 'security'; $sec | Add-Member evidenceRef "$EvidenceRefRoot/skip.json"
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-SCH-06.yaml' $s))
  Expect 'TC-SCH-06 medium 安全审查有证据跳过' ($r.ExitCode -eq 0) $r.Text

  $uiState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $uiState.status = 'done'
  $uiState.artifacts.PSObject.Properties.Remove('uiSpec')
  foreach ($id in @('EXP_DESIGN','EXP_ACCEPT')) {
    $criterion = $uiState.exitCriteria | Where-Object id -eq $id
    $criterion | Add-Member applicable $false -Force
    $criterion.status = 'skipped'
    $criterion | Add-Member skipReason '任务不涉及页面、交互或视觉验收' -Force
    $criterion | Add-Member approvedBy 'workflow-manager' -Force
    $criterion | Add-Member evidenceRef '.ai/docs/20260912-stcore-security-consistency/exp-review.md' -Force
  }
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-UI-01-no-ui.yaml' $uiState))
  Expect 'TC-UI-01 无 UI 时跳过体验标准并保留证据' ($r.ExitCode -eq 0) $r.Text

  $uiState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $uiState.status = 'done'; $uiState.artifacts.PSObject.Properties.Remove('uiSpec')
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-UI-03-ui-spec-required.yaml' $uiState))
  Expect 'TC-UI-03 UI 任务缺 uiSpec 产物被拒绝（缺省 applicable=true）' ($r.ExitCode -ne 0 -and $r.Text -match 'CATALOG_ARTIFACT_MISSING') $r.Text

  $uiState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $uiState.status = 'done'
  foreach ($id in @('EXP_DESIGN','EXP_ACCEPT')) {
    $criterion = $uiState.exitCriteria | Where-Object id -eq $id
    $criterion | Add-Member applicable $false -Force; $criterion.status = 'skipped'
    $criterion | Add-Member skipReason '任务不涉及页面、交互或视觉验收' -Force
    $criterion | Add-Member approvedBy 'workflow-manager' -Force
    $criterion | Add-Member evidenceRef '.ai/docs/20260912-stcore-security-consistency/exp-review.md' -Force
  }
  $path = Save-Fixture 'TC-UI-04-stale-preserves-skip.yaml' $uiState
  $r = Invoke-Loop @('stale', $path, '-RevisionKind', 'code', '-RevisionValue', 'code-next')
  $after = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
  $expAccept = $after.exitCriteria | Where-Object id -eq EXP_ACCEPT
  $knowledge = $after.exitCriteria | Where-Object id -eq KNOWLEDGE
  Expect 'TC-UI-04 非适用体验标准在代码 stale 后保留 skipped' ($r.ExitCode -eq 0 -and $expAccept.status -eq 'skipped' -and $knowledge.status -eq 'stale') $r.Text

  $designStaleState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $designStaleState.status = 'done'
  foreach ($id in @('EXP_DESIGN','EXP_ACCEPT')) {
    $criterion = $designStaleState.exitCriteria | Where-Object id -eq $id
    $criterion | Add-Member applicable $false -Force; $criterion.status = 'skipped'
    $criterion | Add-Member skipReason '任务不涉及页面、交互或视觉验收' -Force
    $criterion | Add-Member approvedBy 'workflow-manager' -Force
    $criterion | Add-Member evidenceRef '.ai/docs/20260912-stcore-security-consistency/exp-review.md' -Force
  }
  $path = Save-Fixture 'TC-UI-05-design-revision-rechecks-skip.yaml' $designStaleState
  $r = Invoke-Loop @('stale', $path, '-RevisionKind', 'design', '-RevisionValue', 'design-next')
  $after = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
  $expAccept = $after.exitCriteria | Where-Object id -eq EXP_ACCEPT
  Expect 'TC-UI-05 设计 revision 会重新评估非适用体验标准' ($r.ExitCode -eq 0 -and $expAccept.status -eq 'stale' -and $after.status -eq 'running') $r.Text

  $uiState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $uiState.status = 'running'; $criterion = $uiState.exitCriteria | Where-Object id -eq EXP_DESIGN
  $criterion | Add-Member applicable $true -Force; $criterion.status = 'skipped'
  $criterion | Add-Member skipReason '错误跳过' -Force; $criterion | Add-Member approvedBy 'workflow-manager' -Force
  $criterion | Add-Member evidenceRef '.ai/docs/20260912-stcore-security-consistency/exp-review.md' -Force
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-UI-02-applicable-skip.yaml' $uiState))
  Expect 'TC-UI-02 适用的体验标准不得跳过' ($r.ExitCode -ne 0 -and $r.Text -match 'SKIP_NOT_ALLOWED') $r.Text

  $duplicateState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $duplicateState.status = 'done'
  $duplicateState.artifacts.archReview.ref = '.ai/docs/20260912-stcore-security-consistency/design.md'
  $duplicateState.artifacts.archReview.provides = @('TECH_DESIGN')
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-ART-03-duplicate-ref.yaml' $duplicateState))
  Expect 'TC-ART-03 两个设计标准不得由同一 ref 充数' ($r.ExitCode -ne 0 -and $r.Text -match 'CATALOG_ARTIFACT_MISSING') $r.Text

  $wrongTypeState = Get-Content -LiteralPath (Join-Path $RepoRoot '.ai\state\20260912-stcore-security-consistency.yaml') -Raw | ConvertFrom-Json
  $wrongTypeState.status = 'done'
  $wrongTypeState.artifacts.archReview.ref = 'AGENTS.md'
  $wrongTypeState.artifacts.archReview.provides = @('TECH_DESIGN')
  $wrongTypeState.artifacts.design.ref = 'README.md'
  $wrongTypeState.artifacts.design.provides = @('TECH_DESIGN')
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-ART-04-multi-artifact-wrong-type.yaml' $wrongTypeState))
  Expect 'TC-ART-04 多产物标准不得由无关文件替代' ($r.ExitCode -ne 0 -and $r.Text -match 'CATALOG_ARTIFACT_MISSING') $r.Text

  $singleWrongTypeState = Complete-State (New-MediumState)
  $singleWrongTypeState.status = 'done'
  $singleWrongTypeState.artifacts.PSObject.Properties.Remove('artifact-DESIGN')
  $singleWrongTypeState.artifacts | Add-Member unrelated ([pscustomobject][ordered]@{ status = 'done'; ref = 'AGENTS.md'; provides = @('DESIGN') }) -Force
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-ART-05-single-nonlegacy-wrong-type.yaml' $singleWrongTypeState))
  Expect 'TC-ART-05 单产物标准不得由无关文件替代' ($r.ExitCode -ne 0 -and $r.Text -match 'CATALOG_ARTIFACT_MISSING') $r.Text

  $legacyState = New-MediumState
  $legacyState | Add-Member legacy $true -Force
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-LEGACY-01-requires-reinit.yaml' $legacyState))
  Expect 'TC-LEGACY-01 历史 State 必须重新建立' ($r.ExitCode -ne 0 -and $r.Text -match 'LEGACY_STATE_REQUIRES_REINIT') $r.Text

  $s = Complete-State (New-MediumState); $s.status = 'done'; $path = Save-Fixture 'TC-REV-01.yaml' $s
  $r = Invoke-Loop @('stale', $path, '-RevisionKind', 'code', '-RevisionValue', 'code-r2')
  $after = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
  $staleIds = @($after.exitCriteria | Where-Object status -eq stale | ForEach-Object id)
  $wanted = @('CODE_REVIEW','SECURITY_REVIEW','TEST_PASS','KNOWLEDGE','ACCEPT')
  Expect 'TC-REV-01 code revision DAG stale' ($r.ExitCode -eq 0 -and @($wanted | Where-Object { $staleIds -notcontains $_ }).Count -eq 0 -and $after.status -eq 'running') ($r.Text + '; stale=' + ($staleIds -join ','))

  $s = New-MediumState; $s.revision.code = 'code-r2'; $s.dispatchLedger = @(New-DesignAttempt 'd1'); $path = Save-Fixture 'TC-REV-02.yaml' $s
  $proposal = [pscustomobject]@{ taskId='design-task'; dispatchId='d1'; criterionProposal = [pscustomobject]@{ id='DESIGN'; outcome='pass'; by='executor'; dispatchId='d1'; evidenceRef="$EvidenceRefRoot/proposal.json"; validatedRevision='old'; userConfirmedAt=$Now; confirmedBy='user'; confirmationArtifact='.ai/docs/20260911-agent-loop-v2/design.md' } }
  $proposalPath = Save-Fixture 'TC-REV-02-proposal.json' $proposal
  $r = Invoke-Loop @('evaluate', $path, '-ProposalPath', $proposalPath)
  Expect 'TC-REV-02 旧 revision 证据拒绝' ($r.ExitCode -ne 0 -and $r.Text -match 'REVISION_MISMATCH') $r.Text

  $s = New-MediumState; $s | Add-Member unexpectedField 'must-fail'
  $r = Invoke-Loop @('validate', (Save-Fixture 'TC-SCH-02-extra-field.yaml' $s))
  Expect 'TC-SCH-02 JSON Schema 拒绝额外字段' ($r.ExitCode -ne 0 -and $r.Text -match 'SCHEMA_VALIDATION_FAILED') $r.Text

  $envelope = [pscustomobject][ordered]@{ schemaVersion=2; dispatchId='d'; taskId='t'; idempotencyKey='k'; role='executor'; taskType='implement'; objective='x'; taskRefs=@('task.md'); stateRef='state.yaml'; scope=[pscustomobject]@{include=@('.ai/**');exclude=@()}; acceptance=@('a'); validation=@('v'); forbidSpawn=$true; unexpected='must-fail' }
  $dispatchPath = Save-Fixture 'TC-DSP-01-extra-field.json' $envelope
  $r = Invoke-Loop @('validate-dispatch','-DispatchPath',$dispatchPath)
  Expect 'TC-DSP-01 Dispatch Schema 拒绝额外字段' ($r.ExitCode -ne 0 -and $r.Text -match 'DISPATCH_SCHEMA_INVALID') $r.Text

  $envelope.PSObject.Properties.Remove('unexpected'); $envelope.stateRef='../outside.yaml'
  $r = Invoke-Loop @('validate-dispatch','-DispatchPath',(Save-Fixture 'TC-DSP-PATH.json' $envelope))
  Expect 'TC-DSP-12 Dispatch 路径逃逸拒绝' ($r.ExitCode -ne 0 -and $r.Text -match 'DISPATCH_PATH_INVALID') $r.Text

  $envelope.stateRef='state.yaml'; $envelope | Add-Member skillRefs @('mysql/SKILL.md') -Force
  $r = Invoke-Loop @('validate-dispatch','-DispatchPath',(Save-Fixture 'TC-DSP-SKILL-01.json' $envelope))
  Expect 'TC-DSP-SKILL-01 技能注册表标识允许' ($r.ExitCode -eq 0) $r.Text

  $envelope.skillRefs=@('../outside/SKILL.md')
  $r = Invoke-Loop @('validate-dispatch','-DispatchPath',(Save-Fixture 'TC-DSP-SKILL-02.json' $envelope))
  Expect 'TC-DSP-SKILL-02 技能路径穿越拒绝' ($r.ExitCode -ne 0 -and $r.Text -match 'DISPATCH_PATH_INVALID') $r.Text

  $s = New-MediumState; $s.dispatchLedger = @(New-DesignAttempt 'bad-outcome'); $path = Save-Fixture 'TC-EVAL-OUTCOME.yaml' $s
  $proposal = [pscustomobject]@{ taskId='design-task'; dispatchId='bad-outcome'; criterionProposal=[pscustomobject]@{ id='DESIGN'; outcome='anything'; by='executor'; dispatchId='bad-outcome'; evidenceRef="$EvidenceRefRoot/proposal.json"; validatedRevision='design-r1' } }
  $r = Invoke-Loop @('evaluate', $path, '-ProposalPath', (Save-Fixture 'TC-EVAL-OUTCOME-proposal.json' $proposal))
  Expect 'TC-EVAL-01 非法 outcome 不得完成' ($r.ExitCode -ne 0 -and $r.Text -match 'PROPOSAL_INVALID') $r.Text

  $s = New-MediumState; $s.dispatchLedger = @(New-DesignAttempt 'review-fail'); $path = Save-Fixture 'TC-EVAL-FAIL.yaml' $s
  $proposal = [pscustomobject]@{ taskId='design-task'; dispatchId='review-fail'; criterionProposal=[pscustomobject]@{ id='DESIGN'; outcome='fail'; by='executor'; dispatchId='review-fail'; evidenceRef="$EvidenceRefRoot/proposal.json"; validatedRevision='design-r1' } }
  $r = Invoke-Loop @('evaluate', $path, '-ProposalPath', (Save-Fixture 'TC-EVAL-FAIL-proposal.json' $proposal)); $after = Get-Content $path -Raw | ConvertFrom-Json
  Expect 'TC-EVAL-02 fail 标 blocked 而非 done' ($r.ExitCode -eq 0 -and $after.exitCriteria[0].status -eq 'blocked' -and $after.dispatchLedger[0].status -eq 'evaluated') $r.Text

  $s = New-MediumState; $s.dispatchLedger = @(New-DesignAttempt 'result-mismatch'); $path = Save-Fixture 'TC-EVAL-BIND.yaml' $s
  $proposal = [pscustomobject]@{ taskId='design-task'; dispatchId='result-mismatch'; criterionProposal=[pscustomobject]@{ id='DESIGN'; outcome='pass'; by='executor'; dispatchId='result-mismatch'; evidenceRef="$EvidenceRefRoot/ac-1.json"; validatedRevision='design-r1'; userConfirmedAt=$Now; confirmedBy='user'; confirmationArtifact='.ai/docs/20260911-agent-loop-v2/design.md' } }
  $r = Invoke-Loop @('evaluate',$path,'-ProposalPath',(Save-Fixture 'TC-EVAL-BIND-proposal.json' $proposal))
  Expect 'TC-EVAL-03 proposal 必须绑定 attempt resultRef' ($r.ExitCode -ne 0 -and $r.Text -match 'DISPATCH_RESULT_MISMATCH') $r.Text

  $s = Complete-State (New-MediumState); $s.acceptanceEvidence[0].validatedRevision='old-code'; $r = Invoke-Loop @('validate',(Save-Fixture 'TC-ACC-REV.yaml' $s))
  Expect 'TC-ACC-04 验收逐项证据绑定当前 revision' ($r.ExitCode -ne 0 -and $r.Text -match 'ACCEPTANCE_REVISION_MISMATCH') $r.Text

  $s = Complete-State (New-MediumState); $s.dispatchLedger=@(New-DesignAttempt 'unfinished' 'running'); $r = Invoke-Loop @('complete',(Save-Fixture 'TC-COMPLETE-DSP.yaml' $s))
  Expect 'TC-COMPLETE-01 未结束 dispatch 阻止完成' ($r.ExitCode -ne 0 -and $r.Text -match 'DISPATCH_NOT_RECONCILED') $r.Text

  $s = New-MediumState; $s.artifacts=[pscustomobject]@{ report=[pscustomobject]@{status='done'} }; $r=Invoke-Loop @('validate',(Save-Fixture 'TC-ART-01.yaml' $s))
  Expect 'TC-ART-01 done artifact 必须有真实 ref' ($r.ExitCode -ne 0 -and $r.Text -match 'ARTIFACT_REF_MISSING') $r.Text

  $s = Complete-State (New-MediumState); $s.artifacts.PSObject.Properties.Remove('artifact-DESIGN')
  $r = Invoke-Loop @('complete', (Save-Fixture 'TC-ART-02-missing-required.yaml' $s))
  Expect 'TC-ART-02 完成前缺必需产物被拒绝' ($r.ExitCode -ne 0 -and $r.Text -match 'CATALOG_ARTIFACT_MISSING') $r.Text

  $s = New-MediumState; $s.dispatchLedger = @(
    (New-DesignAttempt 'old-attempt' 'returned'),
    (New-DesignAttempt 'new-attempt' 'running')
  ); $path = Save-Fixture 'TC-DSP-05.yaml' $s
  $proposal = [pscustomobject]@{ taskId='design-task'; dispatchId='old-attempt'; criterionProposal=[pscustomobject]@{ id='DESIGN'; outcome='pass'; by='executor'; dispatchId='old-attempt'; evidenceRef="$EvidenceRefRoot/proposal.json"; validatedRevision='design-r1'; userConfirmedAt=$Now; confirmedBy='user'; confirmationArtifact='.ai/docs/20260911-agent-loop-v2/design.md' } }
  $r = Invoke-Loop @('evaluate', $path, '-ProposalPath', (Save-Fixture 'TC-DSP-05-proposal.json' $proposal))
  Expect 'TC-DSP-05 旧 attempt 不得覆盖当前 attempt' ($r.ExitCode -ne 0 -and $r.Text -match 'DISPATCH_ATTEMPT_STALE') $r.Text

  $s = New-MediumState; $s.dispatchLedger = @(New-DesignAttempt 'lifecycle' 'planned'); $path = Save-Fixture 'TC-DSP-07.yaml' $s
  $ok = $true; foreach ($next in @('spawned','acked','running','returned')) { $r = Invoke-Loop @('dispatch-transition',$path,'-DispatchId','lifecycle','-DispatchStatus',$next); if ($r.ExitCode -ne 0) { $ok=$false } }
  $rBad = Invoke-Loop @('dispatch-transition',$path,'-DispatchId','lifecycle','-DispatchStatus','acked')
  Expect 'TC-DSP-07 生命周期只允许合法前驱' ($ok -and $rBad.ExitCode -ne 0 -and $rBad.Text -match 'DISPATCH_TRANSITION_INVALID') $rBad.Text

  $s = New-MediumState; $path = Save-Fixture 'TC-BLK-01.yaml' $s
  $r = Invoke-Loop @('report-blocker',$path,'-Fingerprint','stable-fp'); $ok = $r.ExitCode -eq 0
  foreach ($i in 1..3) { $r = Invoke-Loop @('repair-blocker',$path,'-Fingerprint','stable-fp','-RepairEvidence',"$EvidenceRefRoot/proposal.json",'-RepairOutcome','open','-EventId',"repair-$i"); if ($r.ExitCode -ne 0) { $ok=$false } }
  $after = Get-Content $path -Raw | ConvertFrom-Json
  Expect 'TC-BLK-01 三次无效修复后升级' ($ok -and $after.blockers.Count -eq 1 -and $after.blockers[0].repairAttempts.Count -eq 3 -and $after.blockers[0].status -eq 'escalated' -and $after.status -eq 'blocked_escalation') $r.Text
}
finally {
  if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
}

if ($script:Failed -gt 0) { Write-Host "FAILED=$script:Failed"; exit 1 }
Write-Host 'ALL PASSED'; exit 0
