# Build script: compiles the iFogSim2 vendor sources (once, cached) and then our backfill
# simulator into sim\build. Needs JDK 21+ (upstream iFogSim2 needs List.getLast()): the
# repo-local tools\jdk-21.0.12.1+1 if present, otherwise JAVA_HOME, otherwise javac on PATH.
$ErrorActionPreference = "Stop"

$repo     = Split-Path -Parent $PSScriptRoot
$bin      = Join-Path $repo "tools\jdk-21.0.12.1+1\bin"
if (-not (Test-Path (Join-Path $bin "javac.exe")) -and $env:JAVA_HOME) { $bin = Join-Path $env:JAVA_HOME "bin" }
$javac    = Join-Path $bin "javac.exe"
if (-not (Test-Path $javac)) { $javac = (Get-Command javac -ErrorAction Stop).Source }
$build    = Join-Path $repo "sim\build"
$clVendor = Join-Path $build "vendor"
$clSim    = Join-Path $build "classes"

$jars = (Get-ChildItem -Recurse -Path (Join-Path $repo "iFogSim2-vendor\jars") -Filter *.jar |
         ForEach-Object { $_.FullName }) -join ";"

# ---- vendor (cached: skipped when already compiled) ----
if (-not (Test-Path (Join-Path $clVendor "org"))) {
    Write-Host "[build] compiling iFogSim2-vendor ..."
    New-Item -ItemType Directory -Force -Path $clVendor | Out-Null
    $srcs = (Get-ChildItem -Recurse -Path (Join-Path $repo "iFogSim2-vendor\src") -Filter *.java |
             ForEach-Object { $_.FullName })
    $opts = Join-Path $build "vendor_opts.txt"
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("-encoding"); $lines.Add("UTF-8")
    $lines.Add("-nowarn")
    $lines.Add("-cp");      $lines.Add($jars)
    $lines.Add("-d");       $lines.Add($clVendor)
    foreach ($s in $srcs) { $lines.Add($s) }
    [System.IO.File]::WriteAllLines($opts, $lines, [System.Text.UTF8Encoding]::new($false))
    & $javac "@$opts"
    if ($LASTEXITCODE -ne 0) { throw "[build] vendor compile FAILED" }
}

# ---- simulator ----
New-Item -ItemType Directory -Force -Path $clSim | Out-Null
$srcs = (Get-ChildItem -Recurse -Path (Join-Path $repo "sim\src") -Filter *.java |
         ForEach-Object { $_.FullName })
$opts = Join-Path $build "sim_opts.txt"
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("-encoding"); $lines.Add("UTF-8")
$lines.Add("-nowarn")
$lines.Add("-cp");      $lines.Add("$clVendor;$jars")
$lines.Add("-d");       $lines.Add($clSim)
foreach ($s in $srcs) { $lines.Add($s) }
[System.IO.File]::WriteAllLines($opts, $lines, [System.Text.UTF8Encoding]::new($false))
& $javac "@$opts"
if ($LASTEXITCODE -ne 0) { throw "[build] simulator compile FAILED" }
Write-Host "[build] OK -> $clSim"
