# Demo of the measurement results (4 policies x scenarios) for a presentation.
#   .\sim\demo.ps1                                    # analyse the committed results, print the verdicts
#   .\sim\demo.ps1 -Mode quick                        # live: run 4 policies x 10 seeds of one scenario, analyse
#   .\sim\demo.ps1 -Mode quick -Scenario storm -Seeds 10 -Jobs 4
#   .\sim\demo.ps1 -Mode web                          # open the browser demo (web/index.html)
# Requirements: JDK 21+ (see build.ps1) and Python 3 with analysis/requirements.txt installed.
param(
    [ValidateSet("results", "quick", "web")][string]$Mode = "results",
    [string]$Scenario = "base",
    [int]$Seeds = 10,
    [int]$Jobs = 4,
    [switch]$Open
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

if ($Mode -eq "web") {
    Start-Process (Join-Path $repo "web\index.html")
    return
}

$python = $null
foreach ($name in @("python", "py")) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { $python = $cmd.Source; break }
}
if (-not $python) { throw "Python 3 not found: install it and run 'pip install -r analysis/requirements.txt'" }

$out = Join-Path ([System.IO.Path]::GetTempPath()) "backfill_demo_$Mode"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }

if ($Mode -eq "quick") {
    Write-Host "[demo] $Scenario : 4 policies x $Seeds seeds, $Jobs runs in parallel (same seeds => same inputs for every policy)"
    & "$PSScriptRoot\run.ps1" grid -Scenario $Scenario -Seeds $Seeds -Jobs $Jobs -NoTicks -OutDir (Join-Path $out "runs")
    & $python (Join-Path $repo "analysis\stats.py") --runs (Join-Path $out "runs") --out $out
} else {
    Write-Host "[demo] statistical analysis of the committed results (results\runs + results\replications)"
    & $python (Join-Path $repo "analysis\stats.py") --out $out
}
if ($LASTEXITCODE -ne 0) { throw "analysis failed" }

Write-Host ""
Write-Host "[demo] tables : $out\analysis\STATS.md"
Write-Host "[demo] figures: $out\analysis\figures\stats_*.png"
Write-Host "[demo] report : $repo\docs\REPORT_VI.md (method: docs\STATISTICS.md)"
if ($Open) {
    Start-Process (Join-Path $out "analysis\figures\stats_forest_vs_fifo.png")
    Start-Process (Join-Path $out "analysis\figures\stats_ci_backDelayMean.png")
}
