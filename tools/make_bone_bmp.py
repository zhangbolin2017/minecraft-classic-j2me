# Draw the 16x16 Minecraft-style bone item sprite (the skeleton's drop):
# a white bone with round knobs at both ends and a dark outline, running
# diagonally like the vanilla item.  Output res/bone.bmp -- an 8-bit indexed
# BMP whose palette index 0 is the Mascot Capsule color-key (transparency).
# Mirrors tools/make_slime_bmp.py.  Also writes a x16 PNG preview.

# ---------------------------------------------------------------- art ----
W = H = 16

# Bone axis: knob centres A (lower-left) -> B (upper-right).
AX, AY = 2.9, 12.1
BX, BY = 12.1, 3.9
SHAFT_R = 1.15   # shaft half-thickness in px
KNOB_R = 2.05    # knob radius in px

def hypot(dx, dy):
    return (dx * dx + dy * dy) ** 0.5

def in_shape(x, y):
    if hypot(x - AX, y - AY) <= KNOB_R or hypot(x - BX, y - BY) <= KNOB_R:
        return True
    abx, aby = BX - AX, BY - AY
    apx, apy = x - AX, y - AY
    ab2 = abx * abx + aby * aby
    t = max(0.0, min(1.0, (apx * abx + apy * aby) / ab2))
    return hypot(x - (AX + abx * t), y - (AY + aby * t)) <= SHAFT_R

def side(x, y):
    # Signed perpendicular offset from the axis; positive = upper-left side
    # (which faces the light), negative = lower-right (shaded) side.
    abx, aby = BX - AX, BY - AY
    l = (abx * abx + aby * aby) ** 0.5
    return ((x - AX) * -aby + (y - AY) * abx) / l

# Palette: 0 = transparent key, 1 dark outline, 2 mid, 3 light, 4 core.
DARK, MID, LIGHT, CORE = 1, 2, 3, 4

shape = [[in_shape(x + 0.5, y + 0.5) for x in range(W)] for y in range(H)]
idx = [[0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if shape[y][x]:
            s = side(x + 0.5, y + 0.5)
            if s < -0.45:
                idx[y][x] = LIGHT       # upper-left edge faces the light
            elif s > 0.45:
                idx[y][x] = MID         # lower-right edge is shaded
            else:
                idx[y][x] = CORE
        else:
            # 1-px dark outline around the whole bone.
            near = False
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < W and 0 <= ny < H and shape[ny][nx]:
                        near = True
            if near:
                idx[y][x] = DARK

print('bone sprite (D=outline M=mid L=light C=core .=transparent):')
for y in range(H):
    print('  ' + ''.join('.DMLC'[idx[y][x]] for x in range(W)))

# --------------------------------------------------------------- bmp ----
import struct

try:
    from PIL import Image
except ImportError:
    Image = None

# Palette shared by the BMP writer and the PNG writer: 0 = transparent key.
pal_rgb = [(0, 0, 0),
           (0x8A, 0x8A, 0x8A),   # 1: dark outline
           (0xC2, 0xC2, 0xC2),   # 2: mid bone
           (0xE9, 0xE9, 0xE9),   # 3: light bone
           (0xF7, 0xF7, 0xF7)]   # 4: core highlight

def write_bmp(path):
    rowsize = (W + 3) & ~3
    pixdata = bytearray()
    for y in range(H - 1, -1, -1):
        row = bytearray(idx[y][x] for x in range(W))
        row += b'\x00' * (rowsize - W)
        pixdata += row
    pal = pal_rgb
    while len(pal) < 256:
        pal.append((0, 0, 0))
    palsize = 256 * 4
    off = 14 + 40 + palsize
    filesize = off + len(pixdata)
    out = bytearray()
    out += b'BM' + struct.pack('<IHHI', filesize, 0, 0, off)
    out += struct.pack('<IiiHHIIiiII', 40, W, H, 1, 8, 0, len(pixdata), 2835, 2835, 256, 0)
    for (r, g, b) in pal:
        out += bytes((b, g, r, 0))
    out += pixdata
    open(path, 'wb').write(out)
    print('wrote', path, filesize, 'bytes')

write_bmp('res/bone.bmp')

# The same sprite as a PNG with real transparency, for the 2D HUD icons
# (MIDP Image.createImage only guarantees PNG).
if Image is not None:
    rgba = Image.new('RGBA', (W, H))
    rp = rgba.load()
    for y in range(H):
        for x in range(W):
            i = idx[y][x]
            rp[x, y] = (pal_rgb[i][0], pal_rgb[i][1], pal_rgb[i][2], 0 if i == 0 else 255)
    rgba.save('res/bone.png')
    print('wrote res/bone.png')

# ------------------------------------------------------------ preview ----
if Image is not None:
    pal = [(0, 0, 0), (0x8A, 0x8A, 0x8A), (0xC2, 0xC2, 0xC2),
           (0xE9, 0xE9, 0xE9), (0xF7, 0xF7, 0xF7)]
    im = Image.new('RGB', (W, H))
    ip = im.load()
    for y in range(H):
        for x in range(W):
            ip[x, y] = pal[idx[y][x]]
    im.resize((W * 16, H * 16), Image.NEAREST).save('tools/bone_preview.png')
    print('wrote tools/bone_preview.png')
else:
    print('PIL missing: skipped PNG + preview')
