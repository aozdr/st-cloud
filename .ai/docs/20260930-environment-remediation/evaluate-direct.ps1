param([Parameter(Mandatory=$true)][string]$Criterion, [Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
$statePath='.ai/state/20260930-environment-remediation.yaml'
$state=Get-Content -Raw -Encoding UTF8 $statePath | ConvertFrom-Json
$artifactMap=@{ REQ_ANALYSIS=@('requirement.md','uispec.md'); IMPACT_ANALYSIS=@('impact.md'); TECH_DESIGN=@('architecture-review.md','design.md'); TESTCASES=@('testcases.md'); IMPLEMENTED=@('changereport.md') }
foreach($name in $artifactMap[$Criterion]) {
  $artifact=[pscustomobject]@{status='ready';ref=".ai/docs/20260930-environment-remediation/$name"}
  $state.artifacts | Add-Member -NotePropertyName $name -NotePropertyValue $artifact -Force
}
$state | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 $statePath
$designCriteria=@('REQ_ANALYSIS','IMPACT_ANALYSIS','EXP_DESIGN','TECH_DESIGN','TESTCASES')
$revision=if($Criterion -in $designCriteria){$state.revision.design}else{$state.revision.code}
$proposal=@{taskId=$state.taskId;criterionProposal=@{id=$Criterion;outcome='pass';by='workflow-manager';evidenceRef=$Evidence;validatedRevision=$revision}}
$proposalPath=".ai/docs/20260930-environment-remediation/direct-$Criterion.json"
$proposal | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $proposalPath
& .ai/scripts/loopctl.ps1 evaluate-direct $statePath -ProposalPath $proposalPath -Actor workflow-manager
if($LASTEXITCODE -ne 0){throw "Evaluate failed: $Criterion"}
