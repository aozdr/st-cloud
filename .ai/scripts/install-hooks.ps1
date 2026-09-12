#requires -Version 5.1
<#
.SYNOPSIS
  安装 Agent Loop 预提交门禁（把仓库级 core.hooksPath 指向入库的 .ai/githooks）。
.DESCRIPTION
  - 幂等：重复执行覆盖同值，不产生副作用
  - 安装后，提交涉及 .ai/** 或 AGENTS.md 时自动运行 .ai/scripts/run-loop-gate.ps1，FAIL 阻止提交
  - 卸载：git config --unset core.hooksPath
  - 绕过（不推荐）：git commit --no-verify
.NOTES
  入库目录方案使新克隆/新环境同样生效；.git/hooks 不入库，故不采用。
#>
[CmdletBinding()]
param(
  [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $RepoRoot) {
  $RepoRoot = (git rev-parse --show-toplevel)
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($RepoRoot)) {
    throw "无法解析仓库根目录，请确认在 git 仓库内运行"
  }
}

$hookPath = Join-Path $RepoRoot '.ai\githooks\pre-commit'
if (-not (Test-Path $hookPath)) {
  throw "缺少钩子文件：$hookPath"
}

# 静默执行 git 子命令：避免 stderr 在 $ErrorActionPreference='Stop' 下被当作终止错误
function Invoke-GitQuiet {
  param([string[]]$GitArgs)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & git @GitArgs 2>&1
    return @{ Output = $out; Code = $LASTEXITCODE }
  }
  finally { $ErrorActionPreference = $prev }
}

Push-Location $RepoRoot
try {
  git config core.hooksPath '.ai/githooks'
  if ($LASTEXITCODE -ne 0) { throw "git config core.hooksPath 失败" }
  $current = (git config --get core.hooksPath)
  if ($current -ne '.ai/githooks') { throw "写入后校验失败，当前 core.hooksPath=$current" }
  Write-Host "已安装：core.hooksPath = $current" -ForegroundColor Green

  # 该文件需在索引中带可执行位（Git for Windows 下部分环境会跳过无执行位的钩子）
  $tracked = Invoke-GitQuiet @('ls-files', '--error-unmatch', '.ai/githooks/pre-commit')
  if ($tracked.Code -eq 0) {
    Invoke-GitQuiet @('update-index', '--chmod=+x', '.ai/githooks/pre-commit') | Out-Null
    Write-Host "已设置可执行位：.ai/githooks/pre-commit" -ForegroundColor Green
  } else {
    Write-Host "提示：.ai/githooks/pre-commit 尚未纳入索引；首次 git add 后本脚本会自动补可执行位。" -ForegroundColor Yellow
  }

  Write-Host "验证：运行一次门禁（当前状态）" -ForegroundColor Cyan
  $pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
  if (-not $pwsh) { throw '严格门禁需要 PowerShell 7（pwsh），当前环境未找到，安装失败' }
  & $pwsh.Source -NoProfile -File '.ai\scripts\run-loop-gate.ps1' -SkipIntegrationTests
  if ($LASTEXITCODE -ne 0) { throw "安装后门禁验证失败（exit=$LASTEXITCODE）" }
  Write-Host "卸载：git config --unset core.hooksPath" -ForegroundColor Cyan
}
finally {
  Pop-Location
}
