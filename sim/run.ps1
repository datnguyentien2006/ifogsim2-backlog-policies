# Run driver.
#   .\run.ps1 run  -Policy fifo  -Scenario base -Seed 0          # one run -> results\runs\<scenario>\
#   .\run.ps1 run  -Policy rr    -Scenario storm                 # one run, default seed 0
#   .\run.ps1 grid                                -Seeds 3      # full batch -> results\runs\<scenario>\
#   .\run.ps1 grid -Policy newfirst -Scenario N10 -Seeds 2        # filtered batch
#   .\run.ps1 grid -FirstSeed 5 -Seeds 25 -Jobs 4 -NoTicks -OutDir results/replications
#                                                                # extra replications, 4 JVMs in parallel
#   .\run.ps1 test                                               # framework invariants (SelfTest)
# Compiles first if needed, then runs each experiment in its own JVM.
param(
    [Parameter(Mandatory = $true)][ValidateSet("run", "grid", "test")][string]$Mode,
    [string]$Policy,
    [string]$Scenario,
    [int]$Seed = 0,
    [int]$Seeds = 3,
    [int]$FirstSeed = 0,
    [int]$Jobs = 1,
    [switch]$NoTicks,
    [string]$OutDir = "results/runs"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

& "$PSScriptRoot\build.ps1"

$cp = (Join-Path $repo "sim\build\classes") + ";" + (Join-Path $repo "sim\build\vendor") + ";" + (
    (Get-ChildItem -Recurse -Path (Join-Path $repo "iFogSim2-vendor\jars") -Filter *.jar |
     ForEach-Object { $_.FullName }) -join ";")
$java = Join-Path $repo "tools\jdk-21.0.12.1+1\bin\java.exe"
if (-not (Test-Path $java) -and $env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
if (-not (Test-Path $java)) { $java = (Get-Command java -ErrorAction Stop).Source }
# Default heap headroom: rho1.1 at 600 s generates ~633k unit objects; the JVM default
# (1/4 of physical RAM) overflows on small machines. Override with BACKFILL_JVM args if needed.
$jvm = @("-Xmx2g")
if ($env:BACKFILL_JVM) { $jvm = ($env:BACKFILL_JVM -split '\s+') }

Push-Location $repo
try {
    if ($Mode -eq "run") {
        if (-not $Scenario) { $Scenario = "base" }
        # Same layout as the grid: results/runs/<scenario>/run_<scenario>_s<seed>_<policy>.csv
        & $java @jvm -cp $cp backfill.run.RunMain $Policy $Scenario $Seed (Join-Path $OutDir $Scenario)
        if ($LASTEXITCODE -ne 0) { throw "run failed: $Policy $Scenario $Seed" }
    } elseif ($Mode -eq "test") {
        & $java @jvm -cp $cp backfill.run.SelfTest
        if ($LASTEXITCODE -ne 0) { throw "self-test FAILED" }
    } else {
        $argList = @("-Xmx2g", "-Dbackfill.firstSeed=$FirstSeed", "-Dbackfill.jobs=$Jobs",
                     "-Dbackfill.ticks=$(-not $NoTicks)", "-cp", $cp, "backfill.run.ExperimentRunner", $OutDir, "$Seeds")
        # ExperimentRunner arg[2] = onlyPolicy, arg[3] = onlyScenario ("all" = no filter)
        if ($Policy -or $Scenario) { $argList += $(if ($Policy) { $Policy } else { "all" }) }
        if ($Scenario) { $argList += $Scenario }
        & $java @argList
        if ($LASTEXITCODE -ne 0) { throw "grid run failed" }
    }
} finally {
    Pop-Location
}
