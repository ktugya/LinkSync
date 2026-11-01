$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$iscc = Join-Path $root 'tools\InnoSetup\ISCC.exe'
$exe = Join-Path $root 'dist\LinkSync-win64\LinkSync.exe'
$iss = Join-Path $root 'installer\LinkSync.iss'

if (-not (Test-Path $iscc)) { throw "Inno Setup not found: $iscc" }
if (-not (Test-Path $exe)) { throw "Windows build not found: $exe (run scripts/build-pc.ps1 first)" }

& $iscc $iss
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }
Write-Host "OK: $(Join-Path $root 'dist\LinkSync-Setup-1.1.exe')"
