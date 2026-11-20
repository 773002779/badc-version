param([Parameter(Mandatory)][string]$Base,[Parameter(Mandatory)][string]$Head,[Parameter(Mandatory)][string]$OutDir,[Parameter(Mandatory)][string]$N)
$out = Join-Path $OutDir "task-$N-review.md"
$parts = @(
  "# Review package - task $N",
  "range: $Base..$Head",
  "",
  "## Commits",
  (git log --oneline "$Base..$Head" | Out-String),
  "## Stat",
  (git diff --stat "$Base..$Head" | Out-String),
  "## Diff",
  (git diff -U10 "$Base..$Head" | Out-String)
)
[System.IO.File]::WriteAllText($out, ($parts -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Output $out