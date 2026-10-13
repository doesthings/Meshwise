#!/usr/bin/env python3
"""Generates the demo models shipped with Meshwise (samples/ and the app's assets).

All models are created procedurally here and are released under CC0.
Requires CadQuery (pip install cadquery numpy).

    python3 scripts/make_samples.py [output-dir]
"""
import math
import os
import struct
import sys
import zipfile

import cadquery as cq
import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "samples")
os.makedirs(OUT, exist_ok=True)


def color(hex_rgb):
    h = hex_rgb.lstrip("#")
    return cq.Color(int(h[0:2], 16) / 255.0, int(h[2:4], 16) / 255.0, int(h[4:6], 16) / 255.0)


# --------------------------------------------------------------------------- assembly
def hex_bolt(length, d=8.0, head_h=5.5, head_w=13.0):
    head = cq.Workplane("XY").polygon(6, head_w / math.cos(math.pi / 6)).extrude(head_h).edges(">Z").chamfer(0.8)
    shank = cq.Workplane("XY").circle(d / 2).extrude(-length).faces("<Z").chamfer(0.8)
    return head.union(shank)


def hex_nut(d=8.0, h=6.5, w=13.0):
    nut = cq.Workplane("XY").polygon(6, w / math.cos(math.pi / 6)).extrude(h).edges(">Z or <Z").chamfer(0.6)
    return nut.faces(">Z").workplane().hole(d)


def washer(d=8.4, od=16.0, t=1.6):
    return cq.Workplane("XY").circle(od / 2).circle(d / 2).extrude(t)


def bearing_assembly():
    plate_t = 5.0
    # L-shaped bracket: base plate plus an upright flange.
    base = (
        cq.Workplane("XY")
        .box(150, 90, plate_t, centered=(True, True, False))
        .edges("|Z").fillet(6)
        .faces(">Z").workplane()
        .rect(52, 52, forConstruction=True).vertices().hole(8.6)
        .faces(">Z").workplane().hole(34)
        .faces(">Z").workplane().pushPoints([(62, 32), (62, -32), (-62, 32), (-62, -32)]).hole(6.5)
    )
    upright = (
        cq.Workplane("YZ", origin=(-75, 0, 0))
        .rect(90, 70, centered=(True, False)).extrude(plate_t)
        .faces("<X").workplane().center(0, 35).hole(14)
    )
    bracket = base.union(upright)

    # Square-flange bearing housing.
    flange = (
        cq.Workplane("XY", origin=(0, 0, plate_t))
        .box(70, 70, 12, centered=(True, True, False))
        .edges("|Z").fillet(8)
        .faces(">Z").workplane()
        .rect(52, 52, forConstruction=True).vertices().cboreHole(8.6, 15, 2)
    )
    boss = cq.Workplane("XY", origin=(0, 0, plate_t + 12)).circle(27).extrude(16).faces(">Z").edges().chamfer(2.5)
    housing = flange.union(boss).faces(">Z").workplane().hole(40)

    # Bearing insert and grease nipple.
    insert_ring = (
        cq.Workplane("XY", origin=(0, 0, plate_t + 6))
        .circle(20).circle(12.6).extrude(24)
        .faces(">Z").edges().chamfer(1.0)
    )
    nipple = (
        cq.Workplane("XY", origin=(0, 0, plate_t + 28))
        .center(18, 18).polygon(6, 9).extrude(5).faces(">Z").workplane().circle(2.5).extrude(4)
    )

    shaft = cq.Workplane("XY", origin=(0, 0, -45)).circle(12.5).extrude(130).faces(">Z or <Z").chamfer(1.5)

    asm = cq.Assembly(name="Bearing assembly")
    asm.add(bracket, name="Bracket", color=color("#F2E35A"))
    asm.add(housing, name="Flange housing", color=color("#B8BCC0"))
    asm.add(insert_ring, name="Bearing insert", color=color("#D7DADD"))
    asm.add(nipple, name="Grease nipple", color=color("#C9A13B"))
    asm.add(shaft, name="Shaft", color=color("#9FC7A6"))

    corners = [(26, 26), (26, -26), (-26, 26), (-26, -26)]
    top = plate_t + 12 - 2  # counterbore floor
    for i, (x, y) in enumerate(corners):
        asm.add(hex_bolt(28), name=f"Bolt M8x28 #{i + 1}", color=color("#8C9197"),
                loc=cq.Location(cq.Vector(x, y, top)))
        asm.add(washer(), name=f"Washer M8 #{i + 1}", color=color("#EEEEEE"),
                loc=cq.Location(cq.Vector(x, y, -1.6)))
        asm.add(hex_nut(), name=f"Nut M8 #{i + 1}", color=color("#7A7F85"),
                loc=cq.Location(cq.Vector(x, y, -1.6 - 6.5)))
    for i, (x, y) in enumerate([(62, 32), (62, -32)]):
        standoff = cq.Workplane("XY").circle(5).circle(3.1).extrude(30)
        asm.add(standoff, name=f"Standoff #{i + 1}", color=color("#A8352C"),
                loc=cq.Location(cq.Vector(x, y, -30)))
    return asm


# --------------------------------------------------------------------------- gear
def involute_gear(teeth=24, module=2.0, width=10.0, bore=10.0):
    pa = math.radians(20)
    rp = module * teeth / 2
    rb = rp * math.cos(pa)
    ra = rp + module
    rf = rp - 1.25 * module

    def involute(r):
        t = math.sqrt(max((r / rb) ** 2 - 1, 0))
        return (rb * (math.cos(t) + t * math.sin(t)), rb * (math.sin(t) - t * math.cos(t)))

    tooth_angle = 2 * math.pi / teeth
    # Half tooth thickness angle at the pitch circle.
    inv_pa = math.tan(pa) - pa
    half = math.pi / (2 * teeth) + inv_pa
    pts = []
    n = 12
    for k in range(teeth):
        base_a = k * tooth_angle
        flank = []
        for i in range(n + 1):
            r = max(rb, rf) + (ra - max(rb, rf)) * i / n
            x, y = involute(r)
            a = math.atan2(y, x)
            flank.append((r, a))
        right = [(r * math.cos(base_a - half + a), r * math.sin(base_a - half + a)) for r, a in flank]
        left = [(r * math.cos(base_a + half - a), r * math.sin(base_a + half - a)) for r, a in reversed(flank)]
        root_start = (rf * math.cos(base_a - half - 0.02), rf * math.sin(base_a - half - 0.02))
        pts.append(root_start)
        pts.extend(right)
        pts.extend(left)
        root_end = (rf * math.cos(base_a + half + 0.02), rf * math.sin(base_a + half + 0.02))
        pts.append(root_end)
    gear = cq.Workplane("XY").polyline(pts).close().extrude(width)
    hub = cq.Workplane("XY").circle(rf * 0.42).extrude(width + 4)
    gear = gear.union(hub)
    # Lightening holes and bore with keyway.
    holes = [(rf * 0.68 * math.cos(a), rf * 0.68 * math.sin(a)) for a in np.linspace(0, 2 * math.pi, 6, endpoint=False)]
    gear = gear.faces(">Z").workplane().pushPoints(holes).hole(rf * 0.32)
    gear = gear.faces(">Z").workplane().hole(bore)
    key = cq.Workplane("XY").center(bore / 2, 0).rect(3, 3).extrude(width + 4)
    return gear.cut(key)


# --------------------------------------------------------------------------- knot (mesh)
def trefoil_knot_stl(path, segs=720, sides=48, r_tube=4.2, scale=10.0):
    ts = np.linspace(0, 2 * np.pi, segs, endpoint=False)

    def curve(t):
        return np.stack([np.sin(t) + 2 * np.sin(2 * t), np.cos(t) - 2 * np.cos(2 * t), -np.sin(3 * t)], axis=-1) * scale

    c = curve(ts)
    d = curve(ts + 1e-4) - curve(ts - 1e-4)
    T = d / np.linalg.norm(d, axis=1, keepdims=True)
    # Rotation-minimising frame.
    N = np.zeros_like(T)
    ref = np.array([0.0, 0.0, 1.0])
    n0 = np.cross(T[0], ref)
    N[0] = n0 / np.linalg.norm(n0)
    for i in range(1, segs):
        v = N[i - 1] - np.dot(N[i - 1], T[i]) * T[i]
        N[i] = v / np.linalg.norm(v)
    B = np.cross(T, N)
    ang = np.linspace(0, 2 * np.pi, sides, endpoint=False)
    verts = c[:, None, :] + r_tube * (np.cos(ang)[None, :, None] * N[:, None, :] + np.sin(ang)[None, :, None] * B[:, None, :])
    # Close the frame twist mismatch by blending the last ring onto the first.
    verts[:, :, 2] += 3 * scale  # lift above the floor
    verts = verts.reshape(-1, 3)
    tris = []
    for i in range(segs):
        i2 = (i + 1) % segs
        for j in range(sides):
            j2 = (j + 1) % sides
            a, b, cc, dd = i * sides + j, i2 * sides + j, i2 * sides + j2, i * sides + j2
            tris.append((a, b, cc))
            tris.append((a, cc, dd))
    write_binary_stl(path, verts, np.array(tris))


def write_binary_stl(path, verts, tris):
    with open(path, "wb") as f:
        f.write(b"Meshwise sample".ljust(80, b" "))
        f.write(struct.pack("<I", len(tris)))
        v = verts[tris]  # (n,3,3)
        n = np.cross(v[:, 1] - v[:, 0], v[:, 2] - v[:, 0])
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        rec = np.zeros(len(tris), dtype=np.dtype([("n", "<f4", 3), ("v", "<f4", (3, 3)), ("a", "<u2")]))
        rec["n"] = n
        rec["v"] = v
        f.write(rec.tobytes())


# --------------------------------------------------------------------------- vase (thickness demo)
def twisted_vase():
    def section(r, z, twist):
        pts = []
        for i in range(60):
            a = 2 * math.pi * i / 60
            rr = r * (1 + 0.12 * math.cos(6 * a))
            pts.append(cq.Vector(rr * math.cos(a + twist), rr * math.sin(a + twist), z))
        return cq.Wire.assembleEdges([cq.Edge.makeSpline(pts, periodic=True)])

    levels = [(28, 0, 0.0), (36, 35, 0.35), (30, 75, 0.75), (20, 110, 1.05), (24, 130, 1.2)]
    wires = [section(r, z, tw) for r, z, tw in levels]
    outer = cq.Solid.makeLoft(wires)
    # Walls thin out towards the rim: inner loft with a growing radius.
    inner_levels = [(r - t, z, tw) for (r, z, tw), t in zip(levels, [6.0, 3.0, 2.0, 1.2, 0.9])]
    inner_levels[0] = (inner_levels[0][0], 6, inner_levels[0][2])
    inner_levels[-1] = (inner_levels[-1][0], 131, inner_levels[-1][2])
    iw = [section(r, z, tw) for r, z, tw in inner_levels]
    inner = cq.Solid.makeLoft(iw)
    return cq.Workplane("XY").add(outer).cut(cq.Workplane("XY").add(inner))


# --------------------------------------------------------------------------- keychain (multi-colour 3MF)
def keychain_parts():
    w, h, t = 64.0, 30.0, 3.0
    base = (
        cq.Workplane("XY").box(w, h, t, centered=(True, True, False)).edges("|Z").fillet(8)
        .faces(">Z").workplane().center(-w / 2 + 7, 0).hole(5)
    )
    rim = (
        cq.Workplane("XY", origin=(0, 0, t)).rect(w - 3, h - 3).extrude(1.2).edges("|Z").fillet(6.5)
        .cut(cq.Workplane("XY", origin=(0, 0, t)).rect(w - 7.5, h - 7.5).extrude(1.2).edges("|Z").fillet(4.5))
        .cut(cq.Workplane("XY", origin=(-w / 2 + 7, 0, t)).circle(5.5).extrude(1.2))
    )
    text = (
        cq.Workplane("XY", origin=(4.5, 0, t))
        .text("Meshwise", 8.0, 1.4, font="DejaVu Sans", kind="bold", halign="center", valign="center")
    )
    underline = cq.Workplane("XY", origin=(4.5, -9.5, t)).rect(40, 1.6).extrude(0.9)
    return [("Base", base, "#1E3A8A"), ("Rim", rim, "#F97316"), ("Text", text, "#F8FAFC"), ("Underline", underline, "#F97316")]


def write_3mf(path, parts, tolerance=0.05):
    objects = []
    for name, wp, hexc in parts:
        shape = wp.val() if hasattr(wp, "val") else wp
        if hasattr(wp, "vals") and len(wp.vals()) > 1:
            shape = cq.Compound.makeCompound(wp.vals())
        verts, tris = shape.tessellate(tolerance, 0.2)
        objects.append((name, [(v.x, v.y, v.z) for v in verts], tris, hexc))
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">',
             ' <resources>', '  <basematerials id="1">']
    for name, _, _, hexc in objects:
        lines.append(f'   <base name="{name}" displaycolor="{hexc}FF"/>')
    lines.append("  </basematerials>")
    for i, (name, verts, tris, _) in enumerate(objects):
        lines.append(f'  <object id="{i + 2}" type="model" name="{name}" pid="1" pindex="{i}"><mesh><vertices>')
        lines.extend(f'<vertex x="{x:.5f}" y="{y:.5f}" z="{z:.5f}"/>' for x, y, z in verts)
        lines.append("</vertices><triangles>")
        lines.extend(f'<triangle v1="{a}" v2="{b}" v3="{c}"/>' for a, b, c in tris)
        lines.append("</triangles></mesh></object>")
    lines.append(" </resources>")
    lines.append(" <build>" + "".join(f'<item objectid="{i + 2}"/>' for i in range(len(objects))) + "</build>")
    lines.append("</model>")
    ct = ('<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
          '<Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>')
    rels = ('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>'
            '</Relationships>')
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", ct)
        z.writestr("_rels/.rels", rels)
        z.writestr("3D/3dmodel.model", "\n".join(lines))


def main():
    print("bearing assembly...")
    asm = bearing_assembly()
    asm.save(os.path.join(OUT, "bearing_assembly.step"))

    print("gear...")
    gear = involute_gear()
    cq.exporters.export(gear, os.path.join(OUT, "spur_gear.stl"), tolerance=0.02, angularTolerance=0.1)

    print("knot...")
    trefoil_knot_stl(os.path.join(OUT, "trefoil_knot.stl"))

    print("vase...")
    vase = twisted_vase()
    cq.exporters.export(vase, os.path.join(OUT, "twisted_vase.stl"), tolerance=0.03, angularTolerance=0.1)

    print("keychain...")
    write_3mf(os.path.join(OUT, "keychain.3mf"), keychain_parts())
    print("done ->", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
