param([string]$Envelope, [string]$Child, [string]$Criterion, [switch]$Returned, [switch]$Evaluate, [string]$Artifact)
$ErrorActionPreference='Stop'
$path='.ai/state/20260930-environment-remediation.yaml'
$envDoc=Get-Content -Raw -Encoding UTF8 $Envelope | ConvertFrom-Json
$state=Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
if(@($state.dispatchLedger | Where-Object dispatchId -eq $envDoc.dispatchId).Count -eq 0){
  $entry=[pscustomobject]@{dispatchId=$envDoc.dispatchId;taskId=$envDoc.taskId;idempotencyKey=$envDoc.idempotencyKey;role=$envDoc.role;taskType=$envDoc.taskType;criterionId=$Criterion;childId=$Child;status='planned';resultRef=$envDoc.output.resultRef;scopeVerified=$false}
  $state.dispatchLedger=@($state.dispatchLedger)+@($entry)
  $state | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 $path
  & .ai/scripts/loopctl.ps1 dispatch-transition $path -DispatchId $envDoc.dispatchId -DispatchStatus spawned
}
if($Returned){
  # ACK 三元组已由主线程核对真实 Runtime 消息；此脚本只串行登记迁移。
  $state=Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
  ($state.dispatchLedger | Where-Object dispatchId -eq $envDoc.dispatchId).scopeVerified=$true
  $state | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 $path
  foreach($status in 'acked','running','returned'){
    $current=Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
    $currentStatus=($current.dispatchLedger | Where-Object dispatchId -eq $envDoc.dispatchId).status
    $sequence=@('planned','spawned','acked','running','returned','evaluated')
    if($sequence.IndexOf($currentStatus) -ge $sequence.IndexOf($status)){continue}
    & .ai/scripts/loopctl.ps1 dispatch-transition $path -DispatchId $envDoc.dispatchId -DispatchStatus $status
    if($LASTEXITCODE -ne 0){throw 'dispatch transition failed'}
  }
  $state=Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
  ($state.dispatchLedger | Where-Object dispatchId -eq $envDoc.dispatchId).scopeVerified=$true
  if($Artifact){$state.artifacts | Add-Member -NotePropertyName $Artifact -NotePropertyValue ([pscustomobject]@{status='ready';ref=".ai/docs/20260930-environment-remediation/$Artifact"}) -Force}
  $state | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 $path
  if($Evaluate){
    & .ai/scripts/loopctl.ps1 evaluate $path -ProposalPath $envDoc.output.resultRef
    if($LASTEXITCODE -ne 0){throw 'Evaluate failed'}
  }else{
    & .ai/scripts/loopctl.ps1 dispatch-transition $path -DispatchId $envDoc.dispatchId -DispatchStatus evaluated
  }
}
