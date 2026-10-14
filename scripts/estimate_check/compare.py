"""Runs fsv_estimate with the settings Bambu Studio resolved for each slice, and compares.

    python3 compare.py [TAG] [-v]

TAG limits the check to one variant; -v adds time and line length per feature (line lengths
need gcode_stats.py to have run). The last lines sum up, with and without supports.
"""
import json, os, statistics, subprocess, sys, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ESTIMATE as EST, MODELS, RESULTS, RUNS, WORK
def num(v, default=0.0):
    try:
        v = str(v).rstrip('%'); return float(v)
    except Exception: return default
def settings(cfg):
    lw = num(cfg.get('line_width', 0.4)) or 0.4
    w = lambda k: num(cfg.get(k, 0)) or lw
    acc = num(cfg['default_acceleration'])
    maxx = num(cfg.get('machine_max_acceleration_extruding', 1e9)) or 1e9
    maxt = num(cfg.get('machine_max_acceleration_travel', 1e9)) or 1e9
    def a(k):
        v = cfg.get(k, '0'); s = str(v)
        val = acc * num(s) / 100 if s.endswith('%') else (num(s) or acc)
        return min(val, maxx)
    jerk = num(cfg.get('machine_max_jerk_x', 9))
    fil = cfg.get('filament_max_volumetric_speed', '21')
    return {
        'layerHeight': num(cfg['layer_height']), 'firstLayerHeight': num(cfg['initial_layer_print_height']),
        'outerWidth': w('outer_wall_line_width'), 'innerWidth': w('inner_wall_line_width'), 'infillWidth': w('sparse_infill_line_width'),
        'solidWidth': w('internal_solid_infill_line_width'), 'topWidth': w('top_surface_line_width'), 'firstLayerWidth': w('initial_layer_line_width'),
        'supportWidth': w('support_line_width'), 'walls': int(num(cfg['wall_loops'])), 'topLayers': int(num(cfg['top_shell_layers'])),
        'bottomLayers': int(num(cfg['bottom_shell_layers'])), 'topThickness': num(cfg['top_shell_thickness']), 'bottomThickness': num(cfg['bottom_shell_thickness']),
        'infillDensity': num(cfg['sparse_infill_density']) / 100,
        'outerSpeed': num(cfg['outer_wall_speed']), 'innerSpeed': num(cfg['inner_wall_speed']), 'infillSpeed': num(cfg['sparse_infill_speed']),
        'solidSpeed': num(cfg['internal_solid_infill_speed']), 'topSpeed': num(cfg['top_surface_speed']), 'firstLayerSpeed': num(cfg['initial_layer_speed']),
        'firstLayerInfillSpeed': num(cfg['initial_layer_infill_speed']), 'travelSpeed': num(cfg['travel_speed']), 'bridgeSpeed': num(cfg['bridge_speed']),
        'supportSpeed': num(cfg['support_speed']), 'overhang2': num(cfg.get('overhang_2_4_speed', 0)), 'overhang3': num(cfg.get('overhang_3_4_speed', 0)),
        'overhang4': num(cfg.get('overhang_4_4_speed', 0)),
        'outerAccel': a('outer_wall_acceleration'), 'innerAccel': a('inner_wall_acceleration'), 'infillAccel': a('sparse_infill_acceleration'),
        'solidAccel': a('internal_solid_infill_acceleration'), 'topAccel': a('top_surface_acceleration'),
        'travelAccel': min(a('travel_acceleration'), maxt), 'firstLayerAccel': a('initial_layer_acceleration'),
        'jerk': jerk, 'maxVolumetricSpeed': num(fil),
        'minLayerTime': num(cfg['slow_down_layer_time']), 'minSpeed': num(cfg['slow_down_min_speed']),
        'retractLength': num(cfg['retraction_length']), 'retractSpeed': num(cfg['retraction_speed']), 'zHop': 0 if cfg.get('printer_model','').startswith('Creality') or 'Creality' in str(cfg.get('printer_settings_id','')) else num(cfg['z_hop']),
        'zSpeed': num(cfg.get('machine_max_speed_z', 12)) or 12,
        'supports': 1 if str(cfg.get('enable_support', '0')) in ('1', 'true') else 0, 'supportAngle': num(cfg['support_threshold_angle']) or 30,
        'supportSpacing': num(cfg['support_base_pattern_spacing']), 'supportInterfaceSpacing': num(cfg['support_interface_spacing']),
        'supportInterfaceLayers': int(num(cfg['support_interface_top_layers'])),
        'infillPattern': {'grid': 1, 'cubic': 2, 'triangles': 2, 'adaptivecubic': 2, 'supportcubic': 2, 'tri-hexagon': 2, 'stars': 2, 'gyroid': 3}.get(cfg.get('sparse_infill_pattern'), 0),
        'infillOverlap': num(cfg.get('infill_wall_overlap', '15%')) / 100,
        'infillAnchor': (lambda a: num(a) / 100 * w('sparse_infill_line_width') if str(a).endswith('%') else num(a))(cfg.get('sparse_infill_anchor', '400%')),
        'minSparseArea': num(cfg.get('minimum_sparse_infill_area', 15)),
        'retractMinTravel': num(cfg.get('retraction_minimum_travel', 1)),
        'skirtLoops': int(num(cfg.get('skirt_loops', 0))), 'skirtDistance': num(cfg.get('skirt_distance', 2)),
        'flowRatio': num(cfg.get('filament_flow_ratio', 1)) or 1,
        'topOneWall': 0 if cfg.get('top_one_wall_type', 'not apply') == 'not apply' else 1,
        'treeSupports': 1 if str(cfg.get('support_type', '')).startswith('tree') else 0,
        'supportInterfaceSpeed': num(cfg.get('support_interface_speed', 80)),
        'smallPerimeterRadius': num(cfg.get('small_perimeter_threshold', 0)),
        'smallPerimeterFactor': (lambda a: num(a) / 100 if str(a).endswith('%') else num(a) / max(1, num(cfg['outer_wall_speed'])))(cfg.get('small_perimeter_speed', '50%')),
    }
rows = [json.loads(l) for l in open(RESULTS) if 'error' not in l]
tagfilter = next((a for a in sys.argv[1:] if not a.startswith('-')), None)
feats = ['Outer wall', 'Inner wall', 'Sparse infill', 'Internal solid infill', 'Top surface', 'Bottom surface', 'Bridge', 'Support', 'Skirt', 'Travel']
MERGE = {'Overhang wall': 'Outer wall', 'Floating vertical shell': 'Internal solid infill', 'Gap infill': 'Internal solid infill', 'Brim': 'Skirt',
         'Support interface': 'Support', 'Support transition': 'Support'}
G = json.load(open(os.path.join(WORK, 'gstats.json'))) if os.path.exists(os.path.join(WORK, 'gstats.json')) else {}
errs = []  # (time error, filament error, has supports)
for r in rows:
    if tagfilter and r['tag'] != tagfilter: continue
    if r['model'] == 'keychain.3mf': continue  # Bambu's --arrange splits its four parts apart
    d = os.path.join(RUNS, f"{r['printer']}-{r['tag']}-{os.path.splitext(r['model'])[0]}")
    cfg = json.loads(zipfile.ZipFile(os.path.join(d, 'out.3mf')).read('Metadata/project_settings.config'))
    cfg = {k: (v[0] if isinstance(v, list) and v else v) for k, v in cfg.items()}
    st = settings(cfg)
    args = [EST, os.path.join(MODELS, r['model'])] + [f'{k}={v}' for k, v in st.items()]
    e = json.loads(subprocess.run(args, capture_output=True, text=True).stdout)
    bf = {}
    for k, v in r['features'].items():
        if k in ('Undefined', 'Custom'): continue
        bf[MERGE.get(k, k)] = bf.get(MERGE.get(k, k), 0) + v
    et, rt = e['seconds'], sum(bf.values())
    ef, rf = e['filament_mm'], r['filament_mm']
    errs.append((et / rt - 1, ef / rf - 1, st['supports'] == 1))
    print(f"{r['printer']:9} {r['tag']:8} {r['model']:17} time {rt/60:6.1f} → {et/60:6.1f} min ({100*(et/rt-1):+5.0f}%)  fil {rf:7.0f} → {ef:7.0f} mm ({100*(ef/rf-1):+5.0f}%)  {e['compute_ms']:.0f} ms")
    if '-v' in sys.argv:
        gs = G.get(os.path.basename(d), {'features': {}, 'travel': {}})
        gl = {}
        for k, v in gs['features'].items(): gl[MERGE.get(k, k)] = gl.get(MERGE.get(k, k), 0) + v['len']
        gl['Travel'] = gs['travel'].get('len', 0)
        for f in feats:
            b = bf.get(f, 0.0); o = e['features'].get(f, 0.0)
            if b > 1 or o > 1: print(f"      {f:22} {b:7.0f} s → {o:7.0f} s   len {gl.get(f, 0):8.0f} → {e['lengths'].get(f, 0):8.0f} mm")
        print(f"      travel moves {gs['travel'].get('moves')} → {e['travels']}, retract moves {gs['travel'].get('retracts')} → {e['retracts']}")
def summary(label, rows):
    if not rows: return
    t = [abs(x[0]) for x in rows]; f = [abs(x[1]) for x in rows]
    print(f"{label:16} {len(rows):3d} slices   time: mean {100*statistics.mean(t):4.1f}%, max {100*max(t):4.1f}%, "
          f"bias {100*statistics.mean(x[0] for x in rows):+.1f}%   filament: mean {100*statistics.mean(f):4.1f}%, max {100*max(f):4.1f}%")
print()
summary("no supports", [x for x in errs if not x[2]])
summary("with supports", [x for x in errs if x[2]])
summary("all", errs)
