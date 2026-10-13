#!/usr/bin/env python3
"""Generates the Meshwise launcher icon from one piece of geometry.

The mark is a faceted low-poly gem (the mesh) sliced by a section plane: the cut face shows
the orange hatched cap that Meshwise draws in its section view.

Output:
  app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive icon foreground
  app/src/main/res/drawable/ic_launcher_background.xml   adaptive icon background
  app/src/main/res/drawable/ic_launcher_monochrome.xml   themed icon (Android 13+)
  docs/images/icon.png                                   512 px preview (needs Pillow)

Coordinates use the 108x108 adaptive icon canvas; launchers show at least the central
circle of radius 33, so the mark stays inside radius ~30.
"""
import math
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

C = (52.5, 54.5)
R_OUT = 28.0   # gem outline
R_IN = 13.5    # central facet

BG_TOP, BG_BOTTOM = "#2B2540", "#141821"   # dark slate with a violet tint, like the app
CAP = "#FF8A3D"                            # section cap orange (app accent)
CAP_DARK = "#C2531A"
HATCH = "#7A2E0B"
OUTLINE = "#141821"
# Lavender facet shades, light (facing the light) to dark.
SHADES = ["#F1EAFF", "#DCCDFF", "#C3AEFA", "#A68DEB", "#8A6FD6", "#6C53B8", "#523E96"]

LIGHT = (-0.55, -0.83)  # from the top left (screen y points down)

# Section plane: points p with dot(p - C, N) > D are cut away and show the cap.
N = (math.cos(math.radians(35)), math.sin(math.radians(35)))
D = 7.0


def pt(r, deg):
    a = math.radians(deg)
    return (C[0] + r * math.cos(a), C[1] - r * math.sin(a))


def gem_faces():
    """Icosahedron seen along a 3-fold axis: 1 + 3 + 6 triangles inside a hexagon."""
    inner = {d: pt(R_IN, d) for d in (90, 210, 330)}
    outer = {d: pt(R_OUT, d) for d in (30, 90, 150, 210, 270, 330)}
    faces = [(inner[90], inner[210], inner[330])]
    faces += [(inner[90], inner[210], outer[150]), (inner[210], inner[330], outer[270]),
              (inner[330], inner[90], outer[30])]
    for d in (90, 210, 330):
        faces += [(inner[d], outer[d], outer[(d + 60) % 360]), (inner[d], outer[(d - 60) % 360], outer[d])]
    return faces, [outer[d] for d in (90, 150, 210, 270, 330, 30)]


def side(p):
    return (p[0] - C[0]) * N[0] + (p[1] - C[1]) * N[1] - D


def clip(poly, keep_below=True):
    """Sutherland-Hodgman clip of a convex polygon against the section plane."""
    out = []
    for i, a in enumerate(poly):
        b = poly[(i + 1) % len(poly)]
        sa, sb = side(a), side(b)
        ina, inb = (sa <= 0) == keep_below, (sb <= 0) == keep_below
        if ina:
            out.append(a)
        if ina != inb:
            t = sa / (sa - sb)
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out


def shade(face):
    (x0, y0), (x1, y1), (x2, y2) = face
    cx, cy = (x0 + x1 + x2) / 3 - C[0], (y0 + y1 + y2) / 3 - C[1]
    r = math.hypot(cx, cy)
    if r < 1e-6:
        lit = 0.75  # the central facet faces the viewer
    else:
        lit = 0.5 + 0.5 * (cx * LIGHT[0] + cy * LIGHT[1]) / r * min(1.0, r / R_IN)
    return SHADES[max(0, min(len(SHADES) - 1, round((1 - lit) * (len(SHADES) - 1))))]


def hatch_lines(cap, spacing=3.3):
    """45° hatch segments clipped to the convex cap polygon."""
    d = (math.cos(math.radians(-55)), math.sin(math.radians(-55)))
    n = (-d[1], d[0])
    proj = [p[0] * n[0] + p[1] * n[1] for p in cap]
    segs = []
    k = math.ceil(min(proj) / spacing)
    while k * spacing < max(proj):
        c = k * spacing
        hits = []
        for i, a in enumerate(cap):
            b = cap[(i + 1) % len(cap)]
            pa, pb = a[0] * n[0] + a[1] * n[1] - c, b[0] * n[0] + b[1] * n[1] - c
            if (pa < 0) != (pb < 0):
                t = pa / (pa - pb)
                hits.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
        if len(hits) == 2:
            segs.append(tuple(hits))
        k += 1
    return segs


def plane_outline():
    """The section plane drawn as a thin frame sticking out past the gem."""
    t = (-N[1], N[0])
    base = (C[0] + N[0] * D, C[1] + N[1] * D)
    half, depth = 34.0, 9.0
    # A rectangle seen at an angle: a parallelogram whose short sides lean along the cut.
    lean = (t[0] * 0.55 + N[0] * 0.85, t[1] * 0.55 + N[1] * 0.85)
    corners = []
    for s1, s2 in ((1, 1), (-1, 1), (-1, -1), (1, -1)):
        corners.append((base[0] + t[0] * half * s1 + lean[0] * depth * s2,
                        base[1] + t[1] * half * s1 + lean[1] * depth * s2))
    return corners


def path(poly, close=True):
    s = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in poly)
    return s + (" Z" if close else "")


def geometry():
    faces, hexagon = gem_faces()
    kept = [(clip(list(f)), shade(f)) for f in faces]
    kept = [(p, col) for p, col in kept if len(p) >= 3]
    cap = clip(hexagon, keep_below=False)
    return kept, cap, hatch_lines(cap), hexagon, plane_outline()


def vector(body):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Generated by scripts/make_icon.py; edit the script, not this file. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n' + body + "</vector>\n")


def write_xml():
    kept, cap, hatches, hexagon, plane = geometry()
    hatch_path = " ".join(f"M{a[0]:.2f},{a[1]:.2f} L{b[0]:.2f},{b[1]:.2f}" for a, b in hatches)
    kept_outline = clip(hexagon)
    edges = " ".join(path(p) for p, _ in kept)

    fg = "    <!-- Section plane (back half) -->\n"
    fg += (f'    <path android:fillColor="{CAP}" android:fillAlpha="0.12" android:strokeColor="{CAP}" '
           f'android:strokeAlpha="0.85" android:strokeWidth="0.9" '
           f'android:strokeLineJoin="round" android:pathData="{path(plane)}" />\n')
    fg += "    <!-- Gem facets -->\n"
    for p, col in kept:
        fg += f'    <path android:fillColor="{col}" android:pathData="{path(p)}" />\n'
    fg += (f'    <path android:strokeColor="{OUTLINE}" android:strokeAlpha="0.45" android:strokeWidth="0.7" '
           f'android:strokeLineJoin="round" android:pathData="{edges}" />\n')
    fg += "    <!-- Hatched section cap -->\n"
    fg += f'    <path android:fillColor="{CAP}" android:pathData="{path(cap)}" />\n'
    fg += (f'    <path android:strokeColor="{HATCH}" android:strokeAlpha="0.45" android:strokeWidth="0.8" '
           f'android:strokeLineCap="round" android:pathData="{hatch_path}" />\n')
    fg += (f'    <path android:strokeColor="{CAP_DARK}" android:strokeWidth="1.2" android:strokeLineJoin="round" '
           f'android:pathData="{path(cap)}" />\n')
    fg += "    <!-- Outline -->\n"
    fg += (f'    <path android:strokeColor="{OUTLINE}" android:strokeWidth="1.3" android:strokeLineJoin="round" '
           f'android:pathData="{path(kept_outline)}" />\n')

    bg = ('    <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z">\n'
          '        <aapt:attr name="android:fillColor">\n'
          '            <gradient android:type="linear" android:startX="54" android:startY="0" android:endX="54" android:endY="108"\n'
          f'                android:startColor="{BG_TOP}" android:endColor="{BG_BOTTOM}" />\n'
          '        </aapt:attr>\n    </path>\n')

    mono = ""
    for p, col in kept:
        alpha = 1.0 - SHADES.index(col) / (len(SHADES) - 1) * 0.6
        mono += f'    <path android:fillColor="#FFFFFF" android:fillAlpha="{alpha:.2f}" android:pathData="{path(p)}" />\n'
    # Themed icons only use alpha: a half-transparent cap with solid hatch lines.
    mono += f'    <path android:fillColor="#FFFFFF" android:fillAlpha="0.5" android:pathData="{path(cap)}" />\n'
    mono += (f'    <path android:strokeColor="#FFFFFF" android:strokeWidth="0.8" android:strokeLineCap="round" '
             f'android:pathData="{hatch_path}" />\n')

    for name, body in (("ic_launcher_foreground", fg), ("ic_launcher_background", bg), ("ic_launcher_monochrome", mono)):
        with open(os.path.join(DRAWABLE, name + ".xml"), "w") as f:
            f.write(vector(body))
        print(name + ".xml")


def write_png(size=512):
    from PIL import Image, ImageDraw

    ss = 4
    S = size * ss
    k = S / 108.0

    def sc(poly):
        return [(x * k, y * k) for x, y in poly]

    def rgb(h):
        return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

    top, bot = rgb(BG_TOP), rgb(BG_BOTTOM)
    img = Image.new("RGBA", (S, S))
    px = ImageDraw.Draw(img)
    for y in range(S):
        t = y / (S - 1)
        px.line([(0, y), (S, y)], fill=tuple(round(top[i] + (bot[i] - top[i]) * t) for i in range(3)) + (255,))

    kept, cap, hatches, hexagon, plane = geometry()
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dl = ImageDraw.Draw(layer)
    dl.polygon(sc(plane), fill=rgb(CAP) + (31,))
    dl.line(sc(plane + plane[:1]), fill=rgb(CAP) + (217,), width=round(0.9 * k), joint="curve")
    img = Image.alpha_composite(img, layer)
    d = ImageDraw.Draw(img)
    for p, col in kept:
        d.polygon(sc(p), fill=rgb(col))
    edge = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    de = ImageDraw.Draw(edge)
    for p, _ in kept:
        de.line(sc(p + p[:1]), fill=rgb(OUTLINE) + (115,), width=round(0.7 * k), joint="curve")
    img = Image.alpha_composite(img, edge)
    d = ImageDraw.Draw(img)
    d.polygon(sc(cap), fill=rgb(CAP))
    hl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dh = ImageDraw.Draw(hl)
    for a, b in hatches:
        dh.line(sc([a, b]), fill=rgb(HATCH) + (115,), width=round(0.8 * k))
    img = Image.alpha_composite(img, hl)
    d = ImageDraw.Draw(img)
    d.line(sc(cap + cap[:1]), fill=rgb(CAP_DARK), width=round(1.2 * k), joint="curve")
    outline = clip(hexagon)
    d.line(sc(outline + outline[:1]), fill=rgb(OUTLINE), width=round(1.3 * k), joint="curve")

    # Preview with the usual launcher mask (squircle-ish rounded square), full bleed 108 -> 72 visible.
    crop = round(18 * k)
    img = img.crop((crop, crop, S - crop, S - crop)).resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size * ss - 1, size * ss - 1], radius=size * ss * 0.23, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    path_out = os.path.join(ROOT, "docs", "images", "icon.png")
    out.save(path_out, optimize=True)
    print("docs/images/icon.png")


if __name__ == "__main__":
    write_xml()
    try:
        write_png()
    except ImportError:
        print("Pillow missing: skipped docs/images/icon.png")
