# Worktree V2：prepare -> commit -> merge -> verify -> cleanup，可独立重试。
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('prepare','commit','merge','verify','cleanup','reconcile','list')]
    [string]$Action,
    [string]$TaskCode,
    [string]$Message,
    [string]$TargetBranch='main',
    [string[]]$Include=@(),
    [string[]]$Exclude=@(),
    [switch]$Strict
)
$ErrorActionPreference='Stop'

function Fail([string]$Code,[string]$Detail) { throw "${Code}: $Detail" }
function Invoke-GitV2([string]$At,[string[]]$GitArgs,[switch]$AllowFailure) {
    # Windows PowerShell 会把 Git 写入 stderr 的正常进度信息转换成
    # NativeCommandError；临时放宽后仍以进程退出码作为唯一成败依据。
    $previousErrorAction=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try {
        $out = if ($At) { & git -C $At @GitArgs 2>&1 } else { & git @GitArgs 2>&1 }
        $code=$LASTEXITCODE
    }
    finally { $ErrorActionPreference=$previousErrorAction }
    if ($code -ne 0 -and -not $AllowFailure) { Fail 'WT_GIT_FAILED' "git $($GitArgs -join ' ') (exit=$code): $($out -join '; ')" }
    # Git 在受限账户下可能把不可读的全局 ignore 警告写到 stderr；成功命令的该警告不是命令数据。
    $data = if ($code -eq 0) { @($out | Where-Object { ([string]$_) -notmatch '^warning: unable to access ' }) } else { @($out) }
    [pscustomobject]@{ Code=$code; Output=$data }
}
function One([string]$At,[string[]]$GitArgs) { ((Invoke-GitV2 $At $GitArgs).Output | Select-Object -First 1).ToString().Trim() }

$RepoRoot=[IO.Path]::GetFullPath((One '' @('rev-parse','--show-toplevel')))
$common=One $RepoRoot @('rev-parse','--git-common-dir')
$CommonDir=if ([IO.Path]::IsPathRooted($common)) {[IO.Path]::GetFullPath($common)} else {[IO.Path]::GetFullPath((Join-Path $RepoRoot $common))}
$LedgerDir=Join-Path $CommonDir 'codex-worktree-transactions'
$WorktreeRoot=Join-Path $RepoRoot '.ai\worktrees'

function Assert-Code {
    if ([string]::IsNullOrWhiteSpace($TaskCode)) { Fail 'WT_TASK_REQUIRED' 'TaskCode 不能为空' }
    if ($TaskCode -notmatch '^[A-Za-z0-9_-]+$') { Fail 'WT_TASK_INVALID' "TaskCode 非法：$TaskCode" }
}
function Assert-BranchName([string]$Name) {
    if([string]::IsNullOrWhiteSpace($Name) -or $Name.StartsWith('-')){Fail 'WT_TARGET_BRANCH_INVALID' "目标分支非法：$Name"}
    if((Invoke-GitV2 $RepoRoot @('check-ref-format','--branch',$Name) -AllowFailure).Code-ne0){Fail 'WT_TARGET_BRANCH_INVALID' "目标分支非法：$Name"}
}
function Ledger-Path { Join-Path $LedgerDir "$TaskCode.json" }
function Worktree-Path { [IO.Path]::GetFullPath((Join-Path $WorktreeRoot $TaskCode)) }
function Branch-Name { "codex/$TaskCode" }
function Assert-Ledger([object]$Ledger) {
    Assert-Code
    foreach($field in @('schemaVersion','taskCode','baseSha','targetBranch','worktreePath','branch','include','exclude')){if($null-eq$Ledger.PSObject.Properties[$field]){Fail 'WT_LEDGER_INVALID' "缺少字段：$field"}}
    if([int]$Ledger.schemaVersion-ne2 -or [string]$Ledger.taskCode-ne$TaskCode){Fail 'WT_LEDGER_INVALID' 'schemaVersion/taskCode 不匹配'}
    if([IO.Path]::GetFullPath([string]$Ledger.worktreePath)-ne(Worktree-Path)){Fail 'WT_LEDGER_PATH_ESCAPE' 'worktreePath 不等于受控任务路径'}
    if([string]$Ledger.branch-ne(Branch-Name)){Fail 'WT_LEDGER_BRANCH_INVALID' 'branch 不等于受控任务分支'}
    Assert-BranchName ([string]$Ledger.targetBranch)
    foreach($sha in @($Ledger.baseSha,$Ledger.commitSha,$Ledger.mergedSha)|Where-Object{$_}){if([string]$sha-notmatch'^[0-9a-fA-F]{40,64}$'){Fail 'WT_LEDGER_SHA_INVALID' "非法 SHA：$sha"}}
    if(@($Ledger.include).Count-eq0){Fail 'WT_SCOPE_INCLUDE_REQUIRED' 'ledger scope.include 不能为空'}
    foreach($pattern in @($Ledger.include)+@($Ledger.exclude)){[void](Pattern-Regex ([string]$pattern))}
}
function Read-Ledger {
    Assert-Code; $p=Ledger-Path
    if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { Fail 'WT_LEDGER_MISSING' "台账不存在：$p" }
    $ledger=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json
    Assert-Ledger $ledger
    $ledger
}
function Write-Ledger([object]$Ledger) {
    New-Item -ItemType Directory -Path $LedgerDir -Force | Out-Null
    $p=Ledger-Path; $tmp="$p.tmp"; $Ledger.updatedAt=(Get-Date).ToUniversalTime().ToString('o')
    $Ledger | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $p -Force
}
function Head([string]$At) { One $At @('rev-parse','HEAD') }
function Branch([string]$At) { One $At @('branch','--show-current') }
function Assert-Clean([string]$At,[string]$Code) {
    $statusArgs=@('status','--porcelain=v1','--untracked-files=all')
    if ([IO.Path]::GetFullPath($At) -eq $RepoRoot) {
        # 隔离 checkout 位于仓库目录内，但不属于主树改动；其自身状态另行核验。
        $statusArgs += @('--','.',':(exclude).ai/worktrees/**')
    }
    $s=Invoke-GitV2 $At $statusArgs
    if ($s.Output.Count -gt 0) { Fail $Code "工作树不干净：$At；$($s.Output -join '; ')" }
}
function Assert-Target([string]$Expected) {
    $actual=Branch $RepoRoot
    if ($actual -ne $Expected) { Fail 'WT_TARGET_BRANCH_MISMATCH' "当前分支 $actual，期望 $Expected" }
}
function Worktrees {
    $all=@(); $cur=$null
    foreach($obj in (Invoke-GitV2 $RepoRoot @('worktree','list','--porcelain')).Output) {
        $line=[string]$obj
        if($line.StartsWith('worktree ')) { if($null-ne $cur){$all += [pscustomobject]$cur}; $cur=@{Path=$line.Substring(9);Branch='';Head=''} }
        elseif($null-ne $cur -and $line.StartsWith('HEAD ')) {$cur.Head=$line.Substring(5)}
        elseif($null-ne $cur -and $line.StartsWith('branch refs/heads/')) {$cur.Branch=$line.Substring(18)}
    }
    if($null-ne $cur){$all += [pscustomobject]$cur}; @($all)
}
function Assert-Worktree([object]$Ledger,[switch]$AllowMissing) {
    $want=[IO.Path]::GetFullPath([string]$Ledger.worktreePath)
    $record=Worktrees | Where-Object {[IO.Path]::GetFullPath($_.Path) -eq $want} | Select-Object -First 1
    if($null-eq $record) { if($AllowMissing){return $null}; Fail 'WT_WORKTREE_MISSING' "worktree 未注册：$want" }
    if($record.Branch -ne $Ledger.branch){Fail 'WT_WORKTREE_BRANCH_MISMATCH' "实际分支 $($record.Branch)，期望 $($Ledger.branch)"}
    $record
}
function Pattern-Regex([string]$Pattern) {
    $raw=$Pattern.Replace('\','/')
    if([string]::IsNullOrWhiteSpace($raw) -or [IO.Path]::IsPathRooted($Pattern) -or (($raw -split '/') -contains '..')){Fail 'WT_SCOPE_PATTERN_INVALID' "非法模式：$Pattern"}
    $n=$raw.TrimStart('./')
    if([string]::IsNullOrWhiteSpace($n)){Fail 'WT_SCOPE_PATTERN_INVALID' "非法模式：$Pattern"}
    $b=New-Object Text.StringBuilder
    for($i=0;$i-lt$n.Length;$i++){
        $c=$n[$i]
        if($c-eq'*'){if($i+1-lt$n.Length -and $n[$i+1]-eq'*'){[void]$b.Append('.*');$i++}else{[void]$b.Append('[^/]*')}}
        elseif($c-eq'?'){[void]$b.Append('[^/]')}else{[void]$b.Append([Regex]::Escape([string]$c))}
    }
    '^'+$b.ToString()+'$'
}
function Matches([string]$Path,[object[]]$Patterns) { foreach($p in @($Patterns)){if($Path -cmatch (Pattern-Regex ([string]$p))){return $true}}; $false }
function Changed([string]$At) {
    $set=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach($a in @(@('diff','--name-only','HEAD'),@('diff','--cached','--name-only','HEAD'),@('ls-files','--others','--exclude-standard'))){
        foreach($o in (Invoke-GitV2 $At $a).Output){$p=([string]$o).Replace('\','/');if($p){[void]$set.Add($p)}}
    }; @($set | Sort-Object)
}
function Assert-NoReparseAncestors([string]$FullPath,[string]$RootPath) {
    $root=[IO.Path]::GetFullPath($RootPath).TrimEnd('\','/')
    $current=[IO.Path]::GetFullPath($FullPath)
    while($current.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){
        if(Test-Path -LiteralPath $current){$item=Get-Item -LiteralPath $current -Force;if($item.Attributes-band[IO.FileAttributes]::ReparsePoint){Fail 'WT_SCOPE_SYMLINK' "拒绝重解析点及其祖先：$current"}}
        if($current-eq$root){break};$parent=[IO.Path]::GetDirectoryName($current);if([string]::IsNullOrWhiteSpace($parent)-or$parent-eq$current){break};$current=$parent
    }
}
function Assert-Scope([object]$Ledger) {
    $inc=@($Ledger.include);$exc=@($Ledger.exclude)
    if($inc.Count-eq0){Fail 'WT_SCOPE_INCLUDE_REQUIRED' 'scope.include 不能为空'}
    $bad=@();$root=[IO.Path]::GetFullPath([string]$Ledger.worktreePath).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    foreach($p in (Changed $Ledger.worktreePath)){
        if([IO.Path]::IsPathRooted($p) -or (($p-split'/')-contains'..')){Fail 'WT_SCOPE_ESCAPE' "非法路径：$p"}
        $full=[IO.Path]::GetFullPath((Join-Path $Ledger.worktreePath $p))
        if(-not $full.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){Fail 'WT_SCOPE_ESCAPE' "路径逃逸：$p"}
        Assert-NoReparseAncestors $full $Ledger.worktreePath
        if(-not(Matches $p $inc) -or (Matches $p $exc)){$bad += $p}
    }
    if($bad.Count-gt0){Fail 'WT_SCOPE_VIOLATION' "越界路径：$($bad -join ', ')"}
}

switch($Action){
 'prepare' {
    Assert-Code;Assert-BranchName $TargetBranch;if(@($Include).Count-eq0){Fail 'WT_SCOPE_INCLUDE_REQUIRED' 'prepare 必须提供 Include'}
    foreach($pattern in @($Include)+@($Exclude)){[void](Pattern-Regex ([string]$pattern))}
    Assert-Target $TargetBranch;Assert-Clean $RepoRoot 'WT_MAIN_DIRTY'
    $p=Worktree-Path;$b=Branch-Name;$lp=Ledger-Path
    if(Test-Path -LiteralPath $lp){
        $l=Read-Ledger
        if($l.targetBranch-ne$TargetBranch -or [IO.Path]::GetFullPath([string]$l.worktreePath)-ne$p -or $l.branch-ne$b -or (@($l.include)-join"`n")-cne(@($Include)-join"`n") -or (@($l.exclude)-join"`n")-cne(@($Exclude)-join"`n")){Fail 'WT_PREPARE_MISMATCH' '已有事务参数不一致'}
        $r=Assert-Worktree $l -AllowMissing
        if($l.cleaned -and $null-eq$r){Write-Output "ALREADY_CLEANED taskCode=$TaskCode";break}
        if($null-eq$r){Fail 'WT_ORPHAN_LEDGER' '台账存在但 worktree 缺失'}
        $expected=if($l.commitSha){$l.commitSha}else{$l.baseSha};if($r.Head-ne$expected){Fail 'WT_HEAD_DRIFT' "HEAD=$($r.Head)，台账=$expected"}
        Write-Output "ALREADY_PREPARED taskCode=$TaskCode baseSha=$($l.baseSha) worktreePath=$p";break
    }
    if(Test-Path -LiteralPath $p){Fail 'WT_ORPHAN_WORKTREE' "路径已存在：$p"}
    if((Invoke-GitV2 $RepoRoot @('show-ref','--verify','--quiet',"refs/heads/$b") -AllowFailure).Code-eq0){Fail 'WT_ORPHAN_BRANCH' "分支已存在：$b"}
    $base=Head $RepoRoot;New-Item -ItemType Directory -Path $WorktreeRoot -Force|Out-Null
    Invoke-GitV2 $RepoRoot @('worktree','add','-b',$b,$p,$base)|Out-Null
    $l=[pscustomobject]@{schemaVersion=2;taskCode=$TaskCode;baseSha=$base;commitSha=$null;targetBranch=$TargetBranch;worktreePath=$p;branch=$b;include=@($Include);exclude=@($Exclude);merged=$false;mergedSha=$null;verified=$false;cleaned=$false;createdAt=(Get-Date).ToUniversalTime().ToString('o');updatedAt=$null}
    Write-Ledger $l;Write-Output "PREPARED taskCode=$TaskCode baseSha=$base targetBranch=$TargetBranch worktreePath=$p"
 }
 'commit' {
    $l=Read-Ledger;if($l.cleaned){Fail 'WT_ALREADY_CLEANED' '事务已清理'};$r=Assert-Worktree $l
    if($l.commitSha){if($r.Head-ne$l.commitSha){Fail 'WT_HEAD_DRIFT' 'worktree HEAD 已漂移'};Assert-Clean $l.worktreePath 'WT_WORKTREE_DIRTY_AFTER_COMMIT';Write-Output "ALREADY_COMMITTED taskCode=$TaskCode commitSha=$($l.commitSha)";break}
    if($r.Head-ne$l.baseSha){Fail 'WT_HEAD_DRIFT' 'worktree HEAD 不等于 baseSha'}
    if([string]::IsNullOrWhiteSpace($Message)){Fail 'WT_MESSAGE_REQUIRED' 'commit 必须提供 Message'}
    if(@(Changed $l.worktreePath).Count-eq0){Fail 'WT_NO_CHANGES' '没有改动'}
    # 在 git add 前强制验证 include/exclude，防止越界内容进入索引。
    Assert-Scope $l;Invoke-GitV2 $l.worktreePath @('add','-A')|Out-Null;Invoke-GitV2 $l.worktreePath @('commit','-m',$Message)|Out-Null
    $l.commitSha=Head $l.worktreePath;Write-Ledger $l;Write-Output "COMMITTED taskCode=$TaskCode commitSha=$($l.commitSha)"
 }
 'merge' {
    $l=Read-Ledger;if(-not$l.commitSha){Fail 'WT_COMMIT_REQUIRED' '缺少 commitSha'};$r=Assert-Worktree $l
    if($r.Head-ne$l.commitSha){Fail 'WT_HEAD_DRIFT' 'worktree HEAD 不等于 commitSha'};Assert-Target $l.targetBranch
    if($l.merged){if((Invoke-GitV2 $RepoRoot @('merge-base','--is-ancestor',$l.commitSha,'HEAD') -AllowFailure).Code-ne0){Fail 'WT_MERGE_LOST' '目标分支不包含 commitSha'};Write-Output "ALREADY_MERGED taskCode=$TaskCode commitSha=$($l.commitSha) mergedSha=$($l.mergedSha)";break}
    Assert-Clean $RepoRoot 'WT_MAIN_DIRTY';$h=Head $RepoRoot;if($h-ne$l.baseSha){Fail 'WT_MAIN_HEAD_DRIFT' "HEAD=$h，baseSha=$($l.baseSha)"}
    $m=if($Message){$Message}else{"merge: $TaskCode"};$res=Invoke-GitV2 $RepoRoot @('merge','--no-ff',$l.commitSha,'-m',$m) -AllowFailure
    if($res.Code-ne0){Fail 'WT_MERGE_FAILED' "commitSha 已保存，可恢复主树后重试：$($res.Output -join '; ')"}
    $l.merged=$true;$l.mergedSha=Head $RepoRoot;Write-Ledger $l;Write-Output "MERGED taskCode=$TaskCode commitSha=$($l.commitSha) mergedSha=$($l.mergedSha)"
 }
 'verify' {
    $l=Read-Ledger;if(-not$l.merged){Fail 'WT_MERGE_REQUIRED' '尚未 merge'};Assert-Target $l.targetBranch;Assert-Clean $RepoRoot 'WT_MAIN_DIRTY'
    if((Invoke-GitV2 $RepoRoot @('merge-base','--is-ancestor',$l.commitSha,'HEAD') -AllowFailure).Code-ne0){Fail 'WT_VERIFY_COMMIT_MISSING' '目标分支不包含 commitSha'}
    $r=Assert-Worktree $l;if($r.Head-ne$l.commitSha){Fail 'WT_HEAD_DRIFT' 'worktree HEAD 已漂移'};Assert-Clean $l.worktreePath 'WT_WORKTREE_DIRTY_AFTER_COMMIT'
    $l.verified=$true;Write-Ledger $l;Write-Output "VERIFIED taskCode=$TaskCode baseSha=$($l.baseSha) commitSha=$($l.commitSha) targetBranch=$($l.targetBranch) worktreePath=$($l.worktreePath)"
 }
 'cleanup' {
    $l=Read-Ledger;if($l.cleaned){Write-Output "ALREADY_CLEANED taskCode=$TaskCode";break};if(-not$l.verified){Fail 'WT_VERIFY_REQUIRED' 'verify 未通过'}
    Assert-Target $l.targetBranch;Assert-Clean $RepoRoot 'WT_MAIN_DIRTY';if((Invoke-GitV2 $RepoRoot @('merge-base','--is-ancestor',$l.commitSha,'HEAD') -AllowFailure).Code-ne0){Fail 'WT_CLEANUP_UNMERGED' '目标分支不包含 commitSha'}
    $r=Assert-Worktree $l -AllowMissing;if($null-ne$r){Assert-Clean $l.worktreePath 'WT_WORKTREE_DIRTY_AFTER_COMMIT';Invoke-GitV2 $RepoRoot @('worktree','remove',$l.worktreePath)|Out-Null}elseif(Test-Path -LiteralPath $l.worktreePath){Fail 'WT_ORPHAN_PATH' '路径存在但未注册'}
    if((Invoke-GitV2 $RepoRoot @('show-ref','--verify','--quiet',"refs/heads/$($l.branch)") -AllowFailure).Code-eq0){Invoke-GitV2 $RepoRoot @('branch','-d',$l.branch)|Out-Null}
    $l.cleaned=$true;Write-Ledger $l;Write-Output "CLEANED taskCode=$TaskCode"
 }
 'reconcile' {
    $issues=@();$ledgers=@();if(Test-Path -LiteralPath $LedgerDir){foreach($f in Get-ChildItem -LiteralPath $LedgerDir -Filter '*.json' -File){try{$ledgers+=Get-Content -LiteralPath $f.FullName -Raw|ConvertFrom-Json}catch{$issues+="INVALID_LEDGER path=$($f.FullName)"}}}
    $registered=Worktrees
    foreach($w in $registered|Where-Object{[IO.Path]::GetFullPath($_.Path)-ne$RepoRoot}){if(@($ledgers|Where-Object{-not$_.cleaned -and [IO.Path]::GetFullPath([string]$_.worktreePath)-eq[IO.Path]::GetFullPath($w.Path)}).Count-eq0){$issues+="ORPHAN_WORKTREE path=$($w.Path) branch=$($w.Branch)"}}
    foreach($bo in (Invoke-GitV2 $RepoRoot @('for-each-ref','--format=%(refname:short)','refs/heads/codex/')).Output){$b=[string]$bo;if(@($ledgers|Where-Object{-not$_.cleaned -and $_.branch-eq$b}).Count-eq0){$issues+="ORPHAN_BRANCH branch=$b"}}
    foreach($l in $ledgers|Where-Object{-not$_.cleaned}){if(@($registered|Where-Object{[IO.Path]::GetFullPath($_.Path)-eq[IO.Path]::GetFullPath([string]$l.worktreePath)}).Count-eq0){$issues+="MISSING_WORKTREE taskCode=$($l.taskCode) path=$($l.worktreePath)"}}
    $issues|ForEach-Object{Write-Output $_};if($Strict -and $issues.Count-gt0){Fail 'WT_RECONCILE_FAILED' "$($issues.Count) 个资源差异"};Write-Output "RECONCILED issues=$($issues.Count) strict=$([bool]$Strict)"
 }
 'list' { Worktrees|Format-Table Path,Branch,Head -AutoSize;if(Test-Path -LiteralPath $LedgerDir){Get-ChildItem -LiteralPath $LedgerDir -Filter '*.json' -File|Select-Object -ExpandProperty FullName} }
}
