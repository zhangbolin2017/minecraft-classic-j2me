from PIL import Image
import struct

# ZiShu's Wisdom Tooth (子鼠皇智齿): an ivory-yellow molar -- rounded
# crown, narrowing neck, two diverging root prongs. 16x16 RGBA icon +
# 8x8 indexed BMP billboard (palette 0 = magenta key), feather pipeline.

IVORY  = (255, 246, 220, 255)   # FFF6DC crown highlight
YELLOW = (247, 209, 58, 255)    # F7D13A main enamel
MUST   = (201, 184, 76, 255)    # C9B84C shading / roots
BONE_D = (168, 155, 69, 255)    # A89B45 root tips

W = H = 16
px = {}
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[(x, y)] = c

# crown: rows 2..7, wide and rounded (corners cut)
crown = [
    (4, 11),   # y=2: x 4..11
    (3, 12),   # y=3
    (3, 12),
    (2, 13),
    (2, 13),
    (3, 12),   # y=7: slight taper
]
for i, (x0, x1) in enumerate(crown):
    y = 2 + i
    for x in range(x0, x1 + 1):
        edge = x == x0 or x == x1
        c = MUST if edge else YELLOW
        if y <= 3 and 5 <= x <= 10:
            c = IVORY          # top-left-ish highlight band
        put(x, y, c)

# neck: rows 8..9, narrow
for y in (8, 9):
    for x in range(5, 11):
        put(x, y, MUST if x in (5, 10) else YELLOW)

# roots: two prongs rows 10..14, diverging
for y in range(10, 15):
    d = y - 10
    lx = 5 - (1 if d >= 2 else 0)          # left prong drifts left
    rx = 10 + (1 if d >= 2 else 0)         # right prong drifts right
    put(lx, y, MUST if y < 13 else BONE_D)
    put(lx + 1, y, YELLOW if y < 12 else MUST)
    put(rx - 1, y, YELLOW if y < 12 else MUST)
    put(rx, y, MUST if y < 13 else BONE_D)

im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
for (x, y), c in px.items():
    im.putpixel((x, y), c)
im.save('res/zishu_tooth.png')
print('wrote res/zishu_tooth.png')

# ---- 8x8 indexed BMP billboard ----
S = 8
img = Image.new('RGB', (S, S), (255, 0, 255))
q = img.load()
for x in range(1, 7):
    q[x, 1] = (255, 246, 220)
    for y in (2, 3):
        q[x, y] = (247, 209, 58) if 1 < x < 6 else (201, 184, 76)
for x in (3, 4):
    q[x, 4] = (201, 184, 76)
q[2, 5] = (201, 184, 76); q[2, 6] = (168, 155, 69)
q[5, 5] = (201, 184, 76); q[5, 6] = (168, 155, 69)

pal = [(255, 0, 255), (255, 246, 220), (247, 209, 58),
       (201, 184, 76), (168, 155, 69)]
while len(pal) < 256:
    pal.append((0, 0, 0))
idx = [[0] * S for _ in range(S)]
for y in range(S):
    for x in range(S):
        v = q[x, y]
        for i, p in enumerate(pal):
            if v == p:
                idx[y][x] = i
                break
rowsize = (S + 3) & ~3
pixdata = bytearray()
for y in range(S - 1, -1, -1):
    row = bytearray(idx[y][x] for x in range(S))
    row += b'\x00' * (rowsize - S)
    pixdata += row
off = 14 + 40 + 256 * 4
out = b'BM' + struct.pack('<IHHI', off + len(pixdata), 0, 0, off)
out += struct.pack('<IiiHHIIiiII', 40, S, S, 1, 8, 0, len(pixdata), 2835, 2835, 256, 0)
for (r, g, b) in pal:
    out += bytes((b, g, r, 0))
out += pixdata
open('res/zishu_tooth.bmp', 'wb').write(out)
print('wrote res/zishu_tooth.bmp')
