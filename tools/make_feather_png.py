from PIL import Image
import struct

# YouJi's Feather (酉鸡王之羽): a vertical pink feather with a cream quill.
# 16x16 RGBA icon (transparent) + 8x8 indexed BMP (palette 0 = magenta key)
# for the Mascot Capsule billboard, mirroring the bone/slimeball pipeline.

# ---- 16x16 icon: feather leans right, quill bottom-centre ----
W = H = 16
PINK   = (232, 160, 176, 255)   # E8A0B0 vane
PINK_L = (244, 216, 220, 255)   # F4D8DC barb highlight
PINK_D = (208, 136, 152, 255)   # D08898 shaft
CREAM  = (232, 197, 49, 255)    # E8C531 quill (matches YJ_YELLOW feet)

px = {}
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[(x, y)] = c

# rachis (shaft): from quill (8,14) up to the tip (10,1), slight right lean
for i in range(13):
    t = i / 12.0
    x = 8 + int(round(t * 2))
    y = 14 - i
    put(x, y, PINK_D)
# quill stub below the vane
put(8, 15, CREAM)
put(8, 14, CREAM)

# vane: horizontal barbs growing sideways, widest in the middle
half = [0, 1, 2, 3, 3, 4, 4, 4, 3, 3, 2, 1]
for i in range(len(half)):
    y = 2 + i
    t = i / 11.0
    xc = 8 + int(round((1 - t) * 2))   # follow the lean (1..0 -> shift)
    # left side slightly shorter than the right (asymmetric feather)
    for d in range(1, half[i] + 1):
        put(xc - d, y, PINK if d < half[i] else PINK_L)
        put(xc + d, y, PINK if d < half[i] else PINK_L)
    put(xc, y, PINK_D)                 # shaft over barbs

im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
for (x, y), c in px.items():
    im.putpixel((x, y), c)
im.save('res/youji_feather.png')
print('wrote res/youji_feather.png')

# ---- 8x8 indexed BMP: billboard sprite, palette 0 = colour key ----
S = 8
img = Image.new('RGB', (S, S), (255, 0, 255))
q = img.load()
# feather: shaft column x=3..4, barbs fanning, quill at bottom
for y in range(1, 7):
    q[3, y] = (208, 136, 152)          # shaft
    q[4, y] = (208, 136, 152)
    w = 1 if y <= 2 else (2 if y <= 4 else 1)
    for d in range(1, w + 1):
        c = (232, 160, 176) if d < w else (244, 216, 220)
        if 3 - d >= 0: q[3 - d, y] = c
        if 4 + d < S:  q[4 + d, y] = c
q[3, 7] = (232, 197, 49)
q[4, 7] = (232, 197, 49)

pal = [(255, 0, 255),           # 0 = transparent key
       (208, 136, 152),
       (232, 160, 176),
       (244, 216, 220),
       (232, 197, 49),
       (0, 0, 0)]
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
open('res/youji_feather.bmp', 'wb').write(out)
print('wrote res/youji_feather.bmp')
