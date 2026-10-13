# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[semantic versioning](https://semver.org/).

## [1.0.0] - 2026-10-13

### Added
- Native loaders for binary/ASCII STL, OBJ (+MTL colors), 3MF (multi-object, colors), PLY
  (binary/ASCII, vertex colors), OFF and ZIP archives; STEP, IGES and BREP through Open CASCADE
  in an isolated process with a tessellation cache.
- OpenGL ES 3 renderer: physically based materials, studio/outdoor/flat lighting, soft shadows,
  feature edges, wireframe, x-ray, hatched section caps and an interactive section plane.
- Tools: section analysis, explode (automatic and manual with gizmo and undo), parts list with
  search/isolate/focus, colors and materials, measuring, dimensions overlay, wall-thickness and
  overhang analysis, mesh check, weight and cost estimate.
- Projects (`.meshwise`), export to STL, OBJ, 3MF and PNG, on-device library, recent files with
  thumbnails, five sample models.
- Ko-fi support links in Settings, About and on the home screen (they open in the browser; the
  app itself has no network access).
