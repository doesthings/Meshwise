# Meshwise roadmap

What people actually do with 3D files on a phone: check a model someone sent them, measure
something before printing or buying a part, make sure it fits the printer, fix the orientation,
and pass it on to a slicer or a colleague. This roadmap is ordered by how much each item helps
with those jobs. Checked items are done.

Ideas and votes are welcome in [issues](../../../issues).

## 1.2: Smart measuring

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

## 1.3: Print prep

- [ ] **Lay flat on face**: tap a face and the model is rotated so that face sits on the bed
- [ ] Rotate in 90° steps or freely, mirror, center and drop to the bed
- [ ] **Scale**: percent, exact size per axis, "convert inches to mm" for files exported in the wrong unit
- [ ] **Printer beds**: pick a printer (Bambu, Prusa, Creality, Elegoo, Anycubic… or a custom size)
      and see the build volume; warning when the model doesn't fit
- [ ] Export the oriented and scaled model (STL/3MF) straight to a slicer app
- [ ] Footprint and first-layer area, height in layers for a chosen layer height
- [ ] Better overhang view: bridges vs. real overhangs, support area estimate

## 1.4: Mesh repair and editing

- [ ] Fix flipped normals, remove degenerate and duplicate faces
- [ ] Fill small holes so the mesh becomes watertight
- [ ] Split a mesh into its separate shells, delete or keep parts, merge parts
- [ ] Simple cut: split the model with the section plane and export both halves
- [ ] Reduce triangle count (decimate) for huge scans before sharing

## 1.5: G-code preview

- [ ] Open `.gcode` / `.bgcode`: layer slider, travel moves, feature colors (walls, infill, supports)
- [ ] Print time and filament as reported by the slicer, per-layer height and speed

## Files and sharing

- [ ] Library folders, favorites and tags; sort and search across the library
- [ ] Open a model from a direct download link (optional, uses the network only when asked)
- [ ] Annotated screenshots: draw arrows and notes on a snapshot before sharing
- [ ] Measurement report: a PNG or PDF with the model and all measurements
- [ ] Compare two versions of a model (overlay and highlight differences)

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

- [x] 1.1: redesigned interface, customizable toolbar, themes, accent colors, left-handed layout,
      field of view, custom backgrounds, gesture options
- [x] 1.0: STL/OBJ/3MF/PLY/OFF/ZIP and STEP/IGES/BREP, section cuts, measuring, wall thickness,
      overhangs, mesh check, exploded assemblies, weight estimates, projects and export
