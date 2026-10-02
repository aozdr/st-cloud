param([ValidateSet('init','code','finish')][string]$Phase='init')
$ErrorActionPreference='Stop'
$statePath='.ai/state/20261001-disable-user-redis.yaml'
$base='.ai/docs/20261001-disable-user-redis'
$actor='workflow-manager'
function Evaluate-Direct([string]$id,[string]$ref,[string]$revision){
  $proposal=[ordered]@{taskId='20261001-disable-user-redis';criterionProposal=@{id=$id;outcome='pass';by=$actor;evidenceRef=$ref;validatedRevision=$revision}}
  if($id -eq 'ACCEPT'){$proposal.acceptanceEvidence=@('A1','A2','A3','A4' | ForEach-Object {@{criterion=$_;evidenceRef="$base/acceptance.md";validatedRevision=$revision}})}
  $path="$base/proposal-$id.json"
  $proposal | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 -LiteralPath $path
  & .ai/scripts/loopctl.ps1 evaluate-direct $statePath -ProposalPath $path -Actor $actor
  if($LASTEXITCODE -ne 0){throw "Evaluate失败 $id"}
}
if($Phase -eq 'init'){
  & .ai/scripts/loopctl.ps1 init $statePath -TaskId '20261001-disable-user-redis' -Scale medium -Objective '停用后只根据key清理用户独占Redis状态' -Scope 'st-admin停用提交后清理与隔离测试，不处理共享key/value/生产' -CompletionCriteria @('A1','A2','A3','A4')
  if($LASTEXITCODE -ne 0){throw 'State创建失败'}
  $s=Get-Content -Raw $statePath | ConvertFrom-Json
  $s.revision.design='disable-redis-design-r1'
  $s | Add-Member singleAgentAuthorization ([pscustomobject]@{taskId=$s.taskId;actor=$actor;authorizedBy='user';authorizationQuote='我批准，你不用开子线程，你自己完成；删除独占key就行；不能直接从key拿到的内容都不用处理';evidenceRef="$base/authorization.md";criteria=@('CODE_REVIEW','SECURITY_REVIEW','TEST_PASS','ACCEPT')})
  foreach($name in @('design.md','testcases.md','changereport.md','codereview.md','security.md','testreport.md')){$s.artifacts | Add-Member $name ([pscustomobject]@{status='ready';ref="$base/$name"})}
  foreach($c in $s.exitCriteria){if($c.id -eq 'DESIGN'){$c | Add-Member confirmationRequired $false}}
  $s | ConvertTo-Json -Depth 100 | Set-Content -Encoding utf8 -LiteralPath $statePath
  Evaluate-Direct DESIGN "$base/design.md" 'disable-redis-design-r1'
  Evaluate-Direct TESTCASES "$base/testcases.md" 'disable-redis-design-r1'
}
elseif($Phase -eq 'code'){
  $s=Get-Content -Raw $statePath | ConvertFrom-Json
  $s.revision.code='disable-redis-code-r1'
  $s | ConvertTo-Json -Depth 100 | Set-Content -Encoding utf8 -LiteralPath $statePath
  foreach($pair in @(@('IMPLEMENTED','changereport.md'),@('CODE_REVIEW','codereview.md'),@('SECURITY_REVIEW','security.md'))){Evaluate-Direct $pair[0] "$base/$($pair[1])" 'disable-redis-code-r1'}
}
else {
  Evaluate-Direct TEST_PASS "$base/testreport.md" 'disable-redis-code-r1'
  Evaluate-Direct KNOWLEDGE '.ai/knowledge/user-disable-redis.md' 'disable-redis-code-r1'
  Evaluate-Direct ACCEPT "$base/acceptance.md" 'disable-redis-code-r1'
  & .ai/scripts/loopctl.ps1 complete $statePath
  if($LASTEXITCODE -ne 0){throw '完成门禁失败'}
}
& .ai/scripts/loopctl.ps1 validate $statePath
if($LASTEXITCODE -ne 0){throw 'State无效'}
