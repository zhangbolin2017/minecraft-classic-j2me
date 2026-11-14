# Draw the 16x16 Minecraft-style bone meal item sprite: a small heap of
# white powder with grey shading, darker speckles and a dark outline.
# Output res/bone_meal.png (RGBA, transparent background) for the 2D HUD
# icons -- bone meal only ever lives in the inventory, never in the world,
# so no 8-bit BMP variant is needed.  Mirrors make_bone_bmp.py.

W = H = 16

CX, CY = 8.0, 9.2     # heap centre
RX, RY = 5.2, 3.6     # heap radii

def hypot(dx, dy):
    return (dx * dx + dy * dy) ** 0.5

def inside(x, y):
    # Ellipse test, slightly flattened near the bottom for a "settled pile".
    yy = y - CY
    if y > CY:
        yy *= 0.92
    return ((x - CX) / RX) ** 2 + (yy / RY) ** 2 <= 1.0

# Palette: 0 transparent, 1 outline, 2 shade, 3 main, 4 highlight, 5 speckle.
PAL = [(0, 0, 0),
       (0x7A, 0x7A, 0x7A),
       (0xC9, 0xC9, 0xC9),
       (0xE6, 0xE6, 0xE6),
       (0xF8, 0xF8, 0xF8),
       (0xA9, 0xA9, 0xA9)]

shape = [[inside(x + 0.5, y + 0.5) for x in range(W)] for y in range(H)]

# Fixed speckle dots on the heap surface.
SPECKLES = [(6, 9), (9, 10), (7, 11), (10, 8), (5, 10), (8, 12)]

idx = [[0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if shape[y][x]:
            # Light from the top-left: offset from the highlight pole.
            dx = (x + 0.5) - (CX - RX * 0.45)
            dy = (y + 0.5) - (CY - RY * 0.55)
            d = hypot(dx, dy)
            if d < 2.3:
                idx[y][x] = 4       # highlight cap
            elif x + y > CX + CY + 2.5:
                idx[y][x] = 2       # lower-right shade
            else:
                idx[y][x] = 3       # main powder
            if (x, y) in SPECKLES:
                idx[y][x] = 5
        else:
            near = False
            for ddy in (-1, 0, 1):
                for ddx in (-1, 0, 1):
                    nx, ny = x + ddx, y + ddy
                    if 0 <= nx < W and 0 <= ny < H and shape[ny][nx]:
                        near = True
            if near:
                idx[y][x] = 1       # dark outline

print('bone meal sprite (D=outline S=shade M=main L=light K=speckle):')
for y in range(H):
    print('  ' + ''.join('.DSMLK'[idx[y][x]] for x in range(W)))

try:
    from PIL import Image
except ImportError:
    Image = None

if Image is not None:
    rgba = Image.new('RGBA', (W, H))
    rp = rgba.load()
    for y in range(H):
        for x in range(W):
            i = idx[y][x]
            rp[x, y] = (PAL[i][0], PAL[i][1], PAL[i][2], 0 if i == 0 else 255)
    rgba.save('res/bone_meal.png')
    print('wrote res/bone_meal.png')
    im = Image.new('RGB', (W, H))
    ip = im.load()
    for y in range(H):
        for x in range(W):
            ip[x, y] = PAL[idx[y][x]]
    im.resize((W * 16, H * 16), Image.NEAREST).save('tools/bone_meal_preview.png')
    print('wrote tools/bone_meal_preview.png')
else:
    print('PIL missing: run with PIL installed to write the PNG')
