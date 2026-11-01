# LinkSync 1.1 完整归档

本文件夹是 **LinkSync 1.1** 的完整项目备份，包含源码、资源、构建脚本、安装脚本、文档和已构建成品。即使原程序丢失，也可以据此重建。

> 本文描述的是本地完整归档。GitHub 仓库只跟踪源码、文档、脚本与图标：`dist/`（构建产物）和 `tools/`（工具链）被 [`.gitignore`](.gitignore) 排除，成品改由 [Releases](../../releases) 发布。

- 归档日期：2026-11-01
- 版本：LinkSync 1.1（Windows 1.1.0 / Android versionCode 2）
- 运行平台：Windows 10/11 x64；Android 10+（API 29+）

## 目录内容

| 路径 | 内容 |
|---|---|
| `pc/LinkSync.Pc/` | Windows 端 C# 源码（WinForms 托盘 + WebView2 窗口 + Kestrel + 剪贴板 + UDP 发现） |
| `android/` | Android 端 Kotlin 源码（前台服务 + NanoHTTPD + WebView + ZXing 扫码） |
| `shared/www/` | PC 与 Android 共用的 HTML/CSS/JS，含二维码库与 favicon |
| `docs/` | 设计/实现文档（含 v1、v2 方案与 R1 实现说明） |
| `scripts/` | 构建、安装、图标生成脚本 |
| `installer/` | Inno Setup 安装脚本与 WebView2 引导程序 |
| `dist/` | 已构建成品：安装包、便携 exe、APK、说明 |
| `artwork/` | LS 图标源文件（Windows ico/png、Android 自适应图标、SVG、生成脚本） |
| `tools/InnoSetup/` | Inno Setup 7.1.1，可直接编译安装包 |
| `README.md` | 项目使用说明 |
| `VERSION.txt` | 版本与构建信息 |
| `REBUILD.md` | 从零重建的完整步骤与工具版本 |
| `SHA256SUMS.txt` | 主要成品的 SHA-256 校验值 |

## 未包含的内容

以下依赖体积较大，未放入归档，但 `REBUILD.md` 给出了精确版本和下载方式：

- JDK 17（构建 Android 用）
- Android SDK Platform 35 / Build-Tools 35.0.0 / platform-tools
- Gradle 8.9（也可用 Android 目录下的 Gradle Wrapper，如已生成）
- .NET 8 SDK（构建 Windows 用）
- Microsoft Edge WebView2 Runtime（安装包会自动引导安装）

## 校验成品

在归档根目录执行：

```powershell
Get-FileHash .\dist\LinkSync-Setup-1.1.exe -Algorithm SHA256
Get-FileHash .\dist\LinkSync.apk -Algorithm SHA256
Get-FileHash .\dist\LinkSync-win64\LinkSync.exe -Algorithm SHA256
```

与 `SHA256SUMS.txt` 对照。

## 测试状态

已在开发机验证：

- Windows WebView2 窗口、托盘、单实例唤醒、二维码弹窗与二维码内容解码；
- 文本/文件/剪贴板与模拟对端互通；
- 安装包静默安装、启动、卸载；
- APK 编译、lint、签名、权限与自适应图标检查。

未验证：

- Android 真机扫码与后台保活（开发环境无可用 Android 设备）。

## 继续维护

1. 修改源码后，按 `REBUILD.md` 重新构建。
2. 改版本号：`pc/LinkSync.Pc/LinkSync.Pc.csproj`（`Version`）、`android/app/build.gradle.kts`（`versionCode` / `versionName`）、`installer/LinkSync.iss`（`MyAppVersion`）。
3. 重新生成图标：编辑 `artwork/make-icons.py` 或在项目根运行 `python scripts/make-icons.py`。
4. 重新生成校验值并更新 `SHA256SUMS.txt`。
