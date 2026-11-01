# 从零重建 LinkSync 1.1

本文件假设你拿到的是 `LinkSync-1.1-Full` 文件夹。按下面步骤可在新 Windows 机器上重建 Windows 端、Android APK 和安装包。

## 1. 工具链版本（开发机实测）

| 工具 | 版本 | 用途 |
|---|---|---|
| Windows | 10/11 x64 | 构建与运行 |
| .NET SDK | 8.0.133 | 编译 Windows 端 |
| JDK | Temurin 17.0.20.1+1 | 编译 Android |
| Android SDK Platform | android-35 | compileSdk |
| Android Build-Tools | 35.0.0 | aapt2 / apksigner |
| Android Platform-Tools | 37.0.1 | adb（可选） |
| Gradle | 8.9 | Android 构建 |
| Inno Setup | 7.1.1 | Windows 安装包 |
| WebView2 Runtime | Evergreen | PC 原生窗口（安装包会引导安装） |
| Python + Pillow | 3.x + Pillow 12.x | 仅重新生成图标时需要 |

下载入口：

- .NET 8 SDK：<https://dotnet.microsoft.com/download/dotnet/8.0>
- Temurin 17：<https://adoptium.net/temurin/releases/?version=17>
- Android command-line tools：<https://developer.android.com/studio#command-tools>
- Gradle 8.9：<https://gradle.org/releases/>
- Inno Setup：<https://jrsoftware.org/isdl.php>

## 2. 还原 `tools/` 目录

归档与仓库都不包含 `tools/` 下的第三方工具链，需按下面布局放置（名称可不同，但构建脚本按此路径查找）。

> 运行 `.\scripts\bootstrap-tools.ps1` 可自动下载并配置 JDK 17、Android SDK 35、Gradle 8.9（约 500 MB 下载）。Inno Setup 需自行安装到 `tools\InnoSetup`（见 2.4）。

```
tools/
├─ jdk17/
│  └─ jdk-17.0.20.1+1/
├─ android-sdk/
│  ├─ cmdline-tools/latest/
│  ├─ platform-tools/
│  ├─ platforms/android-35/
│  └─ build-tools/35.0.0/
├─ gradle-8.9/
└─ InnoSetup/            # 归档已包含
```

### 2.1 JDK

下载 Temurin 17 的 Windows x64 zip，解压到 `tools\jdk17`，使其下出现 `jdk-17.x.x+x` 目录。

### 2.2 Android SDK

1. 下载 command-line tools zip，解压为 `tools\android-sdk\cmdline-tools\latest`（注意是 `latest\bin\sdkmanager.bat`）。
2. 安装组件：

```powershell
$env:JAVA_HOME = (Resolve-Path '.\tools\jdk17\jdk-17.0.20.1+1').Path
$sdk = (Resolve-Path '.\tools\android-sdk').Path
$sdkm = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
& $sdkm --sdk_root="$sdk" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### 2.3 Gradle

下载 `gradle-8.9-bin.zip`，解压后使 `tools\gradle-8.9\bin\gradle.bat` 存在。

### 2.4 Inno Setup

仓库与归档都不含安装包编译器。从 <https://jrsoftware.org/isdl.php> 下载 Inno Setup 7，安装时把目标目录设为 `tools\InnoSetup`，确保 `tools\InnoSetup\ISCC.exe` 存在。

## 3. 构建

在归档根目录打开 PowerShell：

```powershell
# Windows（需要 .NET 8 SDK）
.\scripts\build-pc.ps1
# 产物：dist\LinkSync-win64\LinkSync.exe

# Android（需要 JDK 17 + Android SDK + Gradle 8.9）
$env:JAVA_HOME = (Resolve-Path '.\tools\jdk17\jdk-17.0.20.1+1').Path
$env:ANDROID_HOME = (Resolve-Path '.\tools\android-sdk').Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\scripts\build-android.ps1
# 产物：dist\LinkSync.apk

# 安装包（需要上一步的 exe）
.\scripts\build-installer.ps1
# 产物：dist\LinkSync-Setup-1.1.exe
```

> 若 PowerShell 禁止运行脚本，使用：
> `powershell -ExecutionPolicy Bypass -File .\scripts\build-pc.ps1`
>
> Android 构建脚本会自动创建 ASCII 目录联接 `C:\LinkSyncBuild`、`C:\LinkSyncJdk`、`C:\LinkSyncSdk`，以规避 Android Gradle Plugin 对非 ASCII 路径的检查。如果这些联接已被其他项目占用，脚本会报错，需先删除或改脚本里的路径。

## 4. 版本号维护

| 位置 | 字段 |
|---|---|
| `pc/LinkSync.Pc/LinkSync.Pc.csproj` | `<Version>`、`<FileVersion>` |
| `android/app/build.gradle.kts` | `versionCode`、`versionName` |
| `installer/LinkSync.iss` | `#define MyAppVersion`、输出文件名 |
| `README.md`、`ARCHIVE.md` | 版本说明 |

修改后重新执行构建与安装包脚本。

## 5. 图标维护

图标源文件在 `artwork/`。重新生成（需要 Python + Pillow）：

```powershell
python .\scripts\make-icons.py
```

脚本会同时更新：

- `pc/LinkSync.Pc/Assets/app.ico`、`app.png`
- `android/app/src/main/res/mipmap-*`、`drawable-xxxhdpi/ic_launcher_foreground.png`
- `shared/www/favicon.svg`

## 6. 构建后的自检

```powershell
# Windows 启动与健康检查
Start-Process .\dist\LinkSync-win64\LinkSync.exe --open
Invoke-WebRequest http://127.0.0.1:47821/api/health

# Android 安装（手机已开 USB 调试）
.\scripts\install-apk.ps1

# 安装包安装/卸载
Start-Process .\dist\LinkSync-Setup-1.1.exe
```

## 7. 已知构建注意事项

- 项目路径尽量使用 ASCII（如 `C:\LinkSync\`）。当前构建脚本已针对中文路径做 junction 规避，但其他工具仍可能出问题。
- Android 端依赖 AndroidX（扫码库需要），`android/gradle.properties` 中 `android.useAndroidX=true` 不要改回 false。
- `targetSdk=34` 是为了避免 Android 15 强制 edge-to-edge 导致 WebView 顶到状态栏；升级 targetSdk 时需要补 insets 处理。
- APK 当前为 debug 签名，仅供侧载；正式分发需要生成自己的 release keystore 并配置 `signingConfigs`。
- Windows 端单文件发布依赖 `IncludeNativeLibrariesForSelfExtract=true`（WebView2Loader.dll 需要解压）；不要删除该属性。
- 安装包是 per-user 安装，不写 HKLM，不需要管理员；WebView2 缺失时会尝试运行 `installer/redist/MicrosoftEdgeWebview2Setup.exe`。
