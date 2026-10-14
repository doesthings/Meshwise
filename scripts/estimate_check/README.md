# Checking the print estimate against a slicer

These scripts slice test models with Bambu Studio's command line, then run Meshwise's
estimator (`fsv_estimate`, the desktop build of `native/core/print_estimate.cpp`) with the
settings Bambu Studio resolved for each slice, and compare print time and filament. The
README's accuracy table comes from them.

## What you need

- Bambu Studio from Flathub (`flatpak install flathub com.bambulab.BambuStudio`). Set
  `BAMBU_PROFILES` if its profiles live somewhere else.
- The desktop build of the native code:
  `cmake -S native -B native/build-host -G Ninja -DCMAKE_BUILD_TYPE=Release && ninja -C native/build-host`
- Python 3.

Everything is written to `build/estimate-check` (or `FSV_CHECK_DIR`).

## Run it

```bash
cd scripts/estimate_check
python3 shapes.py
M=cube20.stl,plate.stl,tower.stl,tube.stl,cylinder.stl,spur_gear.stl,trefoil_knot.stl,twisted_vase.stl
python3 slice.py $M x1c,k1,mk3s,ender3v2 '{"base": {}}'
python3 slice.py cube20.stl,cylinder.stl,spur_gear.stl,trefoil_knot.stl,tube.stl x1c,mk3s '{
  "lh12": {"layer_height": "0.12"}, "lh28": {"layer_height": "0.28"},
  "inf40": {"sparse_infill_density": "40%", "sparse_infill_pattern": "grid"},
  "gyr": {"sparse_infill_density": "15%", "sparse_infill_pattern": "gyroid"},
  "w3": {"wall_loops": "3", "top_shell_layers": "5"}}'
python3 slice.py mushroom.stl,bridge.stl,trefoil_knot.stl x1c,mk3s '{
  "sup": {"enable_support": "1", "support_type": "normal(auto)"},
  "tree": {"enable_support": "1", "support_type": "tree(auto)"}}'
python3 gcode_stats.py
python3 compare.py          # or: python3 compare.py gyr -v
```

`replay.py` runs one slice's own G-code through the estimator's motion model, to check the
timing apart from the slicing.

## Notes

- The command line fills settings a profile leaves out with Bambu Studio's built-in defaults,
  so these slices are not exactly what the app would show for the same printer. That doesn't
  matter here: `compare.py` reads the settings each slice actually used.
- Bambu Studio's Creality profiles don't lift the nozzle on travel, so the comparison turns the
  z hop off for them.
- `--arrange` lays the parts of a multi-object 3MF apart on the plate, so such files are not
  comparable to the assembled model.
