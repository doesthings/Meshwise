"""Replays a slice's G-code with the estimator's motion model (classic jerk, trapezoids) and
compares the time per feature with Bambu Studio's. This checks the motion model on its own,
apart from slicing.

    python3 replay.py x1c-base-spur_gear 9     (run folder, the printer's jerk)
"""
import re, os, sys, math, json, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import RESULTS, RUNS
def safe(u, v, J):
    m = max(abs(u[0]), abs(u[1]))
    return J / m if m * v > J else v
def junction(u, v0, w, v1, J):
    prevLarger = v0 > v1
    smaller = v1 / v0 if prevLarger else v0 / v1
    vmax = v1 if prevLarger else v0
    f = 1.0; lim = False
    for a in range(2):
        ve = u[a] * v0; vn = w[a] * v1
        if prevLarger: ve *= smaller
        if lim: ve *= f; vn *= f
        j = ((ve - vn) if (vn > 0 or ve < 0) else max(ve, -vn)) if ve > vn else ((vn - ve) if (vn < 0 or ve > 0) else max(-ve, vn))
        if j > J: f *= J / j; lim = True
    if lim: vmax *= f
    s0, s1 = safe(u, v0, J), safe(w, v1, J)
    if s0 > vmax * 0.99 and s1 > vmax * 0.99: vmax = s1
    return vmax
def mt(L, v, a, v0, v1):
    v0 = min(v0, v); v1 = min(v1, v)
    p2 = a * L + 0.5 * (v0 * v0 + v1 * v1)
    if p2 >= v * v:
        return (v - v0) / a + (v - v1) / a + (L - (v*v - v0*v0) / (2*a) - (v*v - v1*v1) / (2*a)) / v
    vp = math.sqrt(p2)
    if vp < max(v0, v1): return L / max(0.5 * (v0 + v1), 1e-3)
    return (vp - v0) / a + (vp - v1) / a
def replay(path, J, travelAccelFromS=True):
    x = y = z = 0.0; F = 1000; acc = 1000; feat = 'start'
    moves = []  # (len, v, a, dir, feat, kind)
    for line in open(path, errors='ignore'):
        if line.startswith('; FEATURE:'): feat = line.split(':', 1)[1].strip(); continue
        if line.startswith('M204'):
            m = re.search(r'S(\d+)', line)
            if m: acc = float(m.group(1))
            continue
        if not line.startswith(('G1', 'G0')): continue
        v = {k: float(n) for k, n in re.findall(r'([XYZEF])(-?[\d.]+)', line.split(';')[0])}
        if 'F' in v: F = v['F'] / 60
        nx, ny = v.get('X', x), v.get('Y', y)
        d = math.hypot(nx - x, ny - y)
        if d > 1e-9:
            e = v.get('E', 0)
            moves.append((d, F, acc, ((nx - x) / d, (ny - y) / d), feat if e > 0 else 'Travel'))
        elif 'E' in v:
            moves.append((abs(v['E']), F, 5000, None, 'Retract'))  # E-only
        elif 'Z' in v:
            moves.append((abs(v['Z'] - z), min(F, 20), 500, None, 'Travel'))
        x, y = nx, ny; z = v.get('Z', z)
    n = len(moves)
    vj = [0.0] * (n + 1)
    for i in range(1, n):
        a, b = moves[i - 1], moves[i]
        if a[3] is None or b[3] is None: vj[i] = 0; continue
        vj[i] = min(junction(a[3], a[1], b[3], b[1], J), a[1], b[1])
    for i in range(n - 1, -1, -1): vj[i] = min(vj[i], math.sqrt(vj[i+1]**2 + 2 * moves[i][2] * moves[i][0]))
    for i in range(1, n + 1): vj[i] = min(vj[i], math.sqrt(vj[i-1]**2 + 2 * moves[i-1][2] * moves[i-1][0]))
    t = collections.Counter()
    for i, (L, v, a, u, f) in enumerate(moves):
        t[f] += mt(L, v, a, vj[i], vj[i+1]) if u is not None else L / v
    return t
if __name__ == '__main__':
    run = sys.argv[1]; J = float(sys.argv[2])
    t = replay(os.path.join(RUNS, run, 'plate_1.gcode'), J)
    ref = None
    p, tag, model = run.split('-', 2)
    for l in open(RESULTS):
        r = json.loads(l)
        if r.get('printer') == p and r.get('tag') == tag and r.get('model', '').startswith(model + '.'): ref = r['features']
    for f in sorted(t, key=lambda f: -t[f]):
        print(f'{f:24} replay {t[f]:8.0f} s   bambu {ref.get(f, 0) if ref else 0:8.0f} s')
