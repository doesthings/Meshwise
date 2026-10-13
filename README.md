<p align="center">
  <img src="docs/images/hero.png" alt="Meshwise: a fast, native 3D model viewer for Android" width="100%">
</p>

<p align="center">
  <a href="../../releases/latest"><img alt="Download APK" src="https://img.shields.io/badge/download-APK-7c5cff?style=for-the-badge&logo=android&logoColor=white"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="OpenGL ES 3" src="https://img.shields.io/badge/OpenGL%20ES-3.0-5586a4?style=for-the-badge&logo=opengl&logoColor=white">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue?style=for-the-badge"></a>
  <a href="https://ko-fi.com/doesthings"><img alt="Support on Ko-fi" src="https://img.shields.io/badge/Ko--fi-Support%20this%20project-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white"></a>
</p>

**Meshwise** opens 3D printing and CAD files on your phone in a fraction of a second and
gives you the tools you actually need to inspect them: section cuts, measurements, wall
thickness, exploded assemblies, weight estimates and more. The heavy lifting is done by a
multi-threaded C++ core and an OpenGL ES 3 renderer; STEP and IGES are read by an embedded
Open CASCADE kernel. No ads, no account, and no network permission: your files never leave the device.

> **Honest note:** Meshwise is vibe coded. It was built with a lot of help from an AI coding
> assistant. It works well enough, it's backed by automated tests, and I just wanted to share a
> good free tool with whoever needs one. If something breaks, please
> [open an issue](../../issues/new/choose).

## Download

Grab the latest APK from the [**Releases**](../../releases/latest) page and open it on your
phone (you may need to allow installs from your browser or file manager).

| APK | For |
|---|---|
| `Meshwise-x.y.z-arm64-v8a.apk` | Nearly every phone and tablet from the last 8 years. **Pick this if unsure.** |
| `Meshwise-x.y.z-armeabi-v7a.apk` | Older 32-bit ARM devices |
| `Meshwise-x.y.z-x86_64.apk` | Chromebooks and emulators |
| `Meshwise-x.y.z-universal.apk` | Any device (largest download) |

Requires Android 8.0 (API 26) or newer with OpenGL ES 3.0, which covers essentially all devices
in use today.

Every release APK is signed with the same Meshwise certificate, so updates install over each
other. To check a download, run `apksigner verify --print-certs` on it; the SHA-256 must be
`CB:EF:EB:0E:76:73:75:BE:72:D2:93:25:F9:7E:C6:02:B4:B5:D9:B8:E4:EE:CB:9A:EF:39:58:81:8B:BC:5C:38`.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/01_home.png" width="260"><br><sub><b>Home</b>: recent files with thumbnails, library, samples</sub></td>
    <td align="center"><img src="docs/screenshots/02_viewer.png" width="260"><br><sub><b>Viewer</b>: STEP assembly with real part colors</sub></td>
    <td align="center"><img src="docs/screenshots/03_section.png" width="260"><br><sub><b>Section analysis</b>: hatched caps, live plane</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/04_measure.png" width="260"><br><sub><b>Measure</b>: circle through 3 points, R / D / center</sub></td>
    <td align="center"><img src="docs/screenshots/05_thickness.png" width="260"><br><sub><b>Wall thickness</b>: heat map, thinnest-wall callouts</sub></td>
    <td align="center"><img src="docs/screenshots/06_explode.png" width="260"><br><sub><b>Explode</b>: automatic or manual with gizmo</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/07_parts.png" width="260"><br><sub><b>Parts</b>: search, isolate, x-ray, focus</sub></td>
    <td align="center"><img src="docs/screenshots/08_material.png" width="260"><br><sub><b>Materials</b>: plastics, metals, resin, ceramic</sub></td>
    <td align="center"><img src="docs/screenshots/09_dimensions.png" width="260"><br><sub><b>Dimensions</b>: bounding box overlay</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/10_color.png" width="260"><br><sub><b>Color</b>: paint parts, custom colors</sub></td>
    <td align="center"><img src="docs/screenshots/11_weight.png" width="260"><br><sub><b>Weight</b>: filament, infill, walls, cost</sub></td>
    <td align="center"><img src="docs/screenshots/12_settings.png" width="260"><br><sub><b>Settings</b>: theme, units, shading, CAD quality</sub></td>
  </tr>
</table>

## Features

**Open almost anything**
- STL (binary and ASCII), OBJ with MTL colors, 3MF (multiple objects, colors), PLY (binary and ASCII, vertex colors), OFF
- STEP, IGES and BREP CAD files with assembly structure, part names and colors (Open CASCADE)
- ZIP archives: every model inside is loaded and laid out side by side
- Opens from any app: Files, Google Drive, Gmail, Telegram, WhatsApp, browsers ("Open with" and "Share")
- Recent files with rendered thumbnails, an on-device library, and five sample models to try

**A fast, good-looking viewer**
- Smooth orbit, pan and pinch-zoom with inertia; zoom toward your fingers; double-tap to focus a point
- View cube, standard views, perspective or orthographic projection, turntable mode
- Physically based materials (matte, satin, glossy, resin, rubber, ceramic, steel, aluminium, chrome, gold, silver, copper), studio, outdoor and flat lighting, soft shadows on a grid floor
- Crease-aware smooth shading, feature edges, wireframe and axis overlays, light and dark themes, Material You colors

**Inspect and analyze**
- **Section analysis**: cut along X, Y or Z, flip the side, type an exact cut value, solid hatched caps
- **Measure**: point-to-point distance (with ΔX/ΔY/ΔZ), circle through three points (radius, diameter, center), angle, point coordinates and normals; copy results to the clipboard
- **Dimensions**: tap the info bar for an X/Y/Z bounding-box overlay
- **Wall thickness**: gradient or threshold heat map and callouts at the thinnest walls
- **Overhang** analysis with an adjustable angle, and a **mesh check** for open and non-manifold edges, flipped normals, degenerate faces and shell count
- **Weight estimate**: PLA, PETG, ABS, ASA, TPU, nylon, resin and more, with infill, wall count, filament length and cost

**Work with assemblies**
- Parts list with search, show/hide, isolate, x-ray the rest, and focus
- Explode slider, plus a manual mode with a 3-axis drag gizmo, per-part hide and undo
- Paint parts individually or all at once, and assign materials per part

**Save and share**
- Save projects (camera, colors, materials, explode layout, section) and reopen them later
- Export STL, OBJ (with part names), 3MF (with names and colors) or high-resolution PNG snapshots
- Share any export straight to another app

## Performance

The C++ core memory-maps the file, parses it without copies, welds vertices with a hash of
exact positions, and builds normals, topology and the BVH on all cores. Measured with the
bundled `fsv_bench` tool on a 4-core x86_64 cloud VM (a recent phone is roughly 2–3× slower):

| Model | Size | Triangles | Load + mesh processing | Topology | BVH (picking) | Wall thickness |
|---|---:|---:|---:|---:|---:|---:|
| Twisted vase (sample) | 3.1 MB | 66 k | 13 ms | 5 ms | 12 ms | 30 ms |
| Trefoil knot, dense | 97.7 MB | 2.05 M | 0.41 s | 0.17 s | 0.57 s | 1.3 s |

Only loading blocks the first frame. Topology, BVH and analysis run in the background while
you are already orbiting the model. The renderer packs each vertex into 24 bytes
(position, 10-bit normal, color, part ID) and only redraws when something changes, so the
GPU and battery sit idle when you are not touching the screen.

## How it works

```
┌──────────────────────── Kotlin / Jetpack Compose ────────────────────────┐
│  HomeScreen · ViewerScreen · panels · overlays (labels, gizmo, cube)     │
│  ViewerViewModel ── Engine (JNI facade) ── GLSurfaceView (on demand)     │
└───────────────┬────────────────────────────────────┬─────────────────────┘
                │ JNI                                │ Messenger IPC
┌───────────────▼──────────────────┐  ┌──────────────▼─────────────────────┐
│ libmeshwise.so (C++17)           │  │ :cad process · libmeshwise_cad.so  │
│ loaders: STL OBJ 3MF PLY OFF ZIP │  │ Open CASCADE 7.8.1: STEP/IGES/BREP │
│ weld · normals · topology · BVH  │  │ XCAF names/colors · BRepMesh       │
│ analysis · exporters · camera    │  │ → FSM mesh, cached per file        │
│ GLES 3 renderer (PBR, shadows,   │  └────────────────────────────────────┘
│ stencil section caps, x-ray)     │
└──────────────────────────────────┘
```

- **`native/core`**: loaders, mesh processing, BVH, analysis, exporters, camera and the
  OpenGL ES 3 renderer. Platform-independent; the same code runs in the headless desktop
  renderer and the unit tests.
- **`native/jni`**: the JNI bridge (`libmeshwise.so`).
- **`native/cad`**: Open CASCADE import (`libmeshwise_cad.so`). It runs in its own process, so a
  malformed CAD file can never take the viewer down, and its results are cached as compact
  FSM meshes so a STEP file opens instantly the second time.
- **`app/`**: the Kotlin app (Compose Material 3 UI, view model, file handling, projects).

## Building

Requirements: JDK 17+, Android SDK 36, NDK `28.2.13676358`, CMake 3.31.6 (Android Studio can
install them), plus `git` and `ninja` for the CAD kernel.

```bash
git clone https://github.com/doesthings/Meshwise.git
cd Meshwise

# Optional but recommended: build Open CASCADE for STEP/IGES/BREP (once, ~15-30 min per ABI).
# Without it the app still builds; CAD formats are then reported as unavailable.
for abi in arm64-v8a armeabi-v7a x86_64; do scripts/build-occt-android.sh $abi; done

./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # signed with keystore.properties if present, see docs/RELEASING.md
```

## Testing

```bash
# Native core: unit tests, benchmark and the headless renderer (Mesa EGL on Linux)
cmake -S native -B native/build-host -G Ninja -DCMAKE_BUILD_TYPE=Release
ninja -C native/build-host
native/build-host/fsv_tests
native/build-host/fsv_bench model.stl
native/build-host/fsv_render --in model.stl --out shot.png --size 1200x1200 --section z:0.5

# App: Robolectric tests that drive the real native engine through the view model
./gradlew :app:testDebugUnitTest -Pfsv.hostLibDir=$PWD/native/build-host

# Regenerate the screenshots in this README (Compose UI + offline 3D renders)
scripts/make_screenshots.sh
```

CI runs the native tests, a render smoke test, the JVM tests and a full APK build on every push.
Tagging `vX.Y.Z` publishes a release (see [docs/RELEASING.md](docs/RELEASING.md)).

## Support

Meshwise is free, open source and ad-free, and it will stay that way. If it saves you time,
please consider starring the repo or buying me a coffee. It keeps development going.

<p align="center">
  <a href="https://github.com/doesthings/Meshwise"><img alt="Star on GitHub" src="https://img.shields.io/badge/Star%20on%20GitHub-%E2%AD%90-yellow?style=for-the-badge&logo=github"></a>
  <a href="https://ko-fi.com/doesthings"><img alt="Buy me a coffee on Ko-fi" src="https://img.shields.io/badge/Ko--fi-Buy%20me%20a%20coffee-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white"></a>
</p>

Bug reports and model files that don't open correctly are just as valuable: please
[open an issue](../../issues/new/choose).

## Privacy

Meshwise has **no internet permission**. It reads only the files you open, keeps recents,
thumbnails, projects and the library in its private storage, and collects nothing.

## License

Meshwise is released under the [MIT License](LICENSE). The app bundles Open CASCADE
Technology (LGPL 2.1 with the OCCT exception) for CAD import; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details and how to relink it. Sample
models are CC0.
