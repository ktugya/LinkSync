$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root 'dist\LinkSync-win64\LinkSync.exe'
if (-not (Test-Path $exe)) { throw "EXE not found: $exe (run scripts/build-pc.ps1 first)" }
Start-Process -FilePath $exe
Write-Host "LinkSync 已启动，浏览器界面：http://127.0.0.1:47821"
