$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$jdk = Get-ChildItem (Join-Path $root 'tools\jdk17') -Directory -ErrorAction Stop | Select-Object -First 1
$sdk = Join-Path $root 'tools\android-sdk'
$gradle = Join-Path $root 'tools\gradle-8.9\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "Gradle not found: $gradle" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found: $sdk" }

$links = @()

function New-AsciiLink {
    param([string]$Kind, [string]$Target)
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $paths = @("C:\LinkSync-$Kind-$suffix", "$env:TEMP\LinkSync-$Kind-$suffix")
    foreach ($p in $paths) {
        try {
            New-Item -ItemType Junction -Path $p -Target $Target | Out-Null
            return $p
        } catch {
            continue
        }
    }
    throw "Cannot create ASCII junction for $Kind"
}

# Android Gradle Plugin / aapt dislikes non-ASCII paths. Build through temporary
# ASCII junctions so the whole project can live in any directory.
try {
    $buildLink = New-AsciiLink -Kind 'build' -Target $root
    $links += $buildLink
    $jdkLink = New-AsciiLink -Kind 'jdk' -Target $jdk.FullName
    $links += $jdkLink
    $sdkLink = New-AsciiLink -Kind 'sdk' -Target $sdk
    $links += $sdkLink

    $env:JAVA_HOME = $jdkLink
    $env:ANDROID_HOME = $sdkLink
    $env:ANDROID_SDK_ROOT = $sdkLink

    Write-Host 'Building Android APK...'
    & $gradle -p (Join-Path $buildLink 'android') --no-daemon :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'gradle assembleDebug failed' }

    $apk = Join-Path $buildLink 'android\app\build\outputs\apk\debug\app-debug.apk'
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    Copy-Item $apk (Join-Path $dist 'LinkSync.apk') -Force
    Write-Host ("OK: " + (Join-Path $dist 'LinkSync.apk'))
}
finally {
    foreach ($link in $links) {
        try { [System.IO.Directory]::Delete($link) } catch { }
    }
}
