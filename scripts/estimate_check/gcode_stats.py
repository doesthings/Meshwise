"""Per-feature statistics of every slice's G-code (line length, filament, moves) -> gstats.json."""
import re, os, math, json, collections, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import RUNS, WORK
def stats(path):
    feat = "start"; x = y = z = e = 0.0; rel_e = False
    st = collections.defaultdict(lambda: {"len": 0.0, "e": 0.0, "moves": 0, "runs": 0})
    tr = {"len": 0.0, "moves": 0, "retracts": 0, "zhops": 0}
    last_dir = None; inrun = False
    for line in open(path, errors="ignore"):
        if line.startswith("; FEATURE:") or line.startswith(";TYPE:"):
            feat = line.split(":", 1)[1].strip(); last_dir = None; inrun = False; continue
        if line.startswith("M83"): rel_e = True
        if line.startswith("M82"): rel_e = False
        if not line.startswith(("G1 ", "G0 ", "G2 ", "G3 ")): continue
        code = line.split(";")[0]
        v = {k: float(n) for k, n in re.findall(r"([XYZEF])(-?[\d.]+)", code)}
        nx, ny, nz = v.get("X", x), v.get("Y", y), v.get("Z", z)
        de = v.get("E", 0.0) if rel_e else (v.get("E", e) - e)
        d = math.hypot(nx - x, ny - y)
        if "Z" in v and nz > z + 0.1 and d == 0: tr["zhops"] += 1
        if de < 0: tr["retracts"] += 1
        if d > 0:
            if de > 0:
                s = st[feat]; s["len"] += d; s["e"] += de; s["moves"] += 1
                dv = ((nx - x) / d, (ny - y) / d)
                if not inrun or (last_dir and dv[0] * last_dir[0] + dv[1] * last_dir[1] < math.cos(math.radians(30))): s["runs"] += 1
                inrun = True; last_dir = dv
            else:
                inrun = False; last_dir = None
                tr["len"] += d; tr["moves"] += 1
        x, y, z = nx, ny, nz
        if not rel_e and "E" in v: e = v["E"]
    return {"features": dict(st), "travel": tr}
out = {}
for d in sorted(os.listdir(RUNS)):
    g = os.path.join(RUNS, d, "plate_1.gcode")
    if os.path.exists(g): out[d] = stats(g)
json.dump(out, open(os.path.join(WORK, "gstats.json"), "w"), indent=1)
print(len(out), "runs")
