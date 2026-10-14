"""Writes the test shapes as binary STL files, and copies three of the app's samples next to them."""
import math, os, shutil, struct, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import MODELS, SAMPLES

out = MODELS
os.makedirs(out, exist_ok=True)
def stl(name, tris):
    with open(os.path.join(out, name), "wb") as f:
        f.write(b"\0" * 80); f.write(struct.pack("<I", len(tris)))
        for a, b, c in tris:
            f.write(struct.pack("<3f", 0, 0, 0))
            for p in (a, b, c): f.write(struct.pack("<3f", *p))
            f.write(b"\0\0")
def box(x0, y0, z0, x1, y1, z1):
    c = [(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),(x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)]
    f = [(0,2,1),(0,3,2),(4,5,6),(4,6,7),(0,1,5),(0,5,4),(1,2,6),(1,6,5),(2,3,7),(2,7,6),(3,0,4),(3,4,7)]
    return [(c[a],c[b],c[d]) for a,b,d in f]
def tube(ro, ri, h, seg=96, z0=0.0):
    t = []
    P = lambda r, i, z: (r*math.cos(2*math.pi*i/seg), r*math.sin(2*math.pi*i/seg), z)
    for i in range(seg):
        t += [(P(ro,i,z0),P(ro,i+1,z0),P(ro,i+1,z0+h)), (P(ro,i,z0),P(ro,i+1,z0+h),P(ro,i,z0+h))]
        if ri > 0:
            t += [(P(ri,i,z0),P(ri,i+1,z0+h),P(ri,i+1,z0)), (P(ri,i,z0),P(ri,i,z0+h),P(ri,i+1,z0+h))]
            t += [(P(ri,i,z0+h),P(ro,i,z0+h),P(ro,i+1,z0+h)), (P(ri,i,z0+h),P(ro,i+1,z0+h),P(ri,i+1,z0+h))]
            t += [(P(ri,i,z0),P(ro,i+1,z0),P(ro,i,z0)), (P(ri,i,z0),P(ri,i+1,z0),P(ro,i+1,z0))]
        else:
            t += [((0,0,z0+h),P(ro,i,z0+h),P(ro,i+1,z0+h)), ((0,0,z0),P(ro,i+1,z0),P(ro,i,z0))]
    return t
stl("cube20.stl", box(-10,-10,0,10,10,20))
stl("plate.stl", box(-40,-30,0,40,30,3))
stl("tower.stl", box(-4,-4,0,4,4,60))
stl("tube.stl", tube(20, 17, 30))
stl("cylinder.stl", tube(15, 0, 40))
# Mushroom: thin stem under a wide cap (needs support under the cap).
stl("mushroom.stl", box(-4,-4,0,4,4,20) + box(-20,-20,20,20,20,24))
# Bridge: two pillars joined at the top (the span is a bridge).
stl("bridge.stl", box(-30,-5,0,-20,5,20) + box(20,-5,0,30,5,20) + box(-30,-5,20,30,5,24))
for name in ("spur_gear.stl", "trefoil_knot.stl", "twisted_vase.stl"):
    shutil.copy(os.path.join(SAMPLES, name), os.path.join(out, name))
print("models in", out)
