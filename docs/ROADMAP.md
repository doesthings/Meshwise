# Meshwise roadmap

What people actually do with 3D files on a phone: check a model someone sent them, measure
something before printing or buying a part, make sure it fits the printer, fix the orientation,
and pass it on to a slicer or a colleague. This roadmap is ordered by how much each item helps
with those jobs. Checked items are done.

Ideas and votes are welcome in [issues](../../../issues).

Sections are themes, not version numbers: most improvements ship as patch releases (1.3.1,
1.3.2, ...), and a minor version (1.4) comes with a whole new tool. See
[RELEASING.md](RELEASING.md#version-numbers).

## How features get added (no bloat)

The app should stay as quick to understand as it is today, however much it can do.

- **A new feature joins an existing tool** as a tab or an option there, instead of adding a new
  button. The bottom bar keeps six buttons by default; everything else is one tap away.
- **The simple thing first.** Each tool opens on its most common job (Measure → Smart,
  Print → Orient); depth lives one tab or one row further down.
- **Taps do the obvious thing** in the tool that's open (tap a hole while measuring, tap a face
  while orienting), so most features need no extra buttons at all.
- **Results go on the model** (outlines, dimension lines, the build volume) rather than into
  long panels that hide it.
- **Nothing runs in the background by surprise**: heavy work starts when a tool needs it and can
  be cancelled.
- People can still hide, move or reorder tools (Settings → Customize toolbar).

## Smart measuring (1.2)

Measuring today means tapping two loose points on a mesh and hoping they landed in the right
place. Slicers and CAD viewers let you tap *features* instead. That's the goal here.

- [x] **Snap to features.** A tap is matched to the nearest corner, edge, hole rim, flat face
      or round surface instead of a random point on a triangle
- [x] **One-tap holes and bosses.** Tap the inside of a hole (or any round surface) to get its
      diameter, radius, axis and center, even on STL files with no CAD data
- [x] **One-tap faces.** Tap a flat face to see its area and orientation
- [x] **Edges.** Tap near an edge for its length (straight) or diameter (round rim)
- [x] **Smart pairs.** Two features give the measurement that makes sense: face to parallel face →
      wall thickness/gap; face to face at an angle → angle; hole to hole → center distance;
      point/hole to face → perpendicular distance; anything else → shortest distance plus ΔX/ΔY/ΔZ
- [x] **Highlight** of what was picked, so it's clear what's being measured
- [x] **Precision cursor.** Long-press and drag: a crosshair floats above your finger and shows
      the snap target before you let go
- [x] **Keep several results** on screen, each with copy and delete
- [x] The point modes (distance, angle, 3-point circle) snap to corners and edges too
- [ ] Exact radii and planes from the STEP/IGES geometry (instead of fitting the mesh)
- [ ] Measure along a section cut (wall thickness in the cut, cut outline length and area)
- [ ] Edit units inline on a result, and share all results as text

## Print prep (1.3)

All in one **Print** tool (it replaces the Weight button; the print estimate is one of its tabs).

- [x] **Lay flat on face**: tap a face and the model is turned so that face sits on the bed
- [x] **Suggested orientations**: compares the axis directions and the model's large flat faces
      by overhang area, contact with the bed and height, and offers the best three
- [x] Live stats for the current orientation: overhang share, height, area on the bed, with the
      overhang shading one tap away
- [x] Rotate in 90° steps, mirror, center and drop to the bed, undo, back to the file
- [x] **Scale**: percent, an exact size on one side, one-tap fixes for files exported in inches,
      cm or meters
- [x] **Printer beds**: common Bambu Lab, Prusa, Creality and Elegoo printers or a custom size; the
      build volume is drawn around the model, with "fits", "fits turned 90°" or "scale to fit"
- [x] Exports and projects keep the new orientation and size
- [x] **Print estimate** (1.3.1): print time, filament, weight and cost from slicing the model
      like a slicer, with each printer's usual slicer settings; layer height, infill and pattern,
      walls, normal or tree supports. Checked against Bambu Studio (see the README)
- [ ] Estimate for several copies, brims and rafts
- [ ] Free rotation with a gizmo
- [ ] More printers (resin printers too)
- [ ] Better overhang view: bridges vs. real overhangs, the support area drawn on the model
- [ ] Several copies on the bed, and "split to fit" for parts larger than the printer

## Smarter holes and checks

- [ ] **Screw and insert helper**: a measured hole is matched to the nearest standard size (M2–M8
      clearance, tap drill, common heat-set inserts) with how much it's off
- [ ] **Bolt circle**: tap three or more holes for the pattern's diameter and spacing
- [ ] Thin-wall and small-feature warnings for the chosen nozzle size
- [ ] Tolerance check between two parts of an assembly (gap or interference)

## Mesh repair and editing

- [ ] Fix flipped normals, remove degenerate and duplicate faces
- [ ] Fill small holes so the mesh becomes watertight
- [ ] Split a mesh into its separate shells, delete or keep parts, merge parts
- [ ] Simple cut: split the model with the section plane and export both halves
- [ ] Reduce triangle count (decimate) for huge scans before sharing

## G-code preview

- [ ] Open `.gcode` / `.bgcode`: layer slider, travel moves, feature colors (walls, infill, supports)
- [ ] Print time and filament as reported by the slicer, per-layer height and speed

## Files and sharing

- [ ] Library folders, favorites and tags; sort and search across the library
- [ ] Open a model from a direct download link (optional, uses the network only when asked)
- [ ] Annotated screenshots: draw arrows and notes on a snapshot before sharing
- [ ] Measurement report: a PNG or PDF with the model and all measurements
- [ ] Compare two versions of a model (overlay and highlight differences)
- [ ] Home-screen shortcut for a recent model

## Viewing

- [ ] Arbitrary section planes (tap a face to align the cut) and more than one cut at a time
- [ ] Assembly tree with nested groups from STEP files
- [ ] Clipping box
- [ ] Optional AR view to see a model on the table at real size (devices with ARCore)
- [ ] Level of detail for models with tens of millions of triangles

## App quality

- [ ] Tablet and landscape layouts with side panels
- [ ] Mouse, trackpad and keyboard shortcuts (Chromebooks, DeX)
- [ ] Translations
- [ ] TalkBack and large-text pass
- [ ] Play Store and F-Droid releases
- [ ] Opt-in crash log export (no automatic reporting)

## Done

- [x] 1.3.1: print estimate (time, filament, weight, cost) in the Print tool
- [x] 1.3: print prep (orient, size, printer fit)
- [x] Updates: the app checks GitHub once a day (can be turned off), shows what changed in every
      version you missed, and downloads, verifies and installs the update when you tap Update
- [x] 1.2: smart measuring (see above)
- [x] 1.1: redesigned interface, customizable toolbar, themes, accent colors, left-handed layout,
      field of view, custom backgrounds, gesture options
- [x] 1.0: STL/OBJ/3MF/PLY/OFF/ZIP and STEP/IGES/BREP, section cuts, measuring, wall thickness,
      overhangs, mesh check, exploded assemblies, weight estimates, projects and export
