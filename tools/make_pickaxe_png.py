from PIL import Image

# ZiShu's Pickaxe (鼠皇镐): crafted from 3 wisdom teeth.  Classic pick
# silhouette -- arched purple-red head with bright tips and a yellow
# gem (matching the teeth), brown handle with light wraps.
# 16x16 RGBA icon only (crafted item, never a ground drop).
W = H = 16
PURPLE = (139, 46, 85, 255)     # 8B2E55 head main
PURPLE_L = (216, 88, 117, 255)  # D85875 tip highlight
PURPLE_D = (110, 31, 66, 255)   # 6E1F42 head shadow
BROWN = (160, 120, 69, 255)     # A07845 handle
BROWN_D = (138, 98, 55, 255)    # 8A6237 handle shade
WRAP = (216, 184, 132, 255)     # D8B884 light wrap
GEM = (247, 209, 58, 255)       # F7D13A centre gem

px = {}
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[(x, y)] = c

# Arched head: high centre (y2), drooping tips (down to y6 at the ends).
head = {
    2: range(5, 11),    # y=2: x5..10 (crown of the arch)
    3: range(3, 13),
    4: range(2, 14),
    5: range(2, 14),
}
for y, xs in head.items():
    for x in xs:
        c = PURPLE
        if y <= 3:
            c = PURPLE_D            # darker crown shading
        put(x, y, c)
# drooping tips (2-wide) with bright highlights
for tx in (2, 3):
    put(tx, 6, PURPLE); put(tx, 7, PURPLE_L)
for tx in (12, 13):
    put(tx, 6, PURPLE); put(tx, 7, PURPLE_L)
# outline sparkle on the arch top
put(6, 2, PURPLE_L); put(9, 2, PURPLE_L)
# yellow gem at the head centre
for gy in (3, 4):
    for gx in (7, 8):
        put(gx, gy, GEM)

# Handle: vertical, x7..8, from under the gem to the bottom.
for y in range(5, 15):
    put(7, y, BROWN)
    put(8, y, BROWN_D)
# light wraps on the handle
for gx in (7, 8):
    put(gx, 8, WRAP)
    put(gx, 12, WRAP)

im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
for (x, y), c in px.items():
    im.putpixel((x, y), c)
im.save('res/zishu_pickaxe.png')
print('wrote res/zishu_pickaxe.png')
