#!/usr/bin/env python3
"""Generate LinkSync LS icons (Windows ico/png, Android adaptive foreground, favicon)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "pc" / "LinkSync.Pc" / "Assets"
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"
SHARED = ROOT / "shared" / "www"
for p in (ASSETS, ANDROID_RES / "drawable-xxxhdpi", ANDROID_RES / "mipmap-anydpi-v26",
          ANDROID_RES / "values", SHARED):
    p.mkdir(parents=True, exist_ok=True)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\segoeuib.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
    r"C:\Windows\Fonts\msyhbd.ttc",
]
FONT = next((f for f in FONT_CANDIDATES if Path(f).exists()), None)
if not FONT:
    raise SystemExit("no bold font found")


def gradient(size: int) -> Image.Image:
    top = (91, 140, 255)
    bottom = (143, 107, 255)
    img = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        color = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=color)
    return img


def rounded(size: int, radius_ratio: float = 0.22) -> Image.Image:
    img = gradient(size).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=round(size * radius_ratio), fill=255
    )
    img.putalpha(mask)
    return img


def draw_ls(img: Image.Image, ratio: float, y_offset: float = 0.0) -> None:
    size = img.width
    font = ImageFont.truetype(FONT, round(size * ratio))
    draw = ImageDraw.Draw(img)
    text = "LS"
    bbox = draw.textbbox((0, 0), text, font=font)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    x = (size - w) / 2 - bbox[0]
    y = (size - h) / 2 - bbox[1] + size * y_offset
    draw.text((x, y), text, font=font, fill=(255, 255, 255, 255))


# Windows / general app icon
master = rounded(1024)
draw_ls(master, 0.49, -0.015)
master.save(ASSETS / "app.png")
master.save(
    ASSETS / "app.ico",
    format="ICO",
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)

# Android adaptive icon foreground (transparent, safe zone)
fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
draw_ls(fg, 0.36, -0.01)
fg.save(ANDROID_RES / "drawable-xxxhdpi" / "ic_launcher_foreground.png")

# Legacy launcher PNGs (in case a launcher ignores adaptive icons)
for density, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = ANDROID_RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    master.resize((px, px), Image.Resampling.LANCZOS).save(folder / "ic_launcher.png")

(SHARED / "favicon.svg").write_text(
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#5B8CFF"/><stop offset="1" stop-color="#8F6BFF"/>
</linearGradient></defs>
<rect width="64" height="64" rx="14" fill="url(#g)"/>
<text x="32" y="45" text-anchor="middle" font-family="Segoe UI, Arial, sans-serif"
 font-size="31" font-weight="700" fill="#fff" letter-spacing="1">LS</text>
</svg>
""",
    encoding="utf-8",
)

(ANDROID_RES / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(
    """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
""",
    encoding="utf-8",
)

(ANDROID_RES / "values" / "colors.xml").write_text(
    """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#5B8CFF</color>
</resources>
""",
    encoding="utf-8",
)

print("icons generated:", ASSETS / "app.ico", ANDROID_RES / "drawable-xxxhdpi" / "ic_launcher_foreground.png")
