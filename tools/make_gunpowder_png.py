# Draw the 16x16 Minecraft-style gunpowder sprite: a loose gray-orange
# grain pile (flat-topped mound, jagged edges, a few darker speckles),
# lit from the top-left.  Outputs res/gunpowder.png (2D HUD icon) and
# res/gunpowder.bmp (8-bit indexed, palette 0 = transparent key, for the
# 3D billboard drop).  Mirrors make_bone_meal_png.py.

W = H = 16

import struct

try:
    from PIL import Image
except ImportError:
    Image = None

# Palette: 0 transparent key, then rim/shade, dark grains, main, light.
PAL = [(0, 0, 0),
       (0x5E, 0x59, 0x4E),   # 1 rim / deepest shade
       (0x77, 0x71, 0x64),   # 2 dark grain
       (0x9A, 0x95, 0x8A),   # 3 main warm gray
       (0xC9, 0xC2, 0xB2)]   # 4 light top

RIM, DARK, MAIN, LIGHT = 1, 2, 3, 4

def inside(x, y):
    # A flat-topped mound: wide base tapering to a narrower crest, with a
    # jagged (deterministic) edge from a tiny hash.
    h = hash_pix(x, y) % 100 / 100.0
    if y < 4 or y > 14:
        return False
    # half-width grows toward the base
    t = (y - 4) / 10.0                       # 0 at crest, 1 at base
    hw = 2.5 + t * 4.6 + (h - 0.5) * 0.9     # jagged sides
    return abs(x + 0.5 - 8.0) <= hw

def hash_pix(x, y):
    return (x * 73856093) ^ (y * 19349663)

# Fixed darker grains sprinkled on the pile.
GRAINS = [(6, 9), (9, 10), (7, 12), (10, 8), (5, 11), (8, 6), (11, 11)]

shape = [[inside(x, y) for x in range(W)] for y in range(H)]
idx = [[0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if shape[y][x]:
            # Light from the top-left: crest pixels light, base darker.
            if y <= 5:
                idx[y][x] = LIGHT
            elif y >= 11:
                idx[y][x] = DARK
            else:
                idx[y][x] = MAIN
            if (x, y) in GRAINS:
                idx[y][x] = DARK
        else:
            near = False
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < W and 0 <= ny < H and shape[ny][nx]:
                        near = True
            if near:
                idx[y][x] = RIM

print('gunpowder sprite (R=rim D=dark M=main L=light):')
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
open('res/gunpowder.bmp', 'wb').write(out)
print('wrote res/gunpowder.bmp', filesize, 'bytes')

# --------------------------------------------------------------- png ----
if Image is not None:
    rgba = Image.new('RGBA', (W, H))
    rp = rgba.load()
    for y in range(H):
        for x in range(W):
            i = idx[y][x]
            rp[x, y] = (PAL[i][0], PAL[i][1], PAL[i][2], 0 if i == 0 else 255)
    rgba.save('res/gunpowder.png')
    print('wrote res/gunpowder.png')
    im = Image.new('RGB', (W, H))
    ip = im.load()
    for y in range(H):
        for x in range(W):
            ip[x, y] = PAL[idx[y][x]]
    im.resize((W * 16, H * 16), Image.NEAREST).save('tools/gunpowder_preview.png')
    print('wrote tools/gunpowder_preview.png')
else:
    print('PIL missing: skipped PNG + preview')
