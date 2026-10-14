# Changelog

All notable changes to Meshwise are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). From 1.3.1 on, most releases raise only
the last number; see [version numbers](docs/RELEASING.md#version-numbers).

## [1.3.1] - 2026-10-14

A real print estimate: time, filament, weight and cost, worked out like a slicer.

### Added
- **Estimate** tab in the Print tool (it replaces the Weight tab). Meshwise slices the model into
  layers like a slicer: walls from the contour, top and bottom layers, internal bridges, sparse
  and solid infill, thin gaps, the skirt, overhangs and supports. Every move is timed with the
  printer's speeds and accelerations, Marlin-style corner speeds, the hotend's flow limit and the
  minimum layer time. Shows print time, filament length and weight, cost and the support's share,
  with a breakdown of where the time goes.
- Layer height, infill density and pattern (lines, grid, cubic, gyroid), walls and normal or tree
  supports can be changed; the result updates and stays in view while you do.
- Each printer preset carries its usual slicer settings (Bambu Studio's for Bambu Lab, Creality,
  Elegoo and the Prusa MINI+; PrusaSlicer's for the MK4/MK4S, CORE One and XL). Without a printer,
  generic settings are used.
- Materials set their flow ratio and how fast they can be melted (PETG, ABS, ASA, TPU, nylon and
  PC print slower than PLA); 1.75 or 2.85 mm filament.
- A hint with a one-tap fix when the model has overhangs that need supports.
- The README explains how the estimate was checked against Bambu Studio (92 slices, 4 printers).

### Changed
- Version numbers: most releases now raise only the last number (1.3.1, 1.3.2, ...); see
  docs/RELEASING.md.
- The default infill for the estimate is 15 % (the slicers' default).

## [1.3.0] - 2026-10-14

Print prep, and updates that tell you what changed.

### Added
- **Print tool** (it replaces the Weight button; weight is one of its tabs, so the bottom bar
  keeps six buttons):
  - **Orient**: tap a face to lay it flat on the bed; rotate in 90° steps; mirror; undo; back to
    the file as it was. Live overhang share, build height and area on the bed, with the overhang
    shading one tap away.
  - **Suggest** compares the axis directions and the model's large flat faces by overhang area,
    contact with the bed and height, and offers the best three. It marks the current
    orientation when that is already among the best.
  - **Size**: scale by percent or to an exact size on one side; one-tap fixes for files exported
    in inches, cm or meters.
  - **Printer**: Bambu Lab, Prusa, Creality and Elegoo presets or a custom size; the build volume
    is drawn around the model with "fits", "fits turned 90°" or "too big" and "scale to fit".
  - Exports and projects keep the new orientation and size.
- **Updates**: the update dialog shows what changed in every version you missed. **Update**
  downloads the APK from GitHub, checks it against the release checksums and opens Android's
  installer. A known update is also offered in Settings.
- Roadmap: a "no bloat" section on how new features are added.

### Changed
- The daily update check is on by default (no first-launch question); it can be turned off in
  Settings. People who answered "No thanks" before keep it off.
- Saved toolbar layouts with the old Weight button get the Print button in its place.

### Fixed
- Coordinates that round to zero show as 0.00 instead of -0.00.

## [1.2.0] - 2026-10-13

Measuring rebuilt around features instead of loose points.

### Added
- **Smart measure** (the new default mode). A tap is matched to what's under your finger: a
  corner, a straight edge, a round edge or hole rim, a flat face, a cylinder (hole or shaft) or
  a sphere. This works on plain STL meshes, not just CAD files.
  - One feature: diameter, radius and depth of a hole or shaft; diameter and center of a round
    edge; area and direction of a face; length of an edge; position of a corner.
  - Two features: distance between parallel faces, angle between faces or edges, center distance
    and the wall between two holes, concentric check, axis-to-face distance and the material left
    to a hole, perpendicular distance to an edge, or point to point with ΔX/ΔY/ΔZ.
  - The picked features are outlined in the 3D view, with a dimension line and value.
- **Precision cursor**: hold and drag in the measure tool; a crosshair floats above your finger,
  previews what it will pick, and picks it when you let go.
- **Keep** a measurement on screen and start the next one; copy one value, one result or all of
  them as text.
- The distance, angle and 3-point circle modes snap to corners and edges.
- A roadmap: `docs/ROADMAP.md`.

### Changed
- The measure panel is more compact, so more of the model stays in view.

## [1.1.0] - 2026-10-13

A redesign of the whole interface, plus a lot more ways to make the app your own.

### Added
- Customizable toolbar: choose which tools sit in the bottom bar, which in the top cluster and
  which are hidden, and change their order (Settings → Interface → Customize toolbar). Model
  info, turntable and a quick snapshot button can be added too.
- Appearance: eight accent colors, a pure black mode for OLED screens, button size
  (compact/normal/large), panel opacity, button labels, a left-handed layout, and switches for
  the view cube, the model info bar and haptic feedback.
- 3D view: adjustable field of view, five more background presets (Midnight, Forest, Dusk,
  Paper, Studio grey) and a custom two-color gradient, quick standard views in the View panel,
  an opening view, a default model color and material for files without colors, and a
  selection highlight color.
- Controls: invert horizontal or vertical drag, choose what a double tap does (zoom in, fit view
  or nothing), turntable speed and direction.
- Measurements: choose 0 to 4 decimal places.
- Reopen the last model when the app starts; reset all settings.
- Recent files as a grid of large thumbnails or a list, with search and sorting (last opened,
  name, size).
- Transparent PNG export.

### Changed
- New look throughout: one floating dock at the bottom, a compact tool cluster at the top,
  single-card tool panels, a segmented control with a sliding highlight, and redesigned home,
  settings and about screens.
- The View panel shows display options as chips and keeps brightness and field of view as
  labelled sliders.
- Fit view and the projection switch now sit under the view cube.
- Landscape: the bottom bars are centered and the model is framed in the space above them.
- Support links go through https://doesthings.github.io/donate/, which forwards to Ko-fi
  (ko-fi.com/doesthings), so the destination can change without an app update.

### Fixed
- The first model opened after launch could be framed too large (cut off at the sides) because
  it was fitted before the 3D view knew its size. An untouched view now refits when the view
  size changes, including on rotation.
- On some screen widths the fit view button overlapped the tool buttons.
- Page titles were hard to read in the dark theme.
- Custom background colors are saved in projects.

## [1.0.0] - 2026-10-13

First public release.

### Added
- Native loaders for binary and ASCII STL, OBJ (with MTL colors), 3MF (multiple objects,
  colors), PLY (binary and ASCII, vertex colors), OFF and ZIP archives. STEP, IGES and BREP
  through Open CASCADE, in a separate process with a tessellation cache.
- OpenGL ES 3 renderer: physically based materials, studio/outdoor/flat lighting, soft shadows,
  feature edges, wireframe, x-ray, hatched section caps and an interactive section plane.
  Big models upload to the GPU without blocking touch input.
- Tools: section analysis, explode (automatic and manual with gizmo and undo), parts list with
  search/isolate/focus, colors and materials, measuring, dimensions overlay, wall-thickness and
  overhang analysis, mesh check, weight and cost estimate.
- Projects (`.meshwise`), export to STL, OBJ, 3MF and PNG, on-device library, recent files with
  thumbnails, five sample models.
- Optional update check. On first launch the app asks whether it may check GitHub for new
  releases (off unless the user agrees; switch and "Check now" in Settings). It asks the GitHub
  releases API at most once a day, sends nothing else, and offers the APK for the device's ABI
  with Download, Later or Skip this version. This is the only use of the `INTERNET` permission.
- Launcher icon: a faceted gem with an orange hatched section cut, plus a themed (monochrome)
  version, generated by `scripts/make_icon.py`.
- Ko-fi support links in Settings, About and on the home screen (they open in the browser).
