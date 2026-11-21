#requires -Version 5.1
<#
.SYNOPSIS
    一条命令起本地联调环境：后端 + 前端开发服务器。

.DESCRIPTION
    顺序：停止旧进程（释放两个端口）→ 启动后端并等就绪 → 在新窗口启动 Vite → 打印地址。
    后端未就绪时不会继续，避免前端打开后一直提示连不上。

.PARAMETER Port
    后端端口，默认 8080。

.PARAMETER WebPort
    前端端口，默认 5173。

.PARAMETER Rebuild
    强制重新打包后端（默认复用已有 jar）。

.PARAMETER Open
    就绪后用默认浏览器打开前端页面。

.PARAMETER NoWeb
    只起后端，不启动前端。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 -Rebuild -Open
#>
param(
    [int]$Port = 8080,
    [int]$WebPort = 5173,
    [switch]$Rebuild,
    [switch]$Open,
    [switch]$NoWeb
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$web = Join-Path $root 'web'
$apiBase = "http://127.0.0.1:$Port"

function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }

function Test-Port([string]$HostName, [int]$P, [int]$TimeoutMs = 800) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $P)
        if (-not $task.Wait($TimeoutMs)) { return $false }
        return $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Wait-Port([string]$HostName, [int]$P, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $HostName $P 400) { return $true }
        Start-Sleep -Milliseconds 600
    }
    return $false
}

# ------------------------------------------------------------- 1. 清理旧进程
Say '== 1/3 清理旧进程 ==' 'Cyan'
& (Join-Path $PSScriptRoot 'stop.ps1') *> $null
# 不依赖 pid 文件：直接把两个端口上的监听进程清掉，端口要么空出来要么明确失败。
foreach ($p in @($Port, $WebPort)) {
    Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline -and (Test-Port '127.0.0.1' $Port 300)) {
    Start-Sleep -Milliseconds 400
}
if (Test-Port '127.0.0.1' $Port 300) {
    Say "端口 $Port 无法释放，请手动结束占用进程后重试。" 'Red'
    exit 1
}
Say '  端口已释放'

# -------------------------------------------------------------- 2. 启动后端
Say '== 2/3 启动后端 ==' 'Cyan'
$startArgs = @{ Port = $Port }
if ($Rebuild) { $startArgs['Rebuild'] = $true }
& (Join-Path $PSScriptRoot 'start.ps1') @startArgs
if ($LASTEXITCODE -ne 0 -or -not (Test-Port '127.0.0.1' $Port 400)) {
    Say '后端启动失败，排错请先看 logs\app.log。' 'Red'
    exit 1
}

# -------------------------------------------------------------- 3. 启动前端
if ($NoWeb) {
    Say '== 3/3 跳过前端（-NoWeb）==' 'Cyan'
    Say ''
    Say "后端 : $apiBase    (X-User-Id: dev)" 'Green'
    exit 0
}

Say '== 3/3 启动前端 ==' 'Cyan'
if (-not (Test-Path (Join-Path $web 'node_modules'))) {
    Say '  首次运行：在 web\ 下安装依赖（可能需要几分钟）'
}
Start-Process powershell -ArgumentList @(
    '-ExecutionPolicy', 'Bypass', '-NoExit',
    '-File', (Join-Path $PSScriptRoot 'web-dev.ps1'),
    '-ApiBase', $apiBase,
    '-Port', $WebPort
) -WorkingDirectory $web

if (Wait-Port '127.0.0.1' $WebPort 180) {
    Say "  前端已就绪（端口 $WebPort）"
} else {
    Say '  前端还没起来，稍等一下或看新窗口的输出。' 'Yellow'
}

Say ''
Say '------------------------------------------------------------'
Say "前端 : http://127.0.0.1:$WebPort" 'Green'
Say "后端 : $apiBase    (X-User-Id: dev)" 'Green'
Say '停止 : powershell -ExecutionPolicy Bypass -File scripts\stop.ps1 （前端窗口 Ctrl+C）'
Say '说明 : 未配置 CODING 连接时发布会以 ENGINE_UNAVAILABLE 失败，属预期结果。'
Say '------------------------------------------------------------'

if ($Open) { Start-Process "http://127.0.0.1:$WebPort" }