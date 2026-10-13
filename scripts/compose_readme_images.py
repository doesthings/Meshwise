#!/usr/bin/env python3
"""Turns the raw screenshot captures into the images used by the README.

Input:  build/screenshots/*.png  (1080x2400, from the Robolectric screenshot tests)
Output: docs/screenshots/*.png      (rounded phone-style frames, 540 px wide)
        docs/images/hero.png        (banner with the app icon and a selection of screens)

Requires Pillow (pip install pillow).
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RAW = os.path.join(ROOT, "build", "screenshots")
SHOTS = os.path.join(ROOT, "docs", "screenshots")
IMAGES = os.path.join(ROOT, "docs", "images")


def font(size, bold=False):
    candidates = [
        "/usr/share/fonts/opentype/inter/Inter-Bold.otf" if bold else "/usr/share/fonts/opentype/inter/Inter-Regular.otf",
        "/usr/share/fonts/noto/NotoSans-Bold.ttf" if bold else "/usr/share/fonts/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/TTF/DejaVuSans.ttf",
    ]
    for c in candidates:
        if os.path.exists(c):
            return ImageFont.truetype(c, size)
    return ImageFont.load_default()


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def phone(img, width):
    """Scales a capture and wraps it in a thin dark bezel with rounded corners."""
    scale = width / img.size[0]
    inner = img.convert("RGB").resize((width, int(img.size[1] * scale)), Image.LANCZOS)
    inner = rounded(inner, int(36 * scale * 2))
    bezel = int(10 * scale * 2)
    w, h = inner.size[0] + bezel * 2, inner.size[1] + bezel * 2
    frame = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, w - 1, h - 1], radius=int(44 * scale * 2), fill=(18, 20, 24, 255))
    frame.alpha_composite(inner, (bezel, bezel))
    return frame


def with_shadow(img, offset=(0, 18), blur=28, opacity=150):
    pad = blur * 2
    shadow = Image.new("RGBA", (img.size[0] + pad * 2, img.size[1] + pad * 2), (0, 0, 0, 0))
    mask = img.split()[3].point(lambda a: opacity if a > 0 else 0)
    shadow.paste((0, 0, 0, 255), (pad + offset[0], pad + offset[1]), mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    shadow.alpha_composite(img, (pad, pad))
    return shadow


def gradient(size, top, bottom):
    w, h = size
    g = Image.new("RGB", size)
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return g


def main():
    if not os.path.isdir(RAW):
        sys.exit("no raw screenshots in " + RAW)
    os.makedirs(SHOTS, exist_ok=True)
    os.makedirs(IMAGES, exist_ok=True)
    names = sorted(f for f in os.listdir(RAW) if f.endswith(".png"))
    framed = {}
    for n in names:
        img = Image.open(os.path.join(RAW, n))
        fr = phone(img, 540)
        fr.save(os.path.join(SHOTS, n), optimize=True)
        framed[n] = fr
        print("framed", n)

    # Hero banner.
    picks = [n for n in ["02_viewer.png", "03_section.png", "05_thickness.png", "04_measure.png", "08_material.png"] if n in framed]
    if picks:
        W, H = 2400, 1160
        banner = gradient((W, H), (34, 44, 52), (16, 20, 24)).convert("RGBA")
        d = ImageDraw.Draw(banner)
        # Soft accent glows.
        glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        gd.ellipse([1300, -300, 2600, 700], fill=(208, 188, 255, 60))
        gd.ellipse([-300, 600, 900, 1500], fill=(255, 109, 0, 40))
        banner.alpha_composite(glow.filter(ImageFilter.GaussianBlur(120)))
        title_x = 110
        icon_path = os.path.join(IMAGES, "icon.png")  # from scripts/make_icon.py
        if os.path.exists(icon_path):
            icon = Image.open(icon_path).convert("RGBA").resize((150, 150), Image.LANCZOS)
            banner.alpha_composite(icon, (110, 152))
            title_x = 290
        d.text((title_x, 150), "Meshwise", font=font(118, True), fill=(240, 236, 248))
        d.text((114, 340), "Free, open-source 3D model viewer for Android", font=font(43), fill=(208, 188, 255))
        lines = ["STL · OBJ · 3MF · PLY · OFF · STEP · IGES · BREP · ZIP",
                 "Section caps · Wall thickness · Measure · Explode",
                 "Weight · Materials · Mesh check · Export",
                 "Themes · Custom toolbar · Left-handed layout"]
        for i, line in enumerate(lines):
            d.text((114, 450 + i * 64), line, font=font(36), fill=(200, 204, 210))
        x = 1180
        thumbs = picks[:3]
        for i, n in enumerate(thumbs):
            fr = framed[n]
            s = 0.78 if i != 1 else 0.86
            t = fr.resize((int(fr.size[0] * s * 0.98), int(fr.size[1] * s * 0.98)), Image.LANCZOS)
            t = with_shadow(t)
            y = (H - t.size[1]) // 2 + (0 if i == 1 else 40)
            banner.alpha_composite(t, (x + i * 370 - 60, y))
        banner.convert("RGB").save(os.path.join(IMAGES, "hero.png"), optimize=True)
        print("hero.png")


if __name__ == "__main__":
    main()
