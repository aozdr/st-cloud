# ============================================================
# 星云盘 PC 客户端一键打包脚本
#
# 用法（在 st-desktop 目录）：
#   ./package.ps1              打包绿色版 + 安装包
#   ./package.ps1 -Clean       先清空 release 输出再打包
#   ./package.ps1 -ForceClose  检测到星云盘正在运行时自动结束进程再打包
#   或使用 npm 快捷命令：
#   npm run package:win
#   npm run package:win:clean
#   npm run package:win:force
#
# 产物：
#   release\win-unpacked\星云盘.exe   绿色版（整个文件夹一起分发）
#   release\星云盘 Setup 1.0.0.exe    安装包（NSIS）
#
# 注意：
#   - 首次打包需要联网下载 Electron 运行时（约 111MB），之后走本地缓存；
#   - 前端默认连 http://127.0.0.1:8080 后端，运行 exe 前请先启动后端；
#   - 本脚本只应在 Windows 上运行（产物目标为 win32-x64）。
# ============================================================

param(
  [switch]$Clean,
  [switch]$ForceClose
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# 星云盘正在运行时会锁住 release 文件，导致打包失败，先处理
$running = Get-Process -Name '星云盘' -ErrorAction SilentlyContinue
if ($running) {
  if ($ForceClose) {
    Write-Host '==> 检测到星云盘正在运行，自动结束进程...' -ForegroundColor Yellow
    $running | Stop-Process -Force
    Start-Sleep -Milliseconds 800
  } else {
    throw '检测到星云盘正在运行，release 文件被占用。请先退出星云盘，或加 -ForceClose 参数自动结束进程后重试'
  }
}

function Assert-Ok([string]$step) {
  if ($LASTEXITCODE -ne 0) {
    throw "[打包失败] $step"
  }
}

# 可选：清空旧的打包输出，避免残留文件干扰
if ($Clean -and (Test-Path (Join-Path $PSScriptRoot 'release'))) {
  Write-Host '==> 清理旧产物 release\ ...' -ForegroundColor Yellow
  Remove-Item -LiteralPath (Join-Path $PSScriptRoot 'release') -Recurse -Force
}

# 前置检查
if (-not (Test-Path (Join-Path $PSScriptRoot 'build\icon.png'))) {
  throw '缺少应用图标 build\icon.png，请先准备图标'
}

Write-Host '==> [1/4] 编译主进程 (tsup)' -ForegroundColor Cyan
npm run build:main
Assert-Ok '主进程编译失败'

Write-Host '==> [2/4] 编译前端 (vite build)' -ForegroundColor Cyan
npm run build:web
Assert-Ok '前端编译失败'

# 前端资源必须为相对路径（base: "./"），否则打包后 file:// 下资源 404
$indexHtml = Join-Path $PSScriptRoot '..\st-web\dist\index.html'
if (-not (Select-String -LiteralPath $indexHtml -Pattern 'src="\./assets' -Quiet)) {
  throw '前端资源路径异常：请确认 st-web/vite.config.ts 中设置了 base: ''./'''
}

Write-Host '==> [3/4] 打包绿色版 (win-unpacked)' -ForegroundColor Cyan
npx electron-builder --win dir
Assert-Ok '绿色版打包失败'

Write-Host '==> [4/4] 生成安装包 (NSIS)' -ForegroundColor Cyan
npx electron-builder --win nsis
Assert-Ok '安装包生成失败'

$exe = Join-Path $PSScriptRoot 'release\win-unpacked\星云盘.exe'
$setup = Get-ChildItem (Join-Path $PSScriptRoot 'release') -Filter '*.exe' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -like '星云盘 Setup*' } | Select-Object -First 1

Write-Host ''
Write-Host '打包完成！' -ForegroundColor Green
Write-Host "  绿色版 : $exe"
if ($setup) { Write-Host "  安装包 : $($setup.FullName)" }
