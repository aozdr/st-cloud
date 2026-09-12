[CmdletBinding()]param()
$ErrorActionPreference='Stop'
$gitEnvNames=@('GIT_DIR','GIT_WORK_TREE','GIT_INDEX_FILE','GIT_COMMON_DIR','GIT_PREFIX')
$gitEnvBackup=@{}
foreach($name in $gitEnvNames){$gitEnvBackup[$name]=[Environment]::GetEnvironmentVariable($name,'Process');Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
$env:XDG_CONFIG_HOME=Join-Path ([IO.Path]::GetTempPath()) 'codex-worktree-v2-xdg'
New-Item -ItemType Directory -Path $env:XDG_CONFIG_HOME -Force | Out-Null
$sut=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\scripts\worktree.ps1'))
$pwsh=(Get-Process -Id $PID).Path
$passed=0;$failed=0

function Exec([string]$File,[string[]]$Arguments,[string]$At,[switch]$AllowFailure){
 Push-Location $At
 $previousErrorAction=$ErrorActionPreference
 $ErrorActionPreference='Continue'
 try{$out=& $File @Arguments 2>&1;$code=$LASTEXITCODE}
 finally{$ErrorActionPreference=$previousErrorAction;Pop-Location}
 if($code-ne0-and-not$AllowFailure){throw "$File exit=$code :: $($out-join'; ')"};[pscustomobject]@{Code=$code;Output=@($out)}
}
function G([string]$Repo,[string[]]$Arguments,[switch]$AllowFailure){Exec 'git' $Arguments $Repo -AllowFailure:$AllowFailure}
function WT([string]$Repo,[string[]]$Arguments,[switch]$AllowFailure){Exec $pwsh (@('-NoProfile','-ExecutionPolicy','Bypass','-File',$sut)+$Arguments) $Repo -AllowFailure:$AllowFailure}
function Check([bool]$Value,[string]$Message){if(-not$Value){throw $Message}}
function Has([object[]]$Output,[string]$Pattern){if(($Output-join"`n")-notmatch$Pattern){throw "缺少 $Pattern :: $($Output-join'; ')"}}
function Repo {
 $r=Join-Path ([IO.Path]::GetTempPath()) ('worktree-v2-'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $r|Out-Null
 G $r @('init','-b','main')|Out-Null;G $r @('config','user.email','wt@example.invalid')|Out-Null;G $r @('config','user.name','WT Test')|Out-Null
 New-Item -ItemType Directory (Join-Path $r 'allowed')|Out-Null;Set-Content (Join-Path $r 'README.md') 'base';Set-Content (Join-Path $r 'allowed\seed.txt') 'seed'
 G $r @('add','.')|Out-Null;G $r @('commit','-m','base')|Out-Null;$r
}
function Test([string]$Name,[scriptblock]$Body){try{&$Body;$script:passed++;"PASS $Name"}catch{$script:failed++;"FAIL $Name :: $($_.Exception.Message)"}}

Test 'TC-WT-01/04 全流程和重试' {
 $r=Repo;try{
  $p=@('-Action','prepare','-TaskCode','happy','-Include','allowed/**');WT $r $p|Out-Null;Has (WT $r $p).Output 'ALREADY_PREPARED'
  Set-Content (Join-Path $r '.ai\worktrees\happy\allowed\x.txt') 'x';WT $r @('-Action','commit','-TaskCode','happy','-Message','x')|Out-Null;Has (WT $r @('-Action','commit','-TaskCode','happy')).Output 'ALREADY_COMMITTED'
  WT $r @('-Action','merge','-TaskCode','happy')|Out-Null;Has (WT $r @('-Action','merge','-TaskCode','happy')).Output 'ALREADY_MERGED'
  WT $r @('-Action','verify','-TaskCode','happy')|Out-Null;WT $r @('-Action','verify','-TaskCode','happy')|Out-Null
  WT $r @('-Action','cleanup','-TaskCode','happy')|Out-Null;Has (WT $r @('-Action','cleanup','-TaskCode','happy')).Output 'ALREADY_CLEANED';Has (WT $r @('-Action','reconcile','-Strict')).Output 'issues=0'
 }finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}
}
Test 'TC-WT-03 scope 越界' {
 foreach($mode in @('outside','excluded')){$r=Repo;try{
  WT $r @('-Action','prepare','-TaskCode',$mode,'-Include','allowed/**','-Exclude','allowed/private/**')|Out-Null;$w=Join-Path $r ".ai\worktrees\$mode"
  if($mode-eq'outside'){Set-Content (Join-Path $w 'outside.txt') 'bad'}else{New-Item -ItemType Directory (Join-Path $w 'allowed\private')|Out-Null;Set-Content (Join-Path $w 'allowed\private\secret.txt') 'bad'}
  $before=(G $w @('rev-parse','HEAD')).Output[0];$x=WT $r @('-Action','commit','-TaskCode',$mode,'-Message','bad') -AllowFailure;$after=(G $w @('rev-parse','HEAD')).Output[0]
  Check ($x.Code-ne0) '越界应非零';Has $x.Output 'WT_SCOPE_VIOLATION';Check ($before-eq$after) '越界产生了 commit'
 }finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}}
}
Test 'TC-WT-02/05 commit 后 merge 失败可恢复' {
 $r=Repo;try{
  WT $r @('-Action','prepare','-TaskCode','retry','-Include','allowed/**')|Out-Null;$w=Join-Path $r '.ai\worktrees\retry';Set-Content (Join-Path $w 'allowed\seed.txt') 'worktree';WT $r @('-Action','commit','-TaskCode','retry','-Message','worktree')|Out-Null
  Set-Content (Join-Path $r 'allowed\seed.txt') 'main';G $r @('add','allowed/seed.txt')|Out-Null;G $r @('commit','-m','drift')|Out-Null
  $x=WT $r @('-Action','merge','-TaskCode','retry') -AllowFailure;Check ($x.Code-ne0) 'HEAD 漂移应非零';Has $x.Output 'WT_MAIN_HEAD_DRIFT';G $r @('reset','--hard','HEAD~1')|Out-Null
  WT $r @('-Action','merge','-TaskCode','retry')|Out-Null;WT $r @('-Action','verify','-TaskCode','retry')|Out-Null
 }finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}
}
Test 'TC-WT-05 主树脏和分支错误' {
 $r=Repo;try{
  WT $r @('-Action','prepare','-TaskCode','guards','-Include','allowed/**')|Out-Null;$w=Join-Path $r '.ai\worktrees\guards';Set-Content (Join-Path $w 'allowed\x.txt') 'x';WT $r @('-Action','commit','-TaskCode','guards','-Message','x')|Out-Null
  Set-Content (Join-Path $r 'dirty.txt') 'dirty';$x=WT $r @('-Action','merge','-TaskCode','guards') -AllowFailure;Has $x.Output 'WT_MAIN_DIRTY';Remove-Item (Join-Path $r 'dirty.txt');G $r @('checkout','-b','wrong')|Out-Null
  $x=WT $r @('-Action','merge','-TaskCode','guards') -AllowFailure;Has $x.Output 'WT_TARGET_BRANCH_MISMATCH'
 }finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}
}
Test 'TC-WT-06 strict 孤儿对账' {
 $r=Repo;try{$p=Join-Path $r '.ai\worktrees\orphan';New-Item -ItemType Directory (Split-Path $p) -Force|Out-Null;G $r @('worktree','add','-b','codex/orphan',$p,'HEAD')|Out-Null
  $x=WT $r @('-Action','reconcile','-Strict') -AllowFailure;Check ($x.Code-ne0) 'strict 应非零';Has $x.Output 'ORPHAN_WORKTREE';Has $x.Output 'ORPHAN_BRANCH'
 }finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}
}
Test 'TC-WT-08 verify 前拒绝 cleanup' {$r=Repo;try{WT $r @('-Action','prepare','-TaskCode','early','-Include','allowed/**')|Out-Null;$x=WT $r @('-Action','cleanup','-TaskCode','early') -AllowFailure;Check ($x.Code-ne0) 'cleanup 应非零';Has $x.Output 'WT_VERIFY_REQUIRED'}finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}}

Test 'TC-WT-10 参数注入与路径穿越拒绝' {$r=Repo;try{
 $x=WT $r @('-Action','prepare','-TaskCode','badbranch','-TargetBranch','--help','-Include','allowed/**') -AllowFailure;Check ($x.Code-ne0) '选项形态分支名应非零';Has $x.Output 'WT_TARGET_BRANCH_INVALID'
 $x=WT $r @('-Action','prepare','-TaskCode','badscope','-Include','../outside/**') -AllowFailure;Check ($x.Code-ne0) '穿越 scope 应非零';Has $x.Output 'WT_SCOPE_PATTERN_INVALID'
}finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}}

Test 'TC-WT-11 篡改台账不变量拒绝' {$r=Repo;try{
 WT $r @('-Action','prepare','-TaskCode','tamper','-Include','allowed/**')|Out-Null
 $common=(G $r @('rev-parse','--git-common-dir')).Output[0];$ledgerPath=Join-Path $r "$common/codex-worktree-transactions/tamper.json";$ledger=Get-Content $ledgerPath -Raw|ConvertFrom-Json;$ledger.worktreePath=$r;$ledger|ConvertTo-Json -Depth 8|Set-Content $ledgerPath -Encoding UTF8
 $x=WT $r @('-Action','commit','-TaskCode','tamper','-Message','must-fail') -AllowFailure;Check ($x.Code-ne0) '篡改 worktreePath 应非零';Has $x.Output 'WT_LEDGER_PATH_ESCAPE'
}finally{if(Test-Path $r){Remove-Item $r -Recurse -Force}}}

"RESULT passed=$passed failed=$failed"
foreach($name in $gitEnvNames){
  if($null -eq $gitEnvBackup[$name]){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
  else{[Environment]::SetEnvironmentVariable($name,$gitEnvBackup[$name],'Process')}
}
if($failed-gt0){exit 1}
