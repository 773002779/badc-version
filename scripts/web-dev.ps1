#requires -Version 5.1
<#
.SYNOPSIS
    启动前端开发服务器（Vite）。

.DESCRIPTION
    默认代理到 http://127.0.0.1:8080（scripts\start.ps1 启动的后端）。
    后端未启动也能打开页面，但会看到“无法连接后端服务”的提示。

.PARAMETER ApiBase
    后端地址，默认 127.0.0.1:8080。

.PARAMETER Port
    前端端口，默认 5173。
#>
param(
    [string]$ApiBase = 'http://127.0.0.1:8080',
    [int]$Port = 5173
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$web = Join-Path $root 'web'

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host '找不到 node，请安装 Node.js 20+ 并加入 PATH。' -ForegroundColor Red
    exit 1
}
Write-Host ("node: " + (node -v))

Push-Location $web
try {
    if (-not (Test-Path 'node_modules')) {
        Write-Host '首次运行，安装依赖…' -ForegroundColor Cyan
        npm install --no-audit --no-fund
    }
    $env:VITE_BACKEND = $ApiBase
    Write-Host ''
    Write-Host "前端地址 : http://127.0.0.1:$Port" -ForegroundColor Green
    Write-Host "后端代理 : $ApiBase" -ForegroundColor Green
    Write-Host '停止     : Ctrl+C' -ForegroundColor Green
    Write-Host ''
    npm run dev -- --port $Port
} finally {
    Pop-Location
}