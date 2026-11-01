# LS 图标源文件

本目录保存 LinkSync 的图标资源，便于以后单独替换或重新生成。

| 文件 | 用途 |
|---|---|
| `app.ico` | Windows exe / 托盘 / 窗口 / 安装包，多尺寸（16–256） |
| `app.png` | 1024×1024 主图，用于文档、商店、重新导出 |
| `favicon.svg` | Web UI 浏览器标签页 |
| `android/` | Android 自适应图标与旧版 mipmap |
| `make-icons.py` | 生成上述文件的 Python 脚本（需要 Pillow） |

## 重新生成

在项目根目录执行（不是在本目录）：

```powershell
python .\scripts\make-icons.py
```

脚本会写回：

- `pc/LinkSync.Pc/Assets/app.ico`、`app.png`
- `android/app/src/main/res/mipmap-*/ic_launcher.png`
- `android/app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png`
- `shared/www/favicon.svg`

本目录中的副本需要手动同步；如果只改脚本，建议重新复制一遍。

## 设计

- 主色：`#5B8CFF` → `#8F6BFF` 渐变
- 内容：白色加粗 `LS`
- Windows：圆角方形
- Android：蓝色背景 + 白色 LS 前景（自适应图标安全区）
- favicon：SVG 圆角方形 + `LS` 文本
