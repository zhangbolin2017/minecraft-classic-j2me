from PIL import Image

# Flying Wing (飞翔翅): crafted from 5 YouJi feathers.  A single vertical
# wing in the feather palette: deep pink root -> pink mid -> light pink tip,
# cream central shaft, notched right edge for the layered feather look.
W = H = 16
PINK_D = (208, 136, 152, 255)   # D08898 root layer
PINK   = (232, 160, 176, 255)   # E8A0B0 mid vane
PINK_L = (244, 216, 220, 255)   # F4D8DC tip highlight
CREAM  = (232, 197, 49, 255)    # E8C531 shaft (matches the feather quill)

px = {}
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[(x, y)] = c

# central shaft: bottom-centre up to the tip, slight right lean
for i in range(13):
    t = i / 12.0
    x = 7 + int(round(t * 1))
    y = 13 - i
    put(x, y, CREAM)
put(7, 14, CREAM)   # quill stub
put(7, 15, CREAM)

# vane spans per row (left reach, right reach); wing widens upward,
# right side longer; right edge notched every few rows (layered look)
spans = {
    12: (2, 2), 11: (2, 3), 10: (3, 3), 9: (3, 4), 8: (3, 4),
    7: (4, 4), 6: (4, 5), 5: (4, 5), 4: (3, 5), 3: (3, 4), 2: (2, 3),
}
for y, (wl, wr) in spans.items():
    t = (13 - y) / 12.0
    xc = 7 + int(round(t * 1))          # follow the shaft lean
    c = PINK_D if y >= 11 else (PINK if y >= 5 else PINK_L)
    cl = PINK_L if y < 5 else (PINK if y >= 11 else PINK_L)   # leading edge lighter
    for d in range(1, wl + 1):
        put(xc - d, y, cl if d == wl else c)
    # right side: notch (pull the tip in on every third row)
    wr_eff = wr - (1 if (y % 3 == 0) else 0)
    for d in range(1, wr_eff + 1):
        put(xc + d, y, PINK_L if d == wr_eff and y < 11 else c)
    put(xc, y, PINK_D)                   # shaft over vane

im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
for (x, y), c in px.items():
    im.putpixel((x, y), c)
im.save('res/flying_wing.png')
print('wrote res/flying_wing.png', im.size)
