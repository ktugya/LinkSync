#requires -Version 5.1
<#
  下载并配置 LinkSync 构建所需的本地工具链到 <项目根>\tools\
  可重复运行：已存在的组件会跳过。
  需要：Windows 10/11、可访问 GitHub / dl.google.com / services.gradle.org。
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root 'tools'
$downloads = Join-Path $tools 'downloads'
New-Item -ItemType Directory -Force $tools, $downloads | Out-Null

function Download([string]$url, [string]$out) {
    if (Test-Path $out) { return }
    Write-Host "Downloading $url"
    & curl.exe --ssl-no-revoke -L --fail --retry 3 --retry-delay 2 -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "下载失败：$url" }
}

function Extract-Zip([string]$zip, [string]$dest) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    & tar.exe -xf $zip -C $dest
    if ($LASTEXITCODE -ne 0) { throw "解压失败：$zip" }
}

# 1. JDK 17
$jdkRoot = Join-Path $tools 'jdk17'
if (-not (Test-Path $jdkRoot) -or -not (Get-ChildItem $jdkRoot -Directory -ErrorAction SilentlyContinue)) {
    $jdkZip = Join-Path $downloads 'jdk17.zip'
    Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $jdkZip
    Extract-Zip $jdkZip $jdkRoot
}
$jdk = Get-ChildItem $jdkRoot -Directory | Select-Object -First 1
if (-not $jdk) { throw 'JDK 解压目录未找到' }

# 2. Android command-line tools
$sdkRoot = Join-Path $tools 'android-sdk'
$latest = Join-Path $sdkRoot 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $latest 'bin\sdkmanager.bat'))) {
    $sdkZip = Join-Path $downloads 'cmdline-tools.zip'
    Download 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $sdkZip
    $tmp = Join-Path $tools 'cmdline-tmp'
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Extract-Zip $sdkZip $tmp
    New-Item -ItemType Directory -Force (Join-Path $sdkRoot 'cmdline-tools') | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') $latest -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$sdkm = Join-Path $latest 'bin\sdkmanager.bat'

# 3. SDK packages + licenses
if (-not (Test-Path (Join-Path $sdkRoot 'platforms\android-35\android.jar'))) {
    $yes = ("y`n" * 40)
    $yes | & $sdkm --sdk_root="$sdkRoot" --licenses | Out-Null
    & $sdkm --sdk_root="$sdkRoot" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK 组件安装失败' }
}

# 4. Gradle 8.9
$gradleRoot = Join-Path $tools 'gradle-8.9'
if (-not (Test-Path (Join-Path $gradleRoot 'bin\gradle.bat'))) {
    $gradleZip = Join-Path $downloads 'gradle-8.9-bin.zip'
    Download 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $gradleZip
    Extract-Zip $gradleZip $tools
}

Write-Host ''
Write-Host '工具链已就绪：'
Write-Host "  JAVA_HOME    = $($jdk.FullName)"
Write-Host "  ANDROID_HOME = $sdkRoot"
Write-Host "  Gradle       = $gradleRoot\bin\gradle.bat"
Write-Host ''
Write-Host '下一步：'
Write-Host '  .\scripts\build-pc.ps1'
Write-Host '  .\scripts\build-android.ps1'
Write-Host '  .\scripts\build-installer.ps1'
