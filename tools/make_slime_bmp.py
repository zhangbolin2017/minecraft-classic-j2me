from PIL import Image
import struct

# Convert the packed 6-face slime sheet (slime_green_packed_24x16.png, a 3x2
# grid of 8x8 faces: front/up/down/left/right/back) into an 8-bit indexed BMP
# with palette index 0 reserved as the Mascot Capsule color-key (transparency).
#
# Mascot Capsule Micro3D V3 samples textures in a SQUARE, power-of-two UV
# space, so the 24x16 sheet is padded to 32x32 with the content anchored
# top-left; the transparent padding is never sampled.
# Mirrors tools/make_terrain_bmp.py / tools/make_char_bmp.py.
SRC = 'res/slime_green_packed_24x16.png'
im = Image.open(SRC).convert('RGBA')
W, H = im.size
print('slime sheet', W, H)

side = max(W, H)
p = 1
while p < side:
    p *= 2
side = p
if (W, H) != (side, side):
    sq = Image.new('RGBA', (side, side), (0, 0, 0, 0))
    sq.paste(im, (0, 0))
    im = sq
    W, H = side, side
    print('padded to square', W, H)

px = im.load()
SENT = (255, 0, 255)
rgb = Image.new('RGB', (W, H))
rp = rgb.load()
trans = 0
for y in range(H):
    for x in range(W):
        r, g, b, a = px[x, y]
        if a < 128:
            rp[x, y] = SENT
            trans += 1
        else:
            rp[x, y] = (r, g, b)
print('transparent texels', trans)

q = rgb.quantize(colors=255, method=Image.MEDIANCUT)
pal = q.getpalette()
qpx = q.load()

idx = [[0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if rp[x, y] == SENT:
            idx[y][x] = 0
        else:
            idx[y][x] = qpx[x, y] + 1

newpal = [(0, 0, 0)]
for i in range(255):
    if i * 3 + 2 < len(pal):
        newpal.append((pal[i * 3], pal[i * 3 + 1], pal[i * 3 + 2]))
    else:
        newpal.append((0, 0, 0))
while len(newpal) < 256:
    newpal.append((0, 0, 0))


def write_bmp(path):
    rowsize = (W + 3) & ~3
    pixdata = bytearray()
    for y in range(H - 1, -1, -1):
        row = bytearray(idx[y][x] for x in range(W))
        row += b'\x00' * (rowsize - W)
        pixdata += row
    palsize = 256 * 4
    off = 14 + 40 + palsize
    filesize = off + len(pixdata)
    out = bytearray()
    out += b'BM' + struct.pack('<IHHI', filesize, 0, 0, off)
    out += struct.pack('<IiiHHIIiiII', 40, W, H, 1, 8, 0, len(pixdata), 2835, 2835, 256, 0)
    for (r, g, b) in newpal:
        out += bytes((b, g, r, 0))
    out += pixdata
    open(path, 'wb').write(out)
    print('wrote', path, filesize, 'bytes')


write_bmp('res/slime.bmp')
print('done')
