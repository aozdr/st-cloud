#requires -Version 7.0
[CmdletBinding()]param()
$ErrorActionPreference='Stop'
$repoRoot=Split-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) -Parent
$gitEnvNames=@('GIT_DIR','GIT_WORK_TREE','GIT_INDEX_FILE','GIT_COMMON_DIR','GIT_PREFIX')
$gitEnvBackup=@{}
foreach($name in $gitEnvNames){$gitEnvBackup[$name]=[Environment]::GetEnvironmentVariable($name,'Process');Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
$migrator=Join-Path $repoRoot '.ai\scripts\migrate-loop-state-v2.ps1'
$loopctl=Join-Path $repoRoot '.ai\scripts\loopctl.ps1'
$work=Join-Path $repoRoot ('.ai\tests\loop-v2\.tmp-phase6-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work|Out-Null
$script:failed=0
function Expect([string]$Name,[bool]$Ok,[string]$Detail){if($Ok){"[PASS] $Name"}else{"[FAIL] $Name -- $Detail";$script:failed++}}
function Run-Migration([string[]]$CommandArgs){$out=@(& pwsh -NoProfile -File $migrator @CommandArgs 2>&1|ForEach-Object{$_.ToString()});[pscustomobject]@{Code=$LASTEXITCODE;Text=$out-join"`n"}}
function Legacy([string]$Path,[string]$Id){@"
taskId: $Id
scale: medium
status: running
goal:
  objective: continue $Id
"@|Set-Content -LiteralPath $Path -Encoding utf8NoBOM}
function Invoke-GitTest([string]$At,[string[]]$CommandArgs){& git -C $At @CommandArgs 2>&1|Out-Null;if($LASTEXITCODE-ne0){throw "git failed: $($CommandArgs-join' ')"}}
function Remove-TestRoot([string]$Path){foreach($attempt in 1..10){try{if(Test-Path $Path){Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop};return}catch{if($attempt-eq10){throw};Start-Sleep -Milliseconds 100}}}
try{
  $states=Join-Path $work 'dry-states';$archive=Join-Path $work 'dry-archive';New-Item -ItemType Directory $states|Out-Null;$source=Join-Path $states 'one.yaml';Legacy $source 'one';$before=Get-Content $source -Raw
  $r=Run-Migration -CommandArgs @('-StateDirectory',$states,'-ArchiveDirectory',$archive);Expect 'TC-MIG-01 默认 dry-run 不写' ($r.Code -eq 0 -and (Get-Content $source -Raw) -eq $before -and -not (Test-Path $archive)) $r.Text
  $r=Run-Migration -CommandArgs @('-StateDirectory',$states,'-ArchiveDirectory',$archive,'-Apply','-WhatIf');Expect 'TC-MIG-00 Apply+WhatIf 不写' ($r.Code -eq 0 -and (Get-Content $source -Raw) -eq $before -and -not (Test-Path $archive)) $r.Text
  Legacy (Join-Path $states 'two.yaml') 'two';(Get-Content (Join-Path $states 'two.yaml') -Raw).Replace('status: running','status: incomplete')|Set-Content (Join-Path $states 'two.yaml') -Encoding utf8NoBOM
  $r=Run-Migration -CommandArgs @('-StateDirectory',$states,'-ArchiveDirectory',$archive,'-Apply');$v1=@(& pwsh -NoProfile -File $loopctl validate $source 2>&1);$c1=$LASTEXITCODE;$v2=@(& pwsh -NoProfile -File $loopctl validate (Join-Path $states 'two.yaml') 2>&1);$c2=$LASTEXITCODE;Expect 'TC-MIG-03 全部 running/incomplete 迁移后可续跑' ($r.Code -eq 0 -and $c1 -eq 0 -and $c2 -eq 0 -and (Test-Path (Join-Path $archive 'one.yaml')) -and (Test-Path (Join-Path $archive 'two.yaml'))) ($r.Text+'; '+($v1-join';')+'; '+($v2-join';'))

  $doneStates=Join-Path $work 'done-states';$doneArchive=Join-Path $work 'done-archive';New-Item -ItemType Directory $doneStates|Out-Null;$doneSource=Join-Path $doneStates 'done.yaml';@('taskId: done','scale: medium','status: done','legacy: true')|Set-Content $doneSource -Encoding utf8NoBOM;$doneBefore=Get-Content $doneSource -Raw;$r=Run-Migration -CommandArgs @('-StateDirectory',$doneStates,'-ArchiveDirectory',$doneArchive,'-Apply');Expect 'TC-MIG-02 完成态历史 State 不变' ($r.Code -eq 0 -and (Get-Content $doneSource -Raw) -eq $doneBefore -and -not(Test-Path (Join-Path $doneArchive 'done.yaml'))) $r.Text

  $states2=Join-Path $work 'conflict-states';$archive2=Join-Path $work 'conflict-archive';New-Item -ItemType Directory $states2,$archive2|Out-Null;Legacy (Join-Path $states2 'a.yaml') 'a';Legacy (Join-Path $states2 'b.yaml') 'b';Set-Content (Join-Path $archive2 'b.yaml') 'conflict';$aBefore=Get-Content (Join-Path $states2 'a.yaml') -Raw
  $r=Run-Migration -CommandArgs @('-StateDirectory',$states2,'-ArchiveDirectory',$archive2,'-Apply');Expect 'TC-MIG-04 冲突批量预检不半写' ($r.Code -ne 0 -and (Get-Content (Join-Path $states2 'a.yaml') -Raw) -eq $aBefore -and -not (Test-Path (Join-Path $archive2 'a.yaml'))) $r.Text
  Remove-Item (Join-Path $archive2 'b.yaml');$r=Run-Migration -CommandArgs @('-StateDirectory',$states2,'-ArchiveDirectory',$archive2,'-Apply','-FailAfterArchiveForTaskId','a');Expect 'TC-MIG-04b Copy 后故障可回滚重试' ($r.Code -ne 0 -and (Get-Content (Join-Path $states2 'a.yaml') -Raw) -eq $aBefore -and -not(Test-Path (Join-Path $archive2 'a.yaml'))) $r.Text

  $hook=Get-Content (Join-Path $repoRoot '.ai\githooks\pre-commit') -Raw;$workflow=Get-Content (Join-Path $repoRoot '.github\workflows\ai-loop-gate.yml') -Raw
  Expect 'TC-CI-01 本地 hook 与 CI 使用同一严格入口' ($hook-match'run-loop-gate\.ps1' -and $workflow-match'run-loop-gate\.ps1') 'gate entry mismatch'
  Expect 'TC-CI-02 hook fail-closed 且拒绝 staged/working 漂移' ($hook-match'git diff --cached --name-only\)' -and $hook-match'git diff --name-only -- AGENTS.md \.ai' -and $hook-match'git ls-files --others --exclude-standard -- AGENTS.md \.ai' -and $hook-match'PowerShell not found[\s\S]*exit 1') 'hook guard missing'
  Expect 'TC-CI-03 workflow 自身变更触发门禁' ($workflow-match'\.github/workflows/ai-loop-gate\.yml') 'workflow self path missing'
  $activeFiles=@(Get-ChildItem (Join-Path $repoRoot '.ai\scripts'),(Join-Path $repoRoot '.ai\agents'),(Join-Path $repoRoot '.ai\templates') -Recurse -File);$hits=@($activeFiles|Select-String -Pattern 'DISPATCH_READY|file-dispatch-runtime|parallel-dispatch-runtime-v[78]' -CaseSensitive:$false)
  Expect 'TC-CI-04 archive 外无旧 Dispatch 活跃执行入口' ($hits.Count-eq0) (($hits|ForEach-Object{$_.Path+':'+$_.LineNumber})-join';')

  $gitCommand=@(Get-Command git.exe -All | Where-Object { $_.Source -match '\\cmd\\git\.exe$' -or $_.Path -match '\\cmd\\git\.exe$' } | Select-Object -First 1)
  if ($gitCommand.Count -eq 0) { $gitCommand=@(Get-Command git.exe -ErrorAction Stop | Select-Object -First 1) }
  $gitRoot=Split-Path (Split-Path $gitCommand[0].Source -Parent) -Parent
  $sh=Join-Path $gitRoot 'bin\sh.exe'
  if (-not (Test-Path -LiteralPath $sh)) { $sh=Join-Path $gitRoot 'usr\bin\sh.exe' }
  if (-not (Test-Path -LiteralPath $sh)) { throw "Git Shell not found under $gitRoot" }
  $hookPath=(Join-Path $repoRoot '.ai\githooks\pre-commit').Replace('\','/')
  $hookRepo=Join-Path $work 'hook-staged';New-Item -ItemType Directory $hookRepo|Out-Null;Invoke-GitTest $hookRepo @('init','-b','main');Invoke-GitTest $hookRepo @('config','user.email','hook@example.invalid');Invoke-GitTest $hookRepo @('config','user.name','Hook Test');Set-Content (Join-Path $hookRepo 'AGENTS.md') 'base';Invoke-GitTest $hookRepo @('add','.');Invoke-GitTest $hookRepo @('commit','-m','base');Set-Content (Join-Path $hookRepo 'AGENTS.md') 'staged-invalid';Invoke-GitTest $hookRepo @('add','AGENTS.md');Set-Content (Join-Path $hookRepo 'AGENTS.md') 'working-valid';Push-Location $hookRepo;try{$out=@(& $sh $hookPath 2>&1|ForEach-Object{$_.ToString()});$code=$LASTEXITCODE}finally{Pop-Location};Expect 'TC-CI-05 staged-invalid/working-valid 被拒绝' ($code-ne0-and($out-join"`n")-match'unstaged or untracked') ($out-join';')
  $deleteRepo=Join-Path $work 'hook-delete';New-Item -ItemType Directory $deleteRepo|Out-Null;Invoke-GitTest $deleteRepo @('init','-b','main');Invoke-GitTest $deleteRepo @('config','user.email','hook@example.invalid');Invoke-GitTest $deleteRepo @('config','user.name','Hook Test');New-Item -ItemType Directory (Join-Path $deleteRepo '.ai')|Out-Null;Set-Content (Join-Path $deleteRepo '.ai\control.md') 'base';Invoke-GitTest $deleteRepo @('add','.');Invoke-GitTest $deleteRepo @('commit','-m','base');Remove-Item (Join-Path $deleteRepo '.ai\control.md');Invoke-GitTest $deleteRepo @('add','-A');Push-Location $deleteRepo;try{$out=@(& $sh $hookPath 2>&1|ForEach-Object{$_.ToString()});$code=$LASTEXITCODE}finally{Pop-Location};Expect 'TC-CI-06 纯删除控制文件仍触发门禁' (($out-join"`n")-match'changed -> running') ($out-join';')
  $untrackedRepo=Join-Path $work 'hook-untracked';New-Item -ItemType Directory $untrackedRepo|Out-Null;Invoke-GitTest $untrackedRepo @('init','-b','main');Invoke-GitTest $untrackedRepo @('config','user.email','hook@example.invalid');Invoke-GitTest $untrackedRepo @('config','user.name','Hook Test');New-Item -ItemType Directory (Join-Path $untrackedRepo '.ai\tasks'),(Join-Path $untrackedRepo '.ai\docs') -Force|Out-Null;Set-Content (Join-Path $untrackedRepo 'README.md') 'base';Invoke-GitTest $untrackedRepo @('add','.');Invoke-GitTest $untrackedRepo @('commit','-m','base');Set-Content (Join-Path $untrackedRepo '.ai\tasks\TASK.md') 'evidence .ai/docs/x.md';Invoke-GitTest $untrackedRepo @('add','.ai/tasks/TASK.md');Set-Content (Join-Path $untrackedRepo '.ai\docs\x.md') 'untracked';Push-Location $untrackedRepo;try{$out=@(& $sh $hookPath 2>&1|ForEach-Object{$_.ToString()});$code=$LASTEXITCODE}finally{Pop-Location};Expect 'TC-CI-07 未跟踪证据不得替暂存快照假绿' ($code-ne0-and($out-join"`n")-match'unstaged or untracked') ($out-join';')

  $noPwshRepo=Join-Path $work 'hook-no-pwsh';New-Item -ItemType Directory $noPwshRepo|Out-Null;Invoke-GitTest $noPwshRepo @('init','-b','main');Invoke-GitTest $noPwshRepo @('config','user.email','hook@example.invalid');Invoke-GitTest $noPwshRepo @('config','user.name','Hook Test');Set-Content (Join-Path $noPwshRepo 'AGENTS.md') 'base';Invoke-GitTest $noPwshRepo @('add','.');Invoke-GitTest $noPwshRepo @('commit','-m','base');Set-Content (Join-Path $noPwshRepo 'AGENTS.md') 'changed';Invoke-GitTest $noPwshRepo @('add','AGENTS.md');Push-Location $noPwshRepo;try{$cmd="PATH=/mingw64/bin:/usr/bin:/bin '$hookPath'";$out=@(& $sh -c $cmd 2>&1|ForEach-Object{$_.ToString()});$code=$LASTEXITCODE}finally{Pop-Location};Expect 'TC-CI-02 缺 PowerShell 时 fail-closed' ($code-ne0-and($out-join"`n")-match'PowerShell not found') ($out-join';')
  $outside=Join-Path ([IO.Path]::GetTempPath()) ('loop-hook-root-'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $outside|Out-Null;try{Push-Location $outside;try{$out=@(& $sh $hookPath 2>&1|ForEach-Object{$_.ToString()});$code=$LASTEXITCODE}finally{Pop-Location};Expect 'TC-CI-02 缺仓库根时 fail-closed' ($code-ne0-and($out-join"`n")-match'cannot resolve repository root') ($out-join';')}finally{Remove-TestRoot $outside}

  $worktreeScript=Join-Path $repoRoot '.ai\scripts\worktree.ps1';$r1=@(& pwsh -NoProfile -File $worktreeScript reconcile -Strict 2>&1);$c1=$LASTEXITCODE;$r2=@(& pwsh -NoProfile -File $worktreeScript reconcile -Strict 2>&1);$c2=$LASTEXITCODE;Expect 'TC-CI-05 启动与结束 strict reconcile' ($c1-eq0-and$c2-eq0) (($r1+$r2)-join';')
}finally{
  Remove-TestRoot $work
  foreach($name in $gitEnvNames){
    if($null -eq $gitEnvBackup[$name]){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
    else{[Environment]::SetEnvironmentVariable($name,$gitEnvBackup[$name],'Process')}
  }
}
if($script:failed){"FAILED=$script:failed";exit 1};'ALL PASSED';exit 0
