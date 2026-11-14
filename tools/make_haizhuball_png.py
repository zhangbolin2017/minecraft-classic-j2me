# Draw the 16x16 drop item of the HaiZhu mob: a small glossy CYAN CUBE
# (bright cyan, isometric-ish block face feel: light top-left, shaded
# lower right, dark rim).  Outputs res/haizhuball.png (2D HUD icon) and
# res/haizhuball.bmp (8-bit indexed, palette 0 = transparent key).
# Mirrors make_slimeball_png.py.

W = H = 16

import struct

try:
    from PIL import Image
except ImportError:
    Image = None

PAL = [(0, 0, 0),
       (0x1F, 0x6E, 0x68),   # 1 rim / deepest teal
       (0x2A, 0x92, 0x8A),   # 2 dark cyan
       (0x40, 0xC6, 0xB8),   # 3 main cyan
       (0xAF, 0xF0, 0xE8)]   # 4 highlight

RIM, DARK, MAIN, LIGHT = 1, 2, 3, 4

# An upright cube silhouette: 11x11 square with small 2px corner cuts.
def inside(x, y):
    if x < 3 or x > 13 or y < 3 or y > 13:
        return False
    if (x - 3) + (y - 3) < 2: return False       # top-left cut
    if (13 - x) + (y - 3) < 2: return False      # top-right cut
    if (x - 3) + (13 - y) < 2: return False      # bottom-left cut
    if (13 - x) + (13 - y) < 2: return False     # bottom-right cut
    return True

idx = [[0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if not inside(x, y):
            continue
        # Light from the top-left.
        if x + y < 12:
            idx[y][x] = LIGHT
        elif x + y > 17:
            idx[y][x] = DARK
        else:
            idx[y][x] = MAIN
        # darken the outermost ring into a rim
        if not inside(x - 1, y) or not inside(x + 1, y) \
                or not inside(x, y - 1) or not inside(x, y + 1):
            idx[y][x] = RIM

print('haizhuball sprite (R=rim D=dark M=main L=light):')
for y in range(H):
    print('  ' + ''.join('.RDML'[idx[y][x]] for x in range(W)))

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
open('res/haizhuball.bmp', 'wb').write(out)
print('wrote res/haizhuball.bmp', filesize, 'bytes')

# --------------------------------------------------------------- png ----
if Image is not None:
    rgba = Image.new('RGBA', (W, H))
    rp = rgba.load()
    for y in range(H):
        for x in range(W):
            i = idx[y][x]
            rp[x, y] = (PAL[i][0], PAL[i][1], PAL[i][2], 0 if i == 0 else 255)
    rgba.save('res/haizhuball.png')
    print('wrote res/haizhuball.png')
    im = Image.new('RGB', (W, H))
    ip = im.load()
    for y in range(H):
        for x in range(W):
            ip[x, y] = PAL[idx[y][x]]
    im.resize((W * 16, H * 16), Image.NEAREST).save('tools/haizhuball_preview.png')
    print('wrote tools/haizhuball_preview.png')
else:
    print('PIL missing: skipped PNG + preview')
