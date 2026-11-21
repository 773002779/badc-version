#requires -Version 5.1
<#
.SYNOPSIS
    停止由 scripts\start.ps1 启动的后端进程。
#>
param([int]$Port = 8080)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.run\app.pid'

$stopped = $false

if (Test-Path -LiteralPath $pidFile) {
    $id = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($id) {
        $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $proc.Id -Force
            Write-Host "已停止进程 PID $id" -ForegroundColor Green
            $stopped = $true
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

if (-not $stopped) {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        Stop-Process -Id $listener.OwningProcess -Force
        Write-Host "已停止占用端口 $Port 的进程 PID $($listener.OwningProcess)" -ForegroundColor Green
        $stopped = $true
    }
}

if (-not $stopped) { Write-Host "端口 $Port 上没有在运行的服务。" -ForegroundColor Yellow }