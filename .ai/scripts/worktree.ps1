# worktree.ps1 - V15 worktree 隔离生命周期工具（主线程专用）
# 操作：create / list / wait-claim / commit-merge / cleanup / verify
# 用法（Windows 执行策略限制，需 -ExecutionPolicy Bypass）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/worktree.ps1 -Action create -TaskCode be01
#   powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/worktree.ps1 -Action list
#   powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/worktree.ps1 -Action wait-claim -TaskCode be01 -TimeoutSeconds 180
#   powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/worktree.ps1 -Action commit-merge -TaskCode be01 -Message "TASK-BE-01: 新增 FileNameSanitizer"
#   powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/worktree.ps1 -Action cleanup -TaskCode be01
#   powershell -NoProfile -ExecutionPolicy Bypass -File .ai/scripts/worktree.ps1 -Action verify
# 安全规则：
#   - cleanup 拒绝删除有未提交改动的 worktree（禁止 --force，保护现场）
#   - git 写操作仅主线程执行；子 Agent 禁调 git（forbidGitMvn）
# 顺序准入（V8.5/V15）：spawn 后必须用 wait-claim 轮询 archived 认领文件作为动态 ACK 判据，
#   认领即 ACK，立即派发下一个 child；禁止等待子线程业务完成。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('create', 'list', 'wait-claim', 'commit-merge', 'cleanup', 'verify')]
    [string]$Action,

    [string]$TaskCode,

    [string]$Message,

    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (git rev-parse --show-toplevel)
if ($LASTEXITCODE -ne 0) { throw "无法解析仓库根目录，请确认在 git 仓库内运行" }
$WorktreesDir = Join-Path $RepoRoot '.ai\worktrees'
$MainBranch = 'main'

function Get-WorktreePath {
    param([string]$Code)
    return Join-Path $WorktreesDir $Code
}

function Assert-TaskCode {
    if ([string]::IsNullOrWhiteSpace($TaskCode)) {
        throw "参数 TaskCode 不能为空"
    }
    # 仅允许安全字符，防止路径/分支名注入
    if ($TaskCode -notmatch '^[A-Za-z0-9_-]+$') {
        throw "TaskCode 含非法字符（仅允许 A-Za-z0-9_-）：$TaskCode"
    }
}

switch ($Action) {
    'create' {
        Assert-TaskCode
        $branch = "codex/$TaskCode"
        $path = Get-WorktreePath $TaskCode
        if (Test-Path $path) {
            throw "worktree 已存在：$path"
        }
        git worktree add -b $branch $path $MainBranch | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "git worktree add 失败" }
        Write-Output "WORKTREE_CREATED path=$path branch=$branch"
    }
    'list' {
        git worktree list
        Write-Output "----- 各 worktree 改动状态 -----"
        foreach ($line in (git worktree list --porcelain)) {
            if ($line -like 'worktree *') {
                $wt = ($line -replace '^worktree ', '').Trim()
                if ($wt -ne $RepoRoot) {
                    $st = git -C $wt status --porcelain
                    if ($st) {
                        Write-Output "[$wt] 有未提交改动："
                        $st
                    } else {
                        Write-Output "[$wt] 干净"
                    }
                }
            }
        }
    }
    'wait-claim' {
        Assert-TaskCode
        $claimFile = Join-Path (Join-Path $RepoRoot '.ai\dispatch\archived') "inbox-$TaskCode.md"
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            if (Test-Path $claimFile) {
                Write-Output "CLAIMED taskCode=$TaskCode claimFile=$claimFile"
                return
            }
            Start-Sleep -Seconds 3
        }
        Write-Output "CLAIM_TIMEOUT taskCode=$TaskCode waitedSeconds=$TimeoutSeconds"
    }
    'commit-merge' {
        Assert-TaskCode
        $branch = "codex/$TaskCode"
        $path = Get-WorktreePath $TaskCode
        if (-not (Test-Path $path)) { throw "worktree 不存在：$path" }
        $changes = git -C $path status --porcelain
        if ($LASTEXITCODE -ne 0) { throw "git status 失败" }
        if (-not $changes) {
            Write-Output "NO_CHANGES taskCode=$TaskCode"
            return
        }
        if ([string]::IsNullOrWhiteSpace($Message)) {
            throw "commit-merge 需要 -Message 参数"
        }
        # .ai/**、node_modules、target 已由 .gitignore 排除，不会进入提交
        git -C $path add -A
        if ($LASTEXITCODE -ne 0) { throw "git add 失败" }
        git -C $path commit -m $Message
        if ($LASTEXITCODE -ne 0) { throw "git commit 失败" }
        git -C $RepoRoot merge --no-ff $branch -m "merge: $Message"
        if ($LASTEXITCODE -ne 0) { throw "git merge 失败（需人工处理冲突，保留现场）" }
        Write-Output "MERGED taskCode=$TaskCode branch=$branch"
    }
    'cleanup' {
        Assert-TaskCode
        $branch = "codex/$TaskCode"
        $path = Get-WorktreePath $TaskCode
        if (-not (Test-Path $path)) { throw "worktree 不存在：$path" }
        # 安全检查：存在未提交改动时拒绝清理（保护现场，不使用 --force）
        $changes = git -C $path status --porcelain
        if ($LASTEXITCODE -ne 0) { throw "git status 失败" }
        if ($changes) {
            throw "worktree 有未提交改动，拒绝清理（保留现场）：$path"
        }
        git worktree remove $path
        if ($LASTEXITCODE -ne 0) { throw "git worktree remove 失败" }
        git branch -d $branch
        if ($LASTEXITCODE -ne 0) { throw "git branch -d 失败" }
        Write-Output "CLEANED taskCode=$TaskCode"
    }
    'verify' {
        git -C $RepoRoot status --short --branch
        git worktree list
        $remaining = (git worktree list --porcelain |
            Select-String '^worktree ' |
            Where-Object { $_ -notlike "*$RepoRoot*" } |
            Measure-Object).Count
        Write-Output "REMAINING_WORKTREES=$remaining"
        if ($remaining -gt 0) {
            Write-Output "WARN: 存在未清理 worktree，请复查隔离断言"
        } else {
            Write-Output "OK: 无残留 worktree"
        }
    }
}
