param([Parameter(Mandatory)][string]$Plan,[Parameter(Mandatory)][int]$N,[Parameter(Mandatory)][string]$OutDir)
$lines = Get-Content -LiteralPath $Plan
$start = ($lines | Select-String -Pattern "^### Task $N`:" | Select-Object -First 1).LineNumber
if (-not $start) { throw "Task $N not found in $Plan" }
$next = ($lines | Select-String -Pattern "^### Task \d+:" | Where-Object { $_.LineNumber -gt $start } | Select-Object -First 1).LineNumber
if (-not $next) { $next = $lines.Count + 1 }
$body = $lines[($start-1)..($next-2)]
$out = Join-Path $OutDir "task-$N-brief.md"
Set-Content -LiteralPath $out -Value $body -Encoding UTF8
Write-Output $out
