#!/usr/bin/env bash
# Regenerates the README screenshots:
#  1. builds the desktop renderer + JVM JNI library (needs cmake, ninja, Mesa EGL/GLES),
#  2. renders the 3D frames with the app's own C++ renderer,
#  3. runs the Robolectric screenshot tests, which draw the real Compose UI on top,
#  4. composes the README images.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/native/build-host"
cmake -S "$ROOT/native" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
ninja -C "$BUILD" >/dev/null

R="$BUILD/fsv_render"
run_tests() {
  (cd "$ROOT" && ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pfsv.hostLibDir="$BUILD" --rerun --console=plain -q)
}
# Insets measured by the UI for a scenario (written by the screenshot tests).
insets() { local f="$OUT/$1.insets"; if [ -f "$f" ]; then echo "--insets $(cat "$f")"; fi; }
A="$ROOT/app/src/main/assets/samples"
ASM="$ROOT/app/src/test/resources/bearing_assembly.fsm"
OUT="$ROOT/build/renders"
mkdir -p "$OUT"
C=(--size 1080x2400 --linescale 1.5)

# Pass 1 records the UI insets of every scenario; pass 2 renders and captures for real.
[ -f "$OUT/02_viewer.insets" ] || run_tests || true

"$R" --in "$ASM" --out "$OUT/02_viewer.png" "${C[@]}" $(insets 02_viewer) --edges
"$R" --in "$ASM" --out "$OUT/03_section.png" "${C[@]}" $(insets 03_section) --edges --section y:0.5:flip
"$R" --in "$A/spur_gear.stl" --out "$OUT/04_measure.png" "${C[@]}" $(insets 04_measure) --bg 2
"$R" --in "$A/twisted_vase.stl" --out "$OUT/05_thickness.png" "${C[@]}" $(insets 05_thickness) --thickness
"$R" --in "$ASM" --out "$OUT/06_explode.png" "${C[@]}" $(insets 06_explode) --edges --explode 0.9
"$R" --in "$ASM" --out "$OUT/07_parts.png" "${C[@]}" $(insets 07_parts) --edges --select 4
"$R" --in "$A/trefoil_knot.stl" --out "$OUT/08_material.png" "${C[@]}" $(insets 08_material) --material all:gold
"$R" --in "$A/keychain.3mf" --out "$OUT/09_dimensions.png" "${C[@]}" $(insets 09_dimensions)
"$R" --in "$A/keychain.3mf" --out "$OUT/10_color.png" "${C[@]}" $(insets 10_color)
"$R" --in "$A/spur_gear.stl" --out "$OUT/11_weight.png" "${C[@]}" $(insets 11_weight)

T=(--size 320x320 --linescale 1)
"$R" --in "$ASM" --out "$OUT/thumb_bearing_assembly.png" "${T[@]}" --edges
for m in keychain.3mf trefoil_knot.stl twisted_vase.stl spur_gear.stl; do
  "$R" --in "$A/$m" --out "$OUT/thumb_${m%.*}.png" "${T[@]}"
done

run_tests
"${PYTHON:-python3}" "$ROOT/scripts/compose_readme_images.py"
