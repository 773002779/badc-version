param([Parameter(Mandatory)][string]$Base,[Parameter(Mandatory)][string]$Head,[Parameter(Mandatory)][string]$OutDir,[Parameter(Mandatory)][int]$N)
$out = Join-Path $OutDir "task-$N-review.md"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# Review package — task $N")
[void]$sb.AppendLine("range: $Base..$Head")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("## Commits")
[void]$sb.AppendLine((git log --oneline "$Base..$Head" | Out-String))
[void]$sb.AppendLine("## Stat")
[void]$sb.AppendLine((git diff --stat "$Base..$Head" | Out-String))
[void]$sb.AppendLine("## Diff")
[void]$sb.AppendLine((git diff -U10 "$Base..$Head" | Out-String))
Set-Content -LiteralPath $out -Value $sb.ToString() -Encoding UTF8
Write-Output $out
