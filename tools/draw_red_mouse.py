"""
Preview render of the RED-mouse mob.

Draws three orthographic views (front, left-side, back) onto one PNG,
using EXACTLY the box coordinates + colours defined in
  com.mojang.rubydung.level.WorldRenderer.emitRedMouse()
so the preview matches the in-game model pixel for pixel (as much as a
2D orthographic projection of a 3D box stack can).

Usage:
    python draw_red_mouse.py         # writes red_mouse_preview.png
"""
from __future__ import print_function
import os, sys, struct, zlib

# --------------------------------------------------------------------
#  Palette (copied verbatim from WorldRenderer.java constants)
# --------------------------------------------------------------------
RM_FUR     = 0x3C2A1A
RM_FURDK   = 0x2E2014
RM_BELLY   = 0xE8E0CC
RM_SKIN    = 0xD9A178
RM_SKIN2   = 0xE2B79A
RM_NOSE    = 0xE0B088
RM_TAIL1   = 0xBB9878
RM_TAIL2   = 0xA68664
RM_WOOD_LT = 0xD5A25C
RM_WOOD_DK = 0x8A5A25
RM_WHISK_W = 0xF5F5F5
RM_WHISK_B = 0x222222
RM_EYE_G   = 0x2E9E3A
RM_EYE_W   = 0xFFFFFF
RM_EYE_R   = 0xC92127
RM_MUZZLE  = 0xF0E8D4
RM_ARROW   = 0xC08850

def rgb(c):
    return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)

# --------------------------------------------------------------------
#  PNG writer (no Pillow dependency -- keeps the script zero-install)
# --------------------------------------------------------------------
def write_png(path, pixels, w, h):
    # pixels: list of (r,g,b) tuples, row-major, top row first.
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))
    raw = bytearray()
    stride = w * 3
    for y in range(h):
        raw.append(0)  # filter byte per scanline
        row = pixels[y * w: (y + 1) * w]
        for (r, g, b) in row:
            raw.append(r); raw.append(g); raw.append(b)
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)  # 8-bit RGB
    idat = zlib.compress(bytes(raw), 9)
    with open(path, "wb") as f:
        f.write(sig)
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))

# --------------------------------------------------------------------
#  Model data: a list of axis-aligned boxes in RED-mouse LOCAL space.
#
#  Local axes (same definition as emitRedMouse):
#    origin (0, 0, 0) = mouse centre at feet level (on the floor)
#    +Y = up
#    +Z = front of the mouse (toward the player, crossbow points this way)
#    +X = right side of the mouse (viewer's LEFT when looking head-on)
#
#  Each box: (x0,x1, y0,y1, z0,z1, color_rgb)
#  For side-variant colors, see comment lines; the front/back/side view
#  selector will fall back to the main colour if a face override isn't
#  provided.  For boxes with face-specific overrides we record dicts
#  keyed by view axis (see CUBE6 overrides list below).
# --------------------------------------------------------------------
BOXES = []

def addbox(x0,x1, y0,y1, z0,z1, col):
    BOXES.append((x0,x1, y0,y1, z0,z1, rgb(col)))

# Feet (under legs)
#   right foot = local -X side
addbox(-0.22, -0.02,  0.00, 0.10,  -0.08, 0.08,  RM_SKIN2)  # right foot (hw=0.10,hd=0.08,ox=-0.12)
addbox( 0.02,  0.22,  0.00, 0.10,  -0.08, 0.08,  RM_SKIN2)  # left foot

# Legs
addbox(-0.21, -0.03,  0.10, 0.50,  -0.09, 0.09,  RM_FURDK)   # right leg
addbox( 0.03,  0.21,  0.10, 0.50,  -0.09, 0.09,  RM_FURDK)   # left leg

# Body --  lower segment (white belly on +Z face (front) only)
#   hw=0.22, hd=0.18 -> X in [-0.22,0.22], Z in [-0.18,0.18]
#   override: FRONT view (+Z) -> RM_BELLY;  other faces -> RM_FUR
BOXES.append(("body-lower",
              -0.22, 0.22,  0.45, 0.75,  -0.18, 0.18,
              {"front": rgb(RM_BELLY), "back": rgb(RM_FUR),
               "side": rgb(RM_FUR), "top": rgb(RM_FUR), "bot": rgb(RM_FUR)}))
# Body --  upper torso
BOXES.append(("body-upper",
              -0.24, 0.24,  0.72, 0.95,  -0.20, 0.20,
              {"front": rgb(RM_BELLY), "back": rgb(RM_FUR),
               "side": rgb(RM_FUR), "top": rgb(RM_FUR), "bot": rgb(RM_FUR)}))

# Arms (4 segments, reaching FORWARD toward the held-out crossbow)
# Right (local -X) arm: 2 segments
addbox(-0.42, -0.26,  0.86, 1.00,   0.02, 0.18,  RM_FURDK)    # upper arm (zswing=0.10)
addbox(-0.355,-0.185, 0.70, 0.86,   0.06, 0.26,  RM_FURDK)    # lower arm (zswing=0.16)
addbox( 0.26,  0.42,  0.86, 1.00,   0.02, 0.18,  RM_FURDK)    # left upper arm
addbox( 0.185, 0.355, 0.70, 0.86,   0.06, 0.26,  RM_FURDK)    # left lower arm

# Hands (gripping the crossbar out in FRONT of the chest, zswing=0.29)
addbox(-0.39, -0.27,  0.72, 0.84,   0.24, 0.34,  RM_SKIN2)    # right hand
addbox( 0.27,  0.39,  0.72, 0.84,   0.24, 0.34,  RM_SKIN2)    # left hand

# Crossbow -- plank-coloured T held in the fists, RIGHT way round:
# the wide crossbar is at the FAR (muzzle) end facing the player and the
# stock runs back into the fists.  Tagged "bow-*" so the death rows can
# skip it (the bow drops out of the dead mouse's hands).
#   Stock (stem of the T), zswing=0.47 -> Z in [0.28, 0.66]
BOXES.append(("bow-stock",
              -0.05, 0.05,   0.75, 0.81,   0.28, 0.66,
              {"front": rgb(RM_WOOD_LT), "back": rgb(RM_WOOD_LT),
               "side": rgb(RM_WOOD_LT), "top": rgb(RM_WOOD_LT),
               "bot": rgb(RM_WOOD_LT)}))
#   Crossbar (top of the T) at the far end, zswing=0.64 -> Z in [0.61, 0.67]
BOXES.append(("bow-crossbar",
              -0.44, 0.44,   0.76, 0.82,   0.61, 0.67,
              {"front": rgb(RM_WOOD_LT), "back": rgb(RM_WOOD_LT),
               "side": rgb(RM_WOOD_LT), "top": rgb(RM_WOOD_LT),
               "bot": rgb(RM_WOOD_LT)}))
BOXES.append(("bow-grain-right",
              -0.205,-0.155, 0.77, 0.81,   0.605,0.675,
              {"front": rgb(RM_WOOD_DK), "back": rgb(RM_WOOD_DK),
               "side": rgb(RM_WOOD_DK), "top": rgb(RM_WOOD_DK),
               "bot": rgb(RM_WOOD_DK)}))
BOXES.append(("bow-grain-left",
               0.155, 0.205, 0.77, 0.81,   0.605,0.675,
              {"front": rgb(RM_WOOD_DK), "back": rgb(RM_WOOD_DK),
               "side": rgb(RM_WOOD_DK), "top": rgb(RM_WOOD_DK),
               "bot": rgb(RM_WOOD_DK)}))
#   Dark binding at the fist grip, zswing=0.32 -> Z in [0.27, 0.37]
BOXES.append(("bow-binding",
              -0.06, 0.06,   0.74, 0.83,   0.27, 0.37,
              {"front": rgb(RM_WOOD_DK), "back": rgb(RM_WOOD_DK),
               "side": rgb(RM_WOOD_DK), "top": rgb(RM_WOOD_DK),
               "bot": rgb(RM_WOOD_DK)}))

# Tail (two cubes behind the body, -Z side)
addbox(-0.06, 0.06,  0.55, 0.65,  -0.38,-0.18,  RM_TAIL1)     # base (hd=0.10, zswing=-0.28 -> [-0.38, -0.18])
addbox(-0.045,0.045, 0.57, 0.63,  -0.66,-0.34,  RM_TAIL2)     # tip  (hd=0.16, zswing=-0.50 -> [-0.66, -0.34])

# Head (brown box with specific face overrides)
#   ox=0, hw=0.22; zswing=0.05, hd=0.22 -> Z in [-0.17, 0.27]
BOXES.append(("head",
              -0.22, 0.22,  0.93, 1.28,  -0.17, 0.27,
              {"front": rgb(RM_MUZZLE),   # light grey muzzle rectangle
               "back":  rgb(RM_FUR),
               "side":  rgb(RM_FUR),
               "top":   rgb(RM_FUR),
               "bot":   rgb(RM_FUR)}))

# Ears (two flesh cubes above the head)
BOXES.append(("ear-right",
              -0.21, -0.07,  1.26, 1.44,   0.04, 0.18,
              {"front": rgb(RM_SKIN), "back": rgb(RM_SKIN),
               "side":  rgb(RM_SKIN), "top":  rgb(RM_SKIN), "bot": rgb(RM_SKIN)}))
BOXES.append(("ear-left",
               0.07,  0.21,  1.26, 1.44,   0.04, 0.18,
              {"front": rgb(RM_SKIN), "back": rgb(RM_SKIN),
               "side":  rgb(RM_SKIN), "top":  rgb(RM_SKIN), "bot": rgb(RM_SKIN)}))

# Whiskers (3 strands per side, alternating white/black/white)
# Right side (local -X), extend OUTWARD along -X, hd=0.08
addbox(-0.34,-0.31,  1.08, 1.11,   0.04, 0.12,  RM_WHISK_W)
addbox(-0.345,-0.315,1.04, 1.07,   0.035,0.125, RM_WHISK_B)
addbox(-0.34,-0.31,  1.00, 1.03,   0.04, 0.12,  RM_WHISK_W)
# Left side (local +X)
addbox( 0.31, 0.34,  1.08, 1.11,   0.04, 0.12,  RM_WHISK_W)
addbox( 0.315,0.345, 1.04, 1.07,   0.035,0.125, RM_WHISK_B)
addbox( 0.31, 0.34,  1.00, 1.03,   0.04, 0.12,  RM_WHISK_W)

# Eyes (green iris squares on the FRONT face of the head)
BOXES.append(("eye-green-right",
              -0.140,-0.070,  1.13, 1.21,   0.255, 0.295,
              {"front": rgb(RM_EYE_G), "back":  (0,0,0,),
               "side":  rgb(RM_FUR),   "top":   rgb(RM_FUR),
               "bot":   rgb(RM_FUR)}))
BOXES.append(("eye-green-left",
               0.070, 0.140,  1.13, 1.21,   0.255, 0.295,
              {"front": rgb(RM_EYE_G), "back":  (0,0,0,),
               "side":  rgb(RM_FUR),   "top":   rgb(RM_FUR),
               "bot":   rgb(RM_FUR)}))

# Tiny eye white highlights
addbox(-0.095,-0.075,  1.185,1.205,  0.275, 0.285,  RM_EYE_W)  # right eye hl
addbox( 0.075, 0.095,  1.185,1.205,  0.275, 0.285,  RM_EYE_W)  # left eye hl

# Nose (pink square dead centre of muzzle)
BOXES.append(("nose",
              -0.040, 0.040,  1.05, 1.10,   0.257, 0.293,
              {"front": rgb(RM_NOSE), "back": (0,0,0,),
               "side":  rgb(RM_FUR),  "top":  rgb(RM_FUR),
               "bot":   rgb(RM_FUR)}))

# Side-view RED eye patches on the left / right faces of the head
BOXES.append(("red-eye-right-side",
              -0.238,-0.202,  1.12, 1.20,   0.155, 0.225,
              {"front": rgb(RM_FUR), "back":  rgb(RM_FUR),
               "side":  rgb(RM_EYE_R),
               "top":   rgb(RM_FUR), "bot":   rgb(RM_FUR)}))
BOXES.append(("red-eye-left-side",
               0.202, 0.238,  1.12, 1.20,   0.155, 0.225,
              {"front": rgb(RM_FUR), "back":  rgb(RM_FUR),
               "side":  rgb(RM_EYE_R),
               "top":   rgb(RM_FUR), "bot":   rgb(RM_FUR)}))

# --------------------------------------------------------------------
#  Orthographic painter (depth sort + back-face cull per box face)
# --------------------------------------------------------------------
def face_color(box_entry, face):
    # Plain box entry:  (x0,x1,y0,y1,z0,z1, color_tuple)       -> len 7
    # Override box:     (name, x0,x1,y0,y1,z0,z1, override_dict) -> len 8
    if len(box_entry) == 7:
        return box_entry[6]
    ov = box_entry[7]
    return ov.get(face, ov.get("side", (128, 128, 128)))

def box_geo(box_entry):
    # Plain box: (x0,x1,y0,y1,z0,z1, color_tuple)  -> len 7
    # Named box: (name, x0,x1,y0,y1,z0,z1, override_dict)  -> len 8
    if len(box_entry) == 7:
        return box_entry[0], box_entry[1], box_entry[2], box_entry[3], box_entry[4], box_entry[5]
    return box_entry[1], box_entry[2], box_entry[3], box_entry[4], box_entry[5], box_entry[6]

# Draws one orthographic view with TRUE rigid-rotation polygons.
# Each box contributes 6 quads whose corners are rotated exactly like
# WorldRenderer.pitchCorners() (feet-line pivot, negative sine => falls
# BACKWARD, belly up), so the preview matches the in-game death animation.
#
# view:
#   "front"      : camera at +Z looking back (-Z)
#   "back"       : camera at -Z looking fwd  (+Z)
#   "left-side"  : camera at -X looking right (+X)
#   "right-side" : camera at +X looking left  (-X)
def draw_view(pixels, px, py,  # canvas view area top-left
              img_w, img_h,     # full canvas size
              vw, vh,           # view area size
              scale,            # world units per pixel
              center_a, center_b,
              view,
              pitch):           # death pitch in radians (0 = standing)
    # A (canvas horizontal, + right on screen) = a_axis * a_sign - center_a
    # B (canvas vertical,   + up on screen)    = b_axis * b_sign - center_b
    # C (depth: LARGER = closer to camera; painter draws small C first)
    AXES = {
        "front":      (("X",-1), ("Y",+1), ("Z",+1)),
        "back":       (("X",+1), ("Y",+1), ("Z",-1)),
        "left-side":  (("Z",+1), ("Y",+1), ("X",+1)),
        "right-side": (("Z",-1), ("Y",+1), ("X",-1)),
    }
    (ax_name, ax_s), (bx_name, bx_s), (cx_name, cx_s) = AXES[view]
    def V(axis, x, y, z):
        if axis == "X": return x
        if axis == "Y": return y
        return z

    # Rigid rotation identical to WorldRenderer.pitchCorners():
    #   sp = -sin(pitch)  -> backward fall, head to -Z, belly up
    #   lift              -> rest ON the back, not sunk into the floor
    cp = 1.0; sp = 0.0; lift = 0.0
    if pitch:
        cp = math_cos(pitch)
        sp = -math_sin(pitch)
        lift = (1.0 - cp) * 0.12
    def R(x, y, z):
        ry = lift + y * cp - z * sp
        rz =         y * sp + z * cp
        return (x, ry, rz)

    polys = []
    for entry in BOXES:
        # The crossbow is NOT in the corpse's hands: once the mouse starts
        # dying the bow drops to the ground as its own pickup, so the death
        # rows (pitch != 0) skip every "bow-*" box.
        if pitch and len(entry) == 8 and entry[0].startswith("bow"):
            continue
        x0, x1, y0, y1, z0, z1 = box_geo(entry)
        # corner labels as in cube6(): top ring a,b,c,d at y1; bottom ring 0
        A  = (x0, y1, z0); B  = (x1, y1, z0); C  = (x1, y1, z1); D  = (x0, y1, z1)
        A0 = (x0, y0, z0); B0 = (x1, y0, z0); C0 = (x1, y0, z1); D0 = (x0, y0, z1)
        # 6 physical faces; each keeps its own colour while rotating.
        FACE_LOOPS = [
            ("top",   [A,  B,  C,  D ], "top"),
            ("bot",   [A0, B0, C0, D0], "bot"),
            ("front", [D0, C0, C,  D ], "front"),   # +Z (belly / crossbow side)
            ("back",  [A0, B0, B,  A ], "back"),    # -Z (tail side)
            ("-X",    [A0, D0, D,  A ], "side"),
            ("+X",    [B0, C0, C,  B ], "side"),
        ]
        for fname, loop, cface in FACE_LOOPS:
            pts = []
            depth_sum = 0.0
            for p in loop:
                (wx, wy, wz) = R(*p)
                a = V(ax_name, wx, wy, wz) * ax_s - center_a
                b = V(bx_name, wx, wy, wz) * bx_s - center_b
                d = V(cx_name, wx, wy, wz) * cx_s
                pts.append((a, b))
                depth_sum += d
            polys.append((depth_sum / 4.0, pts, face_color(entry, cface), fname))

    # Painter sort: smallest depth (farthest) drawn FIRST.
    polys.sort(key=lambda t: t[0])

    def world_to_pix(wa, wb):
        pix_x = px + int(round(vw / 2.0 + wa * scale))
        pix_y = py + int(round(vh / 2.0 - wb * scale))
        return pix_x, pix_y

    # Rasterise each quad with a point-in-polygon (crossing) test.
    for (depth, pts, col, fname) in polys:
        pix = [world_to_pix(a, b) for (a, b) in pts]
        x_lo = max(min(p[0] for p in pix), px)
        x_hi = min(max(p[0] for p in pix), px + vw - 1)
        y_lo = max(min(p[1] for p in pix), py)
        y_hi = min(max(p[1] for p in pix), py + vh - 1)
        n = len(pts)
        for yy in range(y_lo, y_hi + 1):
            for xx in range(x_lo, x_hi + 1):
                wa = (xx - px - vw / 2.0) / scale
                wb = (vh / 2.0 - (yy - py)) / scale
                inside = False
                j = n - 1
                for i in range(n):
                    ai, bi = pts[i]
                    aj, bj = pts[j]
                    if (bi > wb) != (bj > wb):
                        xint = aj + (wb - bj) * (ai - aj) / (bi - bj)
                        if wa < xint:
                            inside = not inside
                    j = i
                if inside:
                    pixels[yy * img_w + xx] = col

def main():
    # Canvas layout:
    #   Row 0 = alive (0deg pitch):    FRONT | LEFT-SIDE | BACK
    #   Row 1 = death at 45deg :    (same 3 views, half-pitched back)
    #   Row 2 = death at 90deg :    (same 3 views, flat on back behind feet)
    VIEW_W = 280
    VIEW_H = 340
    N_COLS = 3
    N_ROWS = 3
    GAP = 16
    W = VIEW_W * N_COLS + GAP * (N_COLS + 1)
    H = VIEW_H * N_ROWS + GAP * (N_ROWS + 1)
    BG = (245, 248, 252)
    pixels = [BG] * (W * H)

    SCALE = 180.0  # 1 world unit = 180 pixels  (model is ~1.4 tall -> ~252px)
    OFF_A = 0.0
    OFF_B = 0.7   # centre vertically on ~the hip line

    def draw_border(x,y,w,h,col):
        for i in range(w):
            pixels[y*W + (x+i)] = col; pixels[(y+h-1)*W + (x+i)] = col
        for j in range(h):
            pixels[(y+j)*W + x] = col; pixels[(y+j)*W + (x+w-1)] = col

    rows = [
        ("Alive (standing, 0deg)",      0.0),
        ("Death falling back, 45deg",   0.5 * math_pi() * 0.5),
        ("Death flat on back, 90deg",   0.5 * math_pi()),
    ]
    # Per-row camera: standing is tall, the corpse lies long+flat, so each
    # row gets its own scale / vertical centre / sideways centre.
    row_params = [
        (180.0, 0.70,  0.00),   # standing
        (130.0, 0.30, -0.36),   # 45deg leaning back
        (130.0, 0.18, -0.49),   # 90deg lying flat
    ]
    views = ["front", "left-side", "back"]
    for row_i in range(len(rows)):
        row_label, pitch = rows[row_i]
        sc, offb, offa = row_params[row_i]
        for col_i, vname in enumerate(views):
            vx = GAP + col_i * (VIEW_W + GAP)
            vy = GAP + row_i * (VIEW_H + GAP)
            draw_border(vx-1, vy-1, VIEW_W+2, VIEW_H+2, (200, 210, 220))
            a_center = offa if vname in ("left-side", "right-side") else 0.0
            draw_view(pixels, vx, vy, W, H, VIEW_W, VIEW_H, sc, a_center, offb, vname, pitch)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "red_mouse_preview.png")
    write_png(out, pixels, W, H)
    write_bmp(os.path.splitext(out)[0] + ".bmp", pixels, W, H)
    print("Wrote:", out)
    print("Wrote:", os.path.splitext(out)[0] + ".bmp")

def math_pi():
    # keep imports minimal (avoiding `import math` for a single constant is
    # overkill, but we still want it typed in one place)
    return 3.141592653589793
def math_cos(a):
    import math as _m
    return _m.cos(a)
def math_sin(a):
    import math as _m
    return _m.sin(a)

def write_bmp(path, pixels, w, h):
    row_pad = (4 - (w * 3) % 4) % 4
    row_sz = w * 3 + row_pad
    data_sz = row_sz * h
    with open(path, "wb") as f:
        f.write(b"BM")
        f.write(struct.pack("<I", 14 + 40 + data_sz))
        f.write(struct.pack("<HH", 0, 0))
        f.write(struct.pack("<I", 14 + 40))
        # DIB header BITMAPINFOHEADER
        f.write(struct.pack("<I", 40))
        f.write(struct.pack("<i", w))
        f.write(struct.pack("<i", h))   # positive = bottom-up
        f.write(struct.pack("<HH", 1, 24))
        f.write(struct.pack("<I", 0))  # no compression
        f.write(struct.pack("<I", data_sz))
        f.write(struct.pack("<ii", 2835, 2835))
        f.write(struct.pack("<II", 0, 0))
        for y in range(h - 1, -1, -1):
            row = pixels[y * w: (y + 1) * w]
            for (r, g, b) in row:
                f.write(bytes((b, g, r)))
            f.write(b"\x00" * row_pad)

if __name__ == "__main__":
    main()
