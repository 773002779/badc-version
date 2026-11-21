#requires -Version 5.1
<#
.SYNOPSIS
    启动发版流水线后端（本地验证用）。

.DESCRIPTION
    做四件事：检查依赖 → 生成/加载本地密钥 → 构建并启动 jar → 播种一个 dev 管理员账号。
    启动后可用 X-User-Id: dev 调用接口；scripts\demo.ps1 会跑一遍完整链路。

.PARAMETER Port
    HTTP 端口，默认 8080。

.PARAMETER DbPassword
    MySQL 密码，默认取环境变量 DB_PASSWORD，再退回 root。

.PARAMETER Rebuild
    强制重新打包（默认只在 jar 不存在时打包）。

.PARAMETER NoSeed
    跳过播种 dev 账号。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start.ps1
#>
param(
    [int]$Port = 8080,
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'root' }),
    [switch]$Rebuild,
    [switch]$NoSeed
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root 'server'
$runDir = Join-Path $root '.run'
$logDir = Join-Path $root 'logs'
$envFile = Join-Path $root '.env.local'
$pidFile = Join-Path $runDir 'app.pid'
$dbName = 'release_pipeline'

New-Item -ItemType Directory -Force -Path $runDir, $logDir | Out-Null

function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }
function Fail($msg) { Write-Host "✗ $msg" -ForegroundColor Red; exit 1 }

function Test-Port([string]$HostName, [int]$P, [int]$TimeoutMs = 1500) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $P)
        if (-not $task.Wait($TimeoutMs)) { return $false }
        return $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

# ---------------------------------------------------------------- 1. 依赖检查
Say "== 1/5 检查依赖 ==" 'Cyan'

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { Fail '找不到 java，请安装 JDK 17+ 并加入 PATH。' }
$javaVersion = (& java -version 2>&1) -join ' '
if ($javaVersion -notmatch 'version "(1[7-9]|2[0-9])') {
    Fail "需要 JDK 17+，当前：$javaVersion"
}
Say ("  java : " + ($javaVersion -replace '\s+', ' ').Trim())

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) { Fail '找不到 mvn，请安装 Maven 3.6+ 并加入 PATH。' }
Say ("  mvn  : " + (((& mvn -v 2>&1) | Select-Object -First 1) -replace '\s+', ' ').Trim())

if (-not (Test-Port '127.0.0.1' 3306)) {
    Fail "连不上 MySQL 127.0.0.1:3306。请先启动 MySQL，或设置 DB_URL 指向其他实例。"
}
Say '  mysql: 127.0.0.1:3306 可达'

if (Test-Port '127.0.0.1' $Port 300) {
    Fail "端口 $Port 已被占用。先运行 scripts\stop.ps1，或用 -Port 指定其他端口。"
}

# ---------------------------------------------------------------- 2. 本地密钥
Say "== 2/5 准备本地密钥 ==" 'Cyan'

function New-Secret([int]$Bytes) {
    $buf = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
    return [Convert]::ToBase64String($buf)
}

if (Test-Path -LiteralPath $envFile) {
    $saved = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8)) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(.+)$') { $saved[$Matches[1]] = $Matches[2].Trim() }
    }
    if (-not $env:CODING_CRED_KEY) { $env:CODING_CRED_KEY = $saved['CODING_CRED_KEY'] }
    if (-not $env:CODING_WEBHOOK_SECRET) { $env:CODING_WEBHOOK_SECRET = $saved['CODING_WEBHOOK_SECRET'] }
    Say "  复用 .env.local"
}
if (-not $env:CODING_CRED_KEY) {
    $env:CODING_CRED_KEY = New-Secret 32
    Say '  已生成 CODING_CRED_KEY（32 字节，AES-GCM 凭证密钥）'
}
if (-not $env:CODING_WEBHOOK_SECRET) {
    $env:CODING_WEBHOOK_SECRET = New-Secret 32
    Say '  已生成 CODING_WEBHOOK_SECRET（webhook HMAC 密钥）'
}
$lines = @(
    '# 本文件由 scripts/start.ps1 生成，已加入 .gitignore，勿提交。',
    "CODING_CRED_KEY=$($env:CODING_CRED_KEY)",
    "CODING_WEBHOOK_SECRET=$($env:CODING_WEBHOOK_SECRET)"
) -join "`n"
[System.IO.File]::WriteAllText($envFile, $lines + "`n", (New-Object System.Text.UTF8Encoding($false)))

$env:DB_PASSWORD = $DbPassword
$env:DB_USERNAME = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' })

# ---------------------------------------------------------------- 3. 构建
Say "== 3/5 构建 ==" 'Cyan'

$jar = Get-ChildItem -Path (Join-Path $serverDir 'target') -Filter 'release-pipeline-server-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '\.original$' } | Select-Object -First 1
if ($Rebuild -or -not $jar) {
    Push-Location $serverDir
    try {
        Say '  mvn -q -DskipTests package ...'
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { Fail '打包失败，请查看上面的 Maven 输出。' }
    } finally { Pop-Location }
    $jar = Get-ChildItem -Path (Join-Path $serverDir 'target') -Filter 'release-pipeline-server-*.jar' |
        Where-Object { $_.Name -notmatch '\.original$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if (-not $jar) { Fail '未找到可运行的 jar。' }
Say ("  " + $jar.Name)

# ---------------------------------------------------------------- 4. 启动
Say "== 4/5 启动（端口 $Port）==" 'Cyan'

$stdout = Join-Path $logDir 'app.log'
$stderr = Join-Path $logDir 'app.err.log'
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @("-jar", $jar.FullName, "--server.port=$Port") `
    -WorkingDirectory $serverDir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$proc.Id | Set-Content -LiteralPath $pidFile -Encoding ascii

$deadline = (Get-Date).AddSeconds(120)
$up = $false
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited) { break }
    if (Test-Port '127.0.0.1' $Port 400) { $up = $true; break }
    Start-Sleep -Milliseconds 800
}

if (-not $up) {
    Say '✗ 启动失败，最后 25 行日志：' 'Red'
    if (Test-Path $stdout) { Get-Content -LiteralPath $stdout -Tail 25 | ForEach-Object { Say "  $_" } }
    if (Test-Path $stderr) { Get-Content -LiteralPath $stderr -Tail 10 | ForEach-Object { Say "  $_" 'Red' } }
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
    exit 1
}
Say "  ✓ 已启动，PID $($proc.Id)，日志 logs\app.log"

# ---------------------------------------------------------------- 5. 播种 admin
Say "== 5/5 播种本地管理员 ==" 'Cyan'

if ($NoSeed) {
    Say '  跳过（-NoSeed）'
} else {
    $mysql = Get-Command mysql -ErrorAction SilentlyContinue
    if (-not $mysql) {
        Say '  ! 找不到 mysql 客户端，跳过播种。手动执行下面 SQL 即可：' 'Yellow'
        Say "    INSERT IGNORE INTO app_user (id,name,status,created_at) VALUES ('dev','Dev User','ACTIVE',NOW(3));" 'DarkGray'
        Say "    INSERT IGNORE INTO user_role (user_id,role) VALUES ('dev','ADMIN');" 'DarkGray'
        Say "    INSERT IGNORE INTO user_scope (user_id,scope_type,scope_id) VALUES ('dev','ALL',0);" 'DarkGray'
    } else {
        $sql = @(
            "INSERT IGNORE INTO app_user (id, name, status, created_at) VALUES ('dev','Dev User','ACTIVE',NOW(3));",
            "INSERT IGNORE INTO user_role (user_id, role) VALUES ('dev','ADMIN');",
            "INSERT IGNORE INTO user_scope (user_id, scope_type, scope_id) VALUES ('dev','ALL',0);"
        ) -join ' '
        $env:MYSQL_PWD = $DbPassword
        try {
            $mysqlOut = & mysql "--host=127.0.0.1" "--user=$($env:DB_USERNAME)" "--database=$dbName" "--default-character-set=utf8mb4" "--execute=$sql" 2>&1
            if ($LASTEXITCODE -ne 0) {
                Say '  ! 播种失败，可稍后重试或手动执行。输出：' 'Yellow'
                $mysqlOut | Select-Object -First 5 | ForEach-Object { Say "    $_" 'DarkYellow' }
            } else { Say '  ✓ 本地管理员 dev（ADMIN + 全量单元范围）' }
        } finally { $env:MYSQL_PWD = $null }
    }
}

# ---------------------------------------------------------------- 完成
Say ''
Say "服务地址 : http://127.0.0.1:$Port" 'Green'
Say "认证方式 : 请求头 X-User-Id: dev" 'Green'
Say "日志     : logs\app.log" 'Green'
Say "停止     : powershell -File scripts\stop.ps1" 'Green'
Say ''
Say '下一步：' 'Cyan'
Say '  1) 跑一遍完整链路：powershell -ExecutionPolicy Bypass -File scripts\demo.ps1'
Say "  2) 自己调接口       : curl -H `"X-User-Id: dev`" http://127.0.0.1:$Port/subsystems"
Say ''
Say '说明：一期没有配置 CODING 连接（coding_conn）时，发布会在构建阶段以 ENGINE_UNAVAILABLE 失败；' 'DarkGray'
Say '      这是预期结果。流水线停在 VERIFY（status=PAUSED）也属一期设计边界，门禁在 P2 交付。' 'DarkGray'