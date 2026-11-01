# LinkSync

[![License: GPL v3](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%2F11%20%7C%20Android%2010%2B-lightgrey)](#)

<img src="artwork/app.png" width="96" align="right" alt="LinkSync">

局域网内 **Windows PC 与 Android 手机/平板** 之间互传文件、同步剪贴板、互发文本的开源小工具。最多按 4 台设备设计，不做云、不做账号、**按需求不做加密与鉴权**（只在可信局域网使用）。

A small open-source tool for exchanging files, clipboard text and messages between a Windows PC and an Android phone/tablet on the same LAN. No cloud, no accounts, and by design **no encryption or authentication** — use it only on a trusted local network.

- Windows 端：托盘常驻 + 本机 HTTP 服务 + **WebView2 原生窗口**（不是浏览器标签页）
- Android 端：前台服务常驻 + 内嵌 HTTP 服务 + WebView 界面
- 设备发现：UDP 广播（47822）+ **二维码**（PC 显示、Android 扫描）+ 手动 IP
- 文件：HTTP 流式上传，保存到本机下载目录
- 剪贴板：PC 自动广播；Android 前台自动、后台用按钮/通知触发
- 文本：单发/多发，对方通知里点“复制”
- 图标：LS 品牌图标（exe / 托盘 / 窗口 / Android 自适应图标 / favicon）

> **下载现成安装包**（不随仓库分发，见 [Releases](../../releases)）：
> - `LinkSync-Setup-1.1.exe`（推荐，正规安装包，按用户安装，无需管理员）
> - `LinkSync.exe`（便携版，单文件自包含，无需装 .NET）
> - `LinkSync.apk`（Android 10+，debug 签名，仅用于侧载）
>
> 校验值见 [`SHA256SUMS.txt`](SHA256SUMS.txt)。

---

## 一、快速开始

### 1. Windows（安装版，推荐）

1. 双击 `LinkSync-Setup-1.1.exe`。
   - 安装到当前用户目录：`%LOCALAPPDATA%\Programs\LinkSync`，**不需要管理员权限**。
   - 会创建开始菜单快捷方式；桌面快捷方式为可选任务；开机自启请在应用内“设置”里开启。
   - 若系统没有 WebView2 Runtime，安装程序会尝试静默安装（需要联网）。
2. 首次运行会弹出 Windows 防火墙提示，请勾选 **专用网络** 并允许访问；否则手机连不上。
3. 启动后出现 **LinkSync 原生窗口**（内嵌 WebView2）；关闭窗口 = 隐藏到托盘，程序继续常驻。
4. 托盘菜单：打开界面 / 发送剪贴板 / 自动同步开关 / 开机自启 / 退出。
5. 文件默认保存到 `C:\Users\<你>\Downloads\LinkSync\<发送方设备名>\`。

> 未签名的 exe/安装包可能触发 SmartScreen“未知发布者”，选择“更多信息 → 仍要运行”。
>
> 端口固定为 **47821**。若被占用，程序会提示启动失败；关闭占用端口的程序后重试。
>
> 便携版：直接运行 `LinkSync.exe`，功能相同，不写开始菜单/卸载项。

### 2. Android

> 要求 Android 10（API 29）及以上。

1. 把 `LinkSync.apk` 传到手机（微信/QQ/数据线均可），点击安装；需要允许“安装未知应用”。
2. 打开 LinkSync，允许通知权限；首次扫码时会请求相机权限。
3. 建议点“设置 → 电池优化白名单”，并在系统设置里允许自启动/后台运行（尤其小米、华为、OPPO、vivo、三星）。
4. 保持通知栏里的 “LinkSync 正在运行”，服务才不会被系统回收。
5. 文件保存到 `Download/LinkSync/`。

### 3. 让两台设备互相发现

三种方式，任选：

- **自动发现**：两台设备连同一个 Wi-Fi，都打开 LinkSync，约 3–5 秒后设备页出现对方。
- **扫码（仅 Android 扫）**：在 PC 上点“显示二维码”，Android 上点“扫一扫”，对准屏幕即可。
  - 二维码内容是 `linksync://add?ip=...&port=...`，只承载地址，不包含安全凭据。
  - PC 没有摄像头，所以 PC 只负责显示；手机/平板负责扫。
- **手动添加**：在“手动添加设备”里输入 `对方IP:47821`。
  - PC 端口固定 47821。
  - Android 端口一般也是 47821；被占用会顺延，可在“设置 → 关于”看到实际端口。

> 公司/酒店 Wi-Fi 可能开启 AP 隔离，设备之间无法互访；此时用手机开热点、PC 连手机热点，再扫码/手动添加。

---

## 二、功能说明

### 二维码添加

- PC 设备页右上角“显示二维码”，弹窗展示当前局域网 IP 与端口。
- Android 设备页“扫一扫”，调用相机识别后自动 `/api/peers/add`，无需再输 IP。
- 若 PC 有多个网卡，二维码默认使用带网关的第一个 IPv4；若手机连不上，改用弹窗里显示的地址手动添加。

### 文件

- PC → 手机：设备卡片点“发文件”，可多选；手机自动接收并通知。
- 手机 → PC：设备卡片点“发文件”，选择文件；PC 收到后托盘提示。
- 大文件为流式传输，不占内存；不支持断点续传（本版简化）。
- 目标离线时“发文件”按钮不可用；文本可以排队。

### 文本

- 点“发文本”，勾选一台或多台设备，输入文字发送。
- 对方 Android 会收到通知，点“复制”即入剪贴板；PC 端会弹出托盘提示，可在剪贴板页复制。
- 离线设备可先勾选，连上后自动补发。

### 剪贴板

- PC 上复制文字 → 自动广播给所有在线设备（可在设置/托盘关闭）。
- Android 在前台时，复制文字会自动回传；在后台时受 Android 系统限制无法读取，请用：
  - 通知栏“发送剪贴板”按钮（会打开 App 并发送当前剪贴板）；
  - App 内“发送当前剪贴板”；
  - 托盘/设置里的“自动同步剪贴板”只控制 PC 端是否自动广播。
- 手机收到 PC 剪贴板会自动写入；Android 13+ 会显示系统的“已复制”浮层，属正常现象。

---

## 三、已知限制（重要）

1. **没有加密、没有配对、没有鉴权**：同一局域网内知道地址的人都能读写。请只在家庭/可信网络使用。
2. **Android 后台不能读剪贴板**（Android 10+ 系统限制），只能前台自动或一键触发；这是系统行为，不是 bug。
3. 手机被“强行停止”（设置里 force-stop）后无法自启，只能手动打开；被系统清理后会在下一次开机/打开 App 时恢复。
4. 仅支持同一局域网；不支持跨网络、公网中继。
5. 不支持断点续传、文件夹、图片剪贴板；这些是后续版本。
6. APK 为 debug 签名，仅用于侧载；上架应用商店需要另做签名与政策适配。
7. Windows 防火墙必须放行 47821/TCP、47822/UDP；否则表现为“对方一直离线”。
8. WebView2 窗口依赖 Microsoft Edge WebView2 Runtime；安装包会尝试补齐，缺失时程序回退到系统浏览器。
9. Android 扫码依赖相机；PC 端不提供扫码（无摄像头依赖）。

---

## 四、从源码构建

环境（`tools/` 不随仓库分发，首次构建时自动下载）：

- .NET 8 SDK（Windows）
- JDK 17
- Android SDK Platform 35 + Build-Tools 35
- Gradle 8.9
- Inno Setup 7（仅构建安装包需要）

> 新机器首次构建先运行 `./scripts/bootstrap-tools.ps1`，自动下载 JDK 17、Android SDK 35 和 Gradle 8.9（约 500 MB）。
> Inno Setup 不提供离线下载，请按 [REBUILD.md](REBUILD.md) 安装到 `tools/InnoSetup`（构建脚本按此路径查找 `ISCC.exe`）。

### Windows

```powershell
./scripts/build-pc.ps1
# 输出 dist/LinkSync-win64/LinkSync.exe
```

> 若 PowerShell 提示禁止运行脚本，改用：
> `powershell -ExecutionPolicy Bypass -File .\scripts\build-pc.ps1`

开发调试：

```powershell
dotnet build ./pc/LinkSync.Pc/LinkSync.Pc.csproj -c Release
./pc/LinkSync.Pc/bin/Release/net8.0-windows/LinkSync.exe
```

### Android

```powershell
./scripts/build-android.ps1
# 输出 dist/LinkSync.apk
```

脚本会用 `C:\LinkSyncBuild`、`C:\LinkSyncJdk`、`C:\LinkSyncSdk` 目录联接（junction）规避 Android Gradle Plugin 对非 ASCII 路径的检查；首次构建会下载 Gradle/AGP 依赖，约 3–5 分钟。

安装到已连接 USB 调试的手机：

```powershell
./scripts/install-apk.ps1
```

### 安装包

```powershell
./scripts/build-pc.ps1
./scripts/build-installer.ps1
# 输出 dist/LinkSync-Setup-1.1.exe
```

安装包是 Inno Setup 的按用户安装（`PrivilegesRequired=lowest`），包含开始菜单快捷方式、可选桌面快捷方式、卸载项，以及 WebView2 运行时引导安装。

---

## 五、故障排查

| 现象 | 处理 |
|---|---|
| 两台设备互相看不到 | 确认同一 Wi-Fi；Windows 防火墙放行；路由器关闭 AP 隔离；用二维码/手动添加 |
| 扫码提示连接失败 | 确认 PC 二维码里的 IP 手机能访问；PC 防火墙；换用弹窗里显示的其他 IP |
| 手机能发文本，但 PC 发文件失败 | 手机服务是否在运行；手机通知栏是否有 “LinkSync 正在运行”；尝试手动添加 |
| PC 窗口打不开/白屏 | 确认 WebView2 Runtime 已安装；安装包会尝试补齐；也可用浏览器访问 `http://127.0.0.1:47821` |
| 手机重启后不在线 | 打开一次 App；检查电池白名单/自启动；Android 15 以上确认通知权限 |
| 剪贴板不同步 | Android 后台限制；用通知按钮；确认两端“自动同步”开启 |
| 安装 APK 被拦截 | 允许“安装未知应用”；部分 ROM 需关闭“纯净模式” |
| 端口被占用 | 关闭占用 47821 的程序；Android 会自动顺延，PC 需释放端口 |
| 卸载 | Windows 设置 → 应用 → LinkSync → 卸载；或运行安装目录下 `unins000.exe` |

日志：

- Windows：`%LOCALAPPDATA%\LinkSync\log.txt`
- Android：`adb logcat -s LinkSync`

---

## 六、目录结构

```
shared/www/          PC 与 Android 共用的 Web UI（HTML/CSS/JS、二维码库）
pc/LinkSync.Pc/      Windows 端（WinForms 托盘 + WebView2 窗口 + Kestrel + 剪贴板 + UDP 发现）
android/             Android 端（Kotlin + 前台服务 + NanoHTTPD + WebView + ZXing 扫码）
installer/           Inno Setup 安装脚本与 WebView2 引导程序
artwork/             LS 图标源文件与生成脚本
docs/                设计与实现说明
scripts/             构建、图标生成与安装脚本
```

> `dist/`（构建产物）与 `tools/`（本地工具链）不纳入版本控制，见 [`.gitignore`](.gitignore)。

实现细节与接口见 [docs/R1-实现说明.md](docs/R1-实现说明.md)。

---

## 七、版本

- **1.1（2026-11-01）**：新增 PC 二维码展示、Android 扫码添加、LS 图标、PC WebView2 原生窗口、Inno Setup 安装包；修复单实例重复启动时打开浏览器的问题。
- 1.0：首个可用版本，局域网文件 / 文本 / 剪贴板互通与 Android 后台常驻。

---

## 八、参与贡献

欢迎提交 Issue 和 Pull Request。改动前请先看 [REBUILD.md](REBUILD.md) 了解构建流程，并确认：

- Windows 端改动：`./scripts/build-pc.ps1` 能通过；
- Android 端改动：`./scripts/build-android.ps1` 能产出 APK；
- 破坏性改动（端口、协议、API 路径）请同步更新 `docs/` 中的说明。

> 本项目的定位是“可信局域网内的小工具”，**暂不计划**加入账号体系、公网中继或云同步。需要加密/鉴权请先开 Issue 讨论，因为这会改动现有协议。

## 九、许可证

Copyright (C) 2026 LinkSync contributors

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。你可以自由使用、修改和再分发；分发修改后的版本时，需同样以 GPL-3.0 开放源代码。

