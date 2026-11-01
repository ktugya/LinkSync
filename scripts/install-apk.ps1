$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root 'tools\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $root 'dist\LinkSync.apk'

if (-not (Test-Path $apk)) { throw "APK not found: $apk (run scripts/build-android.ps1 first)" }
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) {
    Write-Host "没有检测到已授权的 Android 设备。请用 USB 连接并打开开发者选项/USB 调试。"
    & $adb devices
    exit 1
}

& $adb install -r $apk
