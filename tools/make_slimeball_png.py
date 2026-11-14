# Draw the 16x16 Minecraft-style slimeball sprite: a glossy green pixel
# ball with a top-left highlight, darker right/bottom shading, a few dark
# speckles and a dark-green rim (no black outline -- the rim IS the edge).
# Outputs res/slimeball.png (2D HUD icon) and res/slimeball.bmp (8-bit
# indexed, palette 0 = Mascot transparent key, for the 3D billboard drop).
# Mirrors make_bone_bmp.py.

W = H = 16
CX, CY = 8.0, 8.0
R = 7.2

import struct

try:
    from PIL import Image
except ImportError:
    Image = None

# Palette: 0 transparent key, then rim, dark, main, light, highlight.
PAL = [(0, 0, 0),
       (0x3E, 0x69, 0x30),   # 1 rim / deepest shade
       (0x4E, 0x82, 0x38),   # 2 dark green
       (0x77, 0xB5, 0x58),   # 3 main slime green
       (0xA8, 0xD5, 0x8F),   # 4 light
       (0xE9, 0xF7, 0xDE)]   # 5 highlight core

RIM, DARK, MAIN, LIGHT, HILITE = 1, 2, 3, 4, 5

def hypot(dx, dy):
    return (dx * dx + dy * dy) ** 0.5

# Fixed dark speckles on the lower-right hemisphere.
SPECKLES = [(9, 10), (6, 12), (11, 7), (10, 12), (8, 6)]

idx = [[0] * W for _ in range(H)]
# Radial light: brightness falls off with distance from the light focus
# near the ball's upper-left, giving a small highlight cluster, a broad
# main-green field and darker shading toward the lower right.
LX, LY = 5.2, 5.2
for y in range(H):
    for x in range(W):
        dx = x + 0.5 - CX
        dy = y + 0.5 - CY
        d = hypot(dx, dy)
        if d > R:
            continue
        ld = hypot(x + 0.5 - LX, y + 0.5 - LY)
        b = 1.0 - ld / 10.0
        edge = d / R
        if edge > 0.90:
            idx[y][x] = RIM
        elif b > 0.84:
            idx[y][x] = HILITE
        elif b > 0.66:
            idx[y][x] = LIGHT
        elif b > 0.34 or edge < 0.45:
            idx[y][x] = MAIN
        else:
            idx[y][x] = DARK
        if (x, y) in SPECKLES:
            idx[y][x] = DARK

print('slimeball sprite (R=rim D=dark M=main L=light H=highlight):')
for y in range(H):
    print('  ' + ''.join('.RDMLH'[idx[y][x]] for x in range(W)))

# --------------------------------------------------------------- bmp ----
rowsize = (W + 3) & ~3
pixdata = bytearray()
for y in range(H - 1, -1, -1):
    row = bytearray(idx[y][x] for x in range(W))
    row += b'\x00' * (rowsize - W)
    pixdata += row
pal = list(PAL)
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
open('res/slimeball.bmp', 'wb').write(out)
print('wrote res/slimeball.bmp', filesize, 'bytes')

# --------------------------------------------------------------- png ----
if Image is not None:
    rgba = Image.new('RGBA', (W, H))
    rp = rgba.load()
    for y in range(H):
        for x in range(W):
            i = idx[y][x]
            rp[x, y] = (PAL[i][0], PAL[i][1], PAL[i][2], 0 if i == 0 else 255)
    rgba.save('res/slimeball.png')
    print('wrote res/slimeball.png')
    im = Image.new('RGB', (W, H))
    ip = im.load()
    for y in range(H):
        for x in range(W):
            ip[x, y] = PAL[idx[y][x]]
    im.resize((W * 16, H * 16), Image.NEAREST).save('tools/slimeball_preview.png')
    print('wrote tools/slimeball_preview.png')
else:
    print('PIL missing: skipped PNG + preview')
