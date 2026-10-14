"""Slices models with Bambu Studio's command line and records its time and filament estimates.

    python3 slice.py MODELS PRINTERS VARIANTS [WORKERS]
    python3 slice.py cube20.stl,spur_gear.stl x1c,mk3s '{"base": {}, "lh12": {"layer_height": "0.12"}}'

VARIANTS maps a tag to process settings that override the profile. Results are appended to
results.jsonl in the work folder; each slice keeps its G-code and project in runs/.
"""
import json, os, re, subprocess, sys, shutil
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import MODELS, PROFILES as P, RESULTS, RUNS, WORK
PRINTERS = {
    "x1c": ("BBL/machine/Bambu Lab X1 Carbon 0.4 nozzle.json", "BBL/process/0.20mm Standard @BBL X1C.json", "BBL/filament/Bambu PLA Basic @BBL X1C.json"),
    "k1": ("Creality/machine/Creality K1 0.4 nozzle.json", "Creality/process/0.20mm Standard @Creality K1 0.4 nozzle.json", "Creality/filament/Generic PLA @Creality.json"),
    "mk3s": ("Prusa/machine/Prusa MK3S 0.4 nozzle.json", "Prusa/process/0.20mm Standard @MK3S.json", "Prusa/filament/Generic PLA @Prusa.json"),
    "ender3v2": ("Creality/machine/Creality Ender-3 V2 0.4 nozzle.json", "Creality/process/0.20mm Standard @Creality Ender3V2.json", "Creality/filament/Generic PLA @Creality.json"),
}
FILAMENT_OVERRIDE = {"filament_max_volumetric_speed": ["12"]}  # generic profiles say 2 mm³/s

def job(printer, model, overrides, tag):
    m, p, f = PRINTERS[printer]
    d = os.path.join(RUNS, f"{printer}-{tag}-{os.path.splitext(model)[0]}")
    shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
    proc = json.load(open(os.path.join(P, p)))
    proc.update(overrides)
    pj = os.path.join(d, "process.json"); json.dump(proc, open(pj, "w"), indent=1)
    fil = json.load(open(os.path.join(P, f)))
    if printer != "x1c": fil.update(FILAMENT_OVERRIDE)
    fj = os.path.join(d, "filament.json"); json.dump(fil, open(fj, "w"), indent=1)
    cmd = ["flatpak", "run", f"--filesystem={WORK}", "--filesystem=/var/lib/flatpak:ro", "--command=bambu-studio", "com.bambulab.BambuStudio",
           "--datadir", os.path.join(WORK, "data"), "--load-settings", f"{os.path.join(P, m)};{pj}",
           "--load-filaments", fj, "--ensure-on-bed", "--arrange", "1", "--slice", "0", "--debug", "1", "--outputdir", d,
           "--export-3mf", "out.3mf", os.path.join(MODELS, model)]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    g = os.path.join(d, "plate_1.gcode")
    if not os.path.exists(g):
        return {"printer": printer, "model": model, "tag": tag, "error": (r.stdout + r.stderr)[-400:]}
    head = open(g, errors="ignore").read(200000)
    def num(pat):
        mm = re.search(pat, head); return float(mm.group(1)) if mm else None
    mt = re.search(r"model printing time: ([^;]+);", head) or re.search(r"estimated printing time \(normal mode\) = ([^\n]+)", head)
    t = mt.group(1).strip()
    secs = sum(int(v) * {"d": 86400, "h": 3600, "m": 60, "s": 1}[u] for v, u in re.findall(r"(\d+)([dhms])", t))
    res = json.load(open(os.path.join(d, "result.json")))
    plate = res["sliced_plates"][0]
    return {"printer": printer, "model": model, "tag": tag, "seconds": secs,
            "filament_mm": num(r"total filament length \[mm\] : ([\d.]+)"),
            "filament_mm3": num(r"total filament volume \[cm\^3\] : ([\d.]+)"),
            "layers": num(r"total layer number: (\d+)"),
            "features": plate.get("feature_type_times", {}), "overrides": overrides}
models = sys.argv[1].split(",")
printers = sys.argv[2].split(",")
variants = json.loads(sys.argv[3])  # {"tag": {overrides}}
jobs = [(p, m, ov, tag) for p in printers for m in models for tag, ov in variants.items()]
with ThreadPoolExecutor(max_workers=int(sys.argv[4]) if len(sys.argv) > 4 else 6) as ex:
    results = list(ex.map(lambda a: job(*a), jobs))
with open(RESULTS, "a") as fh:
    for r in results: fh.write(json.dumps(r) + "\n")
for r in results:
    if "error" in r: print("ERR", r["printer"], r["model"], r["tag"], r["error"][-200:])
    else: print(f'{r["printer"]:9} {r["tag"]:10} {r["model"]:18} {r["seconds"]/60:7.1f} min  {r["filament_mm"]:9.0f} mm  layers {r["layers"]:.0f}')
