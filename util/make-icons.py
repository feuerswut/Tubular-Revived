#!/usr/bin/env python3
"""Regenerates every app icon from the master icon assets/images/icon.png.

    python util/make-icons.py

Needs Pillow and numpy. Writes (all paths relative to the repo root):
  app/src/main/res/mipmap-*/ic_launcher.png              legacy launcher icon (rounded square)
  app/src/main/res/mipmap-*/ic_launcher_foreground.png   adaptive icon foreground
  app/src/main/res/mipmap-*/ic_launcher_monochrome.png   adaptive icon monochrome (themed icons)
  app/src/main/res/mipmap-xhdpi/tubular_tv_banner.png    Android TV banner
  app/src/main/res/drawable-*/ic_tubular_white.png        notification small icon
  app/src/main/res/drawable-xxhdpi/splash_foreground.png  splash screen glyph
  app/src/main/res/values/colors.xml                      ic_launcher_background
  fastlane/metadata/android/en-US/images/{icon,featureGraphic}.png
  iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png
  out/icon-preview.png                                    contact sheet to look at (git-ignored)

The glyph is separated from the flat background by "unmixing" every pixel into background,
cream and dark-red parts, so anti-aliased edges stay clean on any background.
assets/images/wordmark.png (the "Tubular" lettering of the TV banner) is extracted from the
existing banner the first time and kept as an asset afterwards.
"""
import os
import re
import sys
from collections import Counter

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'assets', 'images', 'icon.png')
WORDMARK = os.path.join(ROOT, 'assets', 'images', 'wordmark.png')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
written = []


def out(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    written.append(os.path.relpath(path, ROOT))


def scaled(img, size):
    return img.resize(size if isinstance(size, tuple) else (size, size), Image.LANCZOS)


# ---------------------------------------------------------------- load and unmix
icon = Image.open(SRC).convert('RGBA')
if icon.size[0] != icon.size[1]:
    sys.exit('icon must be square')
arr = np.asarray(icon).astype(np.float64)
if arr[..., 3].min() < 255:  # flatten onto the dominant colour if the master has transparency
    sys.exit('master icon is expected to be opaque')
rgb = arr[..., :3]

packed = (rgb[..., 0].astype(np.int64) << 16) | (rgb[..., 1].astype(np.int64) << 8) | rgb[..., 2].astype(np.int64)
common = [c for c, _ in Counter(packed.ravel().tolist()).most_common(3)]
cols = [np.array([(c >> 16) & 255, (c >> 8) & 255, c & 255], dtype=np.float64) for c in common]
bg = cols[0]
cream, dark = sorted(cols[1:], key=lambda c: -c.sum())
print('background', bg, 'cream', cream, 'dark', dark)

basis = np.stack([cream - bg, dark - bg], axis=1)            # 3x2
d = (rgb - bg).reshape(-1, 3)
coef = np.clip(d @ np.linalg.pinv(basis).T, 0, 1)            # per pixel: [cream, dark] amount
a_cream = coef[:, 0].reshape(rgb.shape[:2])
a_dark = coef[:, 1].reshape(rgb.shape[:2])
alpha = np.clip(a_cream + a_dark, 0, 1)
safe = np.where(alpha > 0, alpha, 1)[..., None]
glyph_rgb = (a_cream[..., None] * cream + a_dark[..., None] * dark) / safe
glyph = Image.fromarray(np.dstack([glyph_rgb, alpha * 255]).round().astype(np.uint8), 'RGBA')


def mask_image(a, color):
    h, w = a.shape
    px = np.zeros((h, w, 4), dtype=np.uint8)
    px[..., :3] = color
    px[..., 3] = (np.clip(a, 0, 1) * 255).round().astype(np.uint8)
    return Image.fromarray(px, 'RGBA')


mono = mask_image(alpha, (0, 0, 0))             # whole glyph, for themed launcher icons
white_tri = mask_image(a_cream, (255, 255, 255))  # cream triangle only, for status bar icons
bg_hex = '#%02X%02X%02X' % tuple(int(round(v)) for v in bg)
bbox = glyph.getchannel('A').point(lambda v: 255 if v > 10 else 0).getbbox()
glyph_crop = glyph.crop(bbox)
white_crop = white_tri.crop(white_tri.getchannel('A').point(lambda v: 255 if v > 10 else 0).getbbox())


def centered(layer, canvas, fraction):
    """Layer (1024 master geometry) scaled by `fraction` and centred on a square canvas."""
    size = round(canvas * fraction)
    img = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
    img.alpha_composite(scaled(layer, size), ((canvas - size) // 2, (canvas - size) // 2))
    return img


# ---------------------------------------------------------------- launcher icons
for name, scale in DENSITIES.items():
    # legacy: rounded square (pre-Android 8 does not mask icons)
    size = round(48 * scale)
    ss = 4
    mask = Image.new('L', (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size * ss - 1, size * ss - 1), radius=round(size * ss * 0.22), fill=255)
    legacy = scaled(icon, size)
    legacy.putalpha(mask.resize((size, size), Image.LANCZOS))
    out(os.path.join(RES, f'mipmap-{name}', 'ic_launcher.png'), legacy)

    # adaptive: 108dp canvas, only the centre 66dp are always visible. The glyph's far corners
    # are ~34dp from the centre in the master, so scale to 92% to stay inside the safe circle.
    canvas = round(108 * scale)
    out(os.path.join(RES, f'mipmap-{name}', 'ic_launcher_foreground.png'), centered(glyph, canvas, 0.92))
    out(os.path.join(RES, f'mipmap-{name}', 'ic_launcher_monochrome.png'), centered(mono, canvas, 0.92))

    # notification small icon: 24dp, white silhouette of the triangle
    n = round(24 * scale)
    note = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    inner = round(n * 0.86)
    ratio = white_crop.width / white_crop.height
    w, h = (inner, round(inner / ratio)) if ratio >= 1 else (round(inner * ratio), inner)
    note.alpha_composite(scaled(white_crop, (w, h)), ((n - w) // 2, (n - h) // 2))
    out(os.path.join(RES, f'drawable-{name}', 'ic_tubular_white.png'), note)

# adaptive icon definition: dedicated monochrome layer, background colour from the icon
xml = os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml')
text = open(xml, encoding='utf-8').read()
text = re.sub(r'(<monochrome android:drawable=")[^"]+(")', r'\1@mipmap/ic_launcher_monochrome\2', text)
open(xml, 'w', encoding='utf-8', newline='\n').write(text)
colors = os.path.join(RES, 'values', 'colors.xml')
text = open(colors, encoding='utf-8').read()
text = re.sub(r'(<color name="ic_launcher_background">)[^<]+(</color>)', r'\g<1>' + bg_hex + r'\g<2>', text)
open(colors, 'w', encoding='utf-8', newline='\n').write(text)

# ---------------------------------------------------------------- splash screen
splash = Image.new('RGBA', (300, 300), (0, 0, 0, 0))  # 100dp at xxhdpi
side = round(300 * 0.9)
fit = glyph_crop.copy()
fit.thumbnail((side, side), Image.LANCZOS)
splash.alpha_composite(fit, ((300 - fit.width) // 2, (300 - fit.height) // 2))
out(os.path.join(RES, 'drawable-xxhdpi', 'splash_foreground.png'), splash)
old_vector = os.path.join(RES, 'drawable', 'splash_foreground.xml')
if os.path.exists(old_vector):
    os.remove(old_vector)
    written.append('(removed) ' + os.path.relpath(old_vector, ROOT))

# ---------------------------------------------------------------- About page logo (shared Compose UI)
# Drawn clipped to a circle over `logoBackground` (theme/Color.kt), so keep the glyph well inside.
compose_drawables = os.path.join(ROOT, 'shared', 'src', 'commonMain', 'composeResources', 'drawable')
about = Image.new('RGBA', (512, 512), (0, 0, 0, 0))
about_glyph = glyph_crop.copy()
about_glyph.thumbnail((round(512 * 0.62), round(512 * 0.62)), Image.LANCZOS)
about.alpha_composite(about_glyph, ((512 - about_glyph.width) // 2, (512 - about_glyph.height) // 2))
out(os.path.join(compose_drawables, 'ic_foreground.png'), about)
old_about = os.path.join(compose_drawables, 'ic_foreground.xml')
if os.path.exists(old_about):
    os.remove(old_about)
    written.append('(removed) ' + os.path.relpath(old_about, ROOT))
color_kt = os.path.join(ROOT, 'shared', 'src', 'commonMain', 'kotlin', 'net', 'newpipe', 'app', 'theme', 'Color.kt')
text = open(color_kt, encoding='utf-8').read()
text = re.sub(r'(val logoBackground = Color\(0xFF)[0-9A-Fa-f]{6}(\))', r'\g<1>' + bg_hex[1:] + r'\g<2>', text)
open(color_kt, 'w', encoding='utf-8', newline='\n').write(text)

# ---------------------------------------------------------------- TV banner
banner_path = os.path.join(RES, 'mipmap-xhdpi', 'tubular_tv_banner.png')
if not os.path.exists(WORDMARK):
    old = np.asarray(Image.open(banner_path).convert('RGB')).astype(np.float64)
    g = old[..., 1]
    g_bg = np.median(g[:4, :4])
    a = np.clip((g - g_bg) / (255 - g_bg), 0, 1)
    region = np.zeros_like(a)
    region[:, 118:] = a[:, 118:]                      # right of the old glyph: the lettering only
    ys, xs = np.where(region > 0.05)
    crop = region[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    out(WORDMARK, mask_image(crop, (255, 255, 255)))
word = Image.open(WORDMARK).convert('RGBA')
tinted = Image.new('RGBA', word.size, tuple(int(v) for v in cream) + (255,))
tinted.putalpha(word.getchannel('A'))
bh = 84
g_small = glyph_crop.copy()
g_small.thumbnail((bh * 2, bh), Image.LANCZOS)
gap = 18
total = g_small.width + gap + tinted.width
banner = Image.new('RGBA', (320, 180), tuple(int(round(v)) for v in bg) + (255,))
x0 = (320 - total) // 2
banner.alpha_composite(g_small, (x0, (180 - g_small.height) // 2))
banner.alpha_composite(tinted, (x0 + g_small.width + gap, (180 - tinted.height) // 2))
out(banner_path, banner.convert('RGB'))

# ---------------------------------------------------------------- store / iOS
fl = os.path.join(ROOT, 'fastlane', 'metadata', 'android', 'en-US', 'images')
out(os.path.join(fl, 'icon.png'), scaled(icon, 512).convert('RGB'))
feature = Image.new('RGB', (1024, 500), tuple(int(round(v)) for v in bg))
fg = glyph_crop.copy()
fg.thumbnail((900, 360), Image.LANCZOS)
feature_rgba = feature.convert('RGBA')
feature_rgba.alpha_composite(fg, ((1024 - fg.width) // 2, (500 - fg.height) // 2))
out(os.path.join(fl, 'featureGraphic.png'), feature_rgba.convert('RGB'))
out(os.path.join(ROOT, 'iosApp', 'iosApp', 'Assets.xcassets', 'AppIcon.appiconset', 'app-icon-1024.png'),
    icon.convert('RGB'))

# ---------------------------------------------------------------- preview sheet
sheet = Image.new('RGBA', (1240, 760), (60, 60, 68, 255))


def paste(img, x, y, bg_color=None):
    if bg_color:
        tile = Image.new('RGBA', img.size, bg_color)
        tile.alpha_composite(img)
        img = tile
    sheet.alpha_composite(img.convert('RGBA'), (x, y))


paste(scaled(Image.open(os.path.join(RES, 'mipmap-xxxhdpi', 'ic_launcher.png')), 192), 20, 20)
adaptive = Image.new('RGBA', (432, 432), tuple(int(round(v)) for v in bg) + (255,))
adaptive.alpha_composite(Image.open(os.path.join(RES, 'mipmap-xxxhdpi', 'ic_launcher_foreground.png')))
circle = Image.new('L', (432, 432), 0)
ImageDraw.Draw(circle).ellipse((432 * 0.1667, 432 * 0.1667, 432 * 0.8333, 432 * 0.8333), fill=255)  # 72dp window
adaptive.putalpha(circle)
paste(scaled(adaptive, 192), 232, 20)
mono_view = Image.open(os.path.join(RES, 'mipmap-xxxhdpi', 'ic_launcher_monochrome.png'))
paste(scaled(mono_view, 192), 444, 20, (230, 230, 235, 255))
paste(Image.open(os.path.join(RES, 'drawable-xxxhdpi', 'ic_tubular_white.png')), 656, 20 + 48, (60, 60, 68, 255))
paste(Image.open(os.path.join(RES, 'drawable-xhdpi', 'ic_tubular_white.png')), 780, 20 + 72, (60, 60, 68, 255))
paste(scaled(splash, 192), 880, 20, tuple(int(round(v)) for v in bg) + (255,))
paste(scaled(splash, 192), 1050, 20, (24, 24, 28, 255))
paste(Image.open(banner_path).convert('RGBA'), 20, 260)
paste(scaled(Image.open(os.path.join(fl, 'featureGraphic.png')).convert('RGBA'), (640, 312)), 360, 260)
out(os.path.join(ROOT, 'out', 'icon-preview.png'), sheet)

print('\n'.join(written))
print('background colour', bg_hex)
