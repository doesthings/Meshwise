# Changelog

All notable changes to Meshwise are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[semantic versioning](https://semver.org/).

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
