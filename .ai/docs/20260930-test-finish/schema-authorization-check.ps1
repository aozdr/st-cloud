$ErrorActionPreference='Stop'
$testOutput=Join-Path $PSScriptRoot 'schema-check-fixtures'
New-Item -ItemType Directory -Force $testOutput | Out-Null
$actualState=Get-Content (Join-Path $PSScriptRoot '../../state/20260930-test-finish.json') -Raw | ConvertFrom-Json
foreach($case in @('authorized','missing-criterion','wrong-task','unknown-criterion')) {
    $caseState=$actualState | ConvertTo-Json -Depth 40 | ConvertFrom-Json
    foreach($syntheticCriterion in $caseState.exitCriteria) { if($syntheticCriterion.id -notin @('REQ_ANALYSIS','IMPACT_ANALYSIS')) { $syntheticCriterion.status='pending' } }
    $caseState.history=@()
    if($case -eq 'missing-criterion') { $caseState.singleAgentAuthorization.criteria=@($caseState.singleAgentAuthorization.criteria | Where-Object {$_ -ne 'EXP_DESIGN'}) }
    if($case -eq 'wrong-task') { $caseState.singleAgentAuthorization.taskId='SYNTHETIC-OTHER-TASK' }
    if($case -eq 'unknown-criterion') { $caseState.singleAgentAuthorization.criteria+= 'UNKNOWN_REVIEW' }
    $casePath=Join-Path $testOutput "$case.json"
    $caseState | ConvertTo-Json -Depth 40 | Set-Content $casePath -Encoding utf8
    $proposal=@{taskId=$caseState.taskId;criterionProposal=@{id='EXP_DESIGN';outcome='pass';by='root';evidenceRef='.ai/docs/20260930-test-finish/exp-review.md';validatedRevision=$caseState.revision.design}}
    $proposalPath=Join-Path $testOutput 'proposal.json'
    $proposal | ConvertTo-Json -Depth 8 | Set-Content $proposalPath -Encoding utf8
    & (Join-Path $PSScriptRoot '../../scripts/loopctl.ps1') evaluate-direct $casePath -ProposalPath $proposalPath -Actor root -DryRun
    $caseExit=$LASTEXITCODE
    if($case -eq 'authorized' -and $caseExit -ne 0) {throw '合法授权未通过'}
    if($case -ne 'authorized' -and $caseExit -eq 0) {throw "越权夹具误通过：$case"}
    Write-Output "PASS $case exit=$caseExit；仅合成夹具/DryRun，未修改当前State"
}
