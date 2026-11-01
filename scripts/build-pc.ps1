$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$proj = Join-Path $root 'pc\LinkSync.Pc\LinkSync.Pc.csproj'
$out = Join-Path $root 'dist\LinkSync-win64'

Write-Host "Publishing Windows build..."
dotnet publish $proj -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true `
    -p:IncludeAllContentForSelfExtract=true -p:DebugType=None -p:DebugSymbols=false `
    -o $out
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed" }

Write-Host "OK: $(Join-Path $out 'LinkSync.exe')"
