package com.mojang.rubydung.level;

import com.mojang.rubydung.DroppedBlock;
import com.mojang.rubydung.HitResult;
import com.mojang.rubydung.Particle;
import com.mojang.rubydung.Player;
import com.mojang.rubydung.RedMouse;
import com.mojang.rubydung.Skeleton;
import com.mojang.rubydung.Firecracker;
import com.mojang.rubydung.HaiZhu;
import com.mojang.rubydung.YouJi;
import com.mojang.rubydung.ZiShu;
import com.mojang.rubydung.Forward;
import com.mojang.rubydung.Slime;
import com.mojang.rubydung.level.tile.Tile;

import com.mascotcapsule.micro3d.v3.AffineTrans;
import com.mascotcapsule.micro3d.v3.Effect3D;
import com.mascotcapsule.micro3d.v3.FigureLayout;
import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;
import com.mascotcapsule.micro3d.v3.Vector3D;

import java.util.Vector;

/**
 * Mascot Capsule Micro3D V3 software renderer for the Classic world.
 *
 * Each frame it walks the blocks in a radius around the player and emits the
 * exposed faces only (neighbour/occlusion culled like ClassiCube), submitting
 * them as textured quads to Graphics3D.renderPrimitives() in batches that
 * respect the engine's < 256 primitives-per-call limit.
 *
 * Supports the full Classic block set:
 *   - opaque full cubes (stone, dirt, ...) with per-face textures
 *   - slabs / snow (variable top height)
 *   - sprites (flowers, saplings, mushrooms, fire) as crossed quads
 *   - transparent (glass, leaves) and translucent (water, ice) blocks,
 *     culled against occluders only so you can see through them
 *
 * Fixed-point: BLOCK view units per world block; ONE == 1.0 for directions.
 */
public class WorldRenderer {
    public static final int ONE = 4096;     // fixed-point 1.0
    public static final int BLOCK = 256;    // view units per world block

    private static final int RADIUS = 11;   // render distance in blocks
    private static final int BATCH = 200;   // max quads per renderPrimitives call
    private static final int TILE = 16;     // atlas tile px (256 / 16)
    private static final int SLIME_TILE = 8; // slime face px in the 32x32 sheet
    // Sun from the upper-right/front at roughly 45 degrees elevation, so the
    // top plus two side faces receive light.
    private static final float SUN_X = 0.50f;
    private static final float SUN_Y = 0.7071f;
    private static final float SUN_Z = 0.50f;
    private static final float AMBIENT = 0.32f;
    private static final float DIFFUSE = 0.78f;
    private float globalLight = 1.0f;

    private final ILevel level;
    private final Texture texture;
    private Texture slimeTexture;
    private Texture boneTexture;
    private Texture slimeballTexture;
    private Texture gunpowderTexture;
    private Texture featherTexture;
    private Texture toothTexture;
    private Texture haizhuballTexture;
    /** All-white neutral texture for flat-colour batches (see setWhiteTexture). */
    private Texture whiteTexture;
    private Texture curTexture;
    private final Graphics3D g3d;
    private final FigureLayout layout;
    private final Effect3D effectOpaque;
    private final Effect3D effectTrans;
    private final AffineTrans view;
    private float camRightX = 1.0f, camRightZ = 0.0f;
    /** Interpolated camera position, refreshed every frame for culling. */
    private float camPX, camPY, camPZ;

    // Scratch batch buffers (4 verts * 3 coords per quad).
    private final int[] vc = new int[BATCH * 4 * 3];
    private final int[] tc = new int[BATCH * 4 * 2];
    private final int[] col = new int[BATCH];
    private final int[] nrm = new int[] {0, 0, ONE};
    private static final int[] EMPTY_TC = new int[0];
    private int quadCount;
    private int curCommand;

    private final int cmdOpaque =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_NORMAL;

    private final int cmdSprite =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_COLORKEY;   // alpha-test for sprites/leaves/glass

    private final int cmdTrans =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_HALF;  // 50% blend for water / ice

    // Flat (untextured) per-face colour, used for mob cubes.
    private final int cmdFlat =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD_NONE |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_NORMAL;

    public WorldRenderer(ILevel level, Graphics3D g3d, Texture texture) {
        this.level = level;
        this.g3d = g3d;
        this.texture = texture;
        this.layout = new FigureLayout();
        this.effectOpaque = new Effect3D(null, Effect3D.NORMAL_SHADING, false, null);
        this.effectTrans = new Effect3D(null, Effect3D.NORMAL_SHADING, true, null);
        this.view = new AffineTrans();
        this.curCommand = cmdOpaque;
        this.curTexture = texture;
    }

    public void setGlobalLight(float globalLight) {
        if (globalLight < 0.2f) globalLight = 0.2f;
        if (globalLight > 1.0f) globalLight = 1.0f;
        this.globalLight = globalLight;
    }

    /** Second texture used for the slime's six-face sheet. */
    public void setSlimeTexture(Texture slimeTexture) {
        this.slimeTexture = slimeTexture;
    }

    public void setBoneTexture(Texture boneTexture) {
        this.boneTexture = boneTexture;
    }

    public void setSlimeballTexture(Texture slimeballTexture) {
        this.slimeballTexture = slimeballTexture;
    }

    public void setGunpowderTexture(Texture gunpowderTexture) {
        this.gunpowderTexture = gunpowderTexture;
    }

    public void setFeatherTexture(Texture featherTexture) {
        this.featherTexture = featherTexture;
    }

    public void setToothTexture(Texture toothTexture) {
        this.toothTexture = toothTexture;
    }

    public void setHaizhuballTexture(Texture haizhuballTexture) {
        this.haizhuballTexture = haizhuballTexture;
    }

    /**
     * freej2me's engine multiplies even TEXURE_COORD_NONE faces by the
     * currently bound texture's texel AND treats a null texture as "keep
     * the previous one" -- so flat-colour batches must bind this pure
     * white texture to come out with their exact face colours.  (Real
     * Micro3D hardware ignores the texture for untextured primitives,
     * so binding white is harmless there.)
     */
    public void setWhiteTexture(Texture whiteTexture) {
        this.whiteTexture = whiteTexture;
    }


    private void setupCamera(Player p, float alpha, int centerX, int centerY) {
        float px = p.xo + (p.x - p.xo) * alpha;
        float py = p.yo + (p.y - p.yo) * alpha;
        float pz = p.zo + (p.z - p.zo) * alpha;
        camPX = px; camPY = py; camPZ = pz;   // for entity distance culling

        double yaw = p.yRot * Math.PI / 180.0;
        double pitch = p.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cosPitch;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cosPitch;

        Vector3D pos = new Vector3D((int) (px * BLOCK), (int) (py * BLOCK), (int) (pz * BLOCK));
        Vector3D look = new Vector3D((int) (fx * ONE), (int) (fy * ONE), (int) (fz * ONE));
        Vector3D up = new Vector3D(0, ONE, 0);

        view.lookAt(pos, look, up);
        camRightX = (float) Math.cos(yaw);
        camRightZ = (float) Math.sin(yaw);
        layout.setCenter(centerX, centerY);
        layout.setAffineTrans(view);
        layout.setPerspective(100, 32767, 800);
    }

    public void render(Player p, float alpha, int centerX, int centerY) {
        setupCamera(p, alpha, centerX, centerY);

        int pcx = (int) p.x, pcy = (int) p.y, pcz = (int) p.z;
        int x0 = pcx - RADIUS, x1 = pcx + RADIUS;
        int y0 = pcy - RADIUS, y1 = pcy + RADIUS;
        int z0 = pcz - RADIUS, z1 = pcz + RADIUS;

        // Pass 0: opaque & sprites.  Pass 1: translucent (water/ice) last,
        // so half-blend composites over already-drawn opaque geometry.
        for (int pass = 0; pass < 2; ++pass) {
            beginBatch(pass == 0 ? cmdSprite : cmdTrans);
            for (int x = x0; x <= x1; ++x) {
                for (int y = y0; y <= y1; ++y) {
                    for (int z = z0; z <= z1; ++z) {
                        int id = level.getTile(x, y, z);
                        if (id == 0) continue;
                        Tile tile = Tile.tiles[id];
                        if (tile == null || !tile.isRenderable()) continue;
                        boolean trans = tile.isTranslucent();
                        if (pass == 0 && trans) continue;
                        if (pass == 1 && !trans) continue;
                        if (tile.isSprite()) {
                            if (pass == 0) emitSprite(tile, x, y, z);
                        } else {
                            emitCube(tile, x, y, z);
                        }
                    }
                }
            }
            flushBatch();
        }
    }

    /** Emit the exposed faces of one cube (full or slab), with neighbour cull. */
    private void emitCube(Tile tile, int x, int y, int z) {
        // Merge adjacent liquid cubes so large water bodies stay clear instead
        // of stacking many blended internal faces on top of each other.
        boolean mergeSameLiquid = tile.isLiquid();
        float th = tile.topHeight();

        // bottom (y-1)
        if (!(mergeSameLiquid ? level.hidesFace(x, y - 1, z, tile.id) : level.occludes(x, y - 1, z))) {
            int b = shade(faceBrightness(0.0f, -1.0f, 0.0f, level.getBrightness(x, y - 1, z)));
            quad(tile.getTexture(0), b,
                x,     y, z + 1,
                x,     y, z,
                x + 1, y, z,
                x + 1, y, z + 1);
        }
        // top (y+1) at slab height
        if (th >= 1.0f ? !(mergeSameLiquid ? level.hidesFace(x, y + 1, z, tile.id) : level.occludes(x, y + 1, z)) : true) {
            int b = shade(faceBrightness(0.0f, 1.0f, 0.0f, level.getBrightness(x, y + 1, z)));
            quad(tile.getTexture(1), b,
                x + 1, y + th, z + 1,
                x + 1, y + th, z,
                x,     y + th, z,
                x,     y + th, z + 1);
        }
        // z-1
        if (!(mergeSameLiquid ? level.hidesFace(x, y, z - 1, tile.id) : level.occludes(x, y, z - 1))) {
            int b = shade(faceBrightness(0.0f, 0.0f, -1.0f, level.getBrightness(x, y, z - 1)));
            quad(tile.getTexture(2), b,
                x,     y,      z,
                x,     y + th, z,
                x + 1, y + th, z,
                x + 1, y,      z);
        }
        // z+1
        if (!(mergeSameLiquid ? level.hidesFace(x, y, z + 1, tile.id) : level.occludes(x, y, z + 1))) {
            int b = shade(faceBrightness(0.0f, 0.0f, 1.0f, level.getBrightness(x, y, z + 1)));
            quad(tile.getTexture(3), b,
                x + 1, y,      z + 1,
                x + 1, y + th, z + 1,
                x,     y + th, z + 1,
                x,     y,      z + 1);
        }
        // x-1
        if (!(mergeSameLiquid ? level.hidesFace(x - 1, y, z, tile.id) : level.occludes(x - 1, y, z))) {
            int b = shade(faceBrightness(-1.0f, 0.0f, 0.0f, level.getBrightness(x - 1, y, z)));
            quad(tile.getTexture(4), b,
                x, y,      z + 1,
                x, y + th, z + 1,
                x, y + th, z,
                x, y,      z);
        }
        // x+1
        if (!(mergeSameLiquid ? level.hidesFace(x + 1, y, z, tile.id) : level.occludes(x + 1, y, z))) {
            int b = shade(faceBrightness(1.0f, 0.0f, 0.0f, level.getBrightness(x + 1, y, z)));
            quad(tile.getTexture(5), b,
                x + 1, y,      z,
                x + 1, y + th, z,
                x + 1, y + th, z + 1,
                x + 1, y,      z + 1);
        }
    }

    /** Emit a sprite block (flower/sapling/mushroom/fire) as two crossed quads. */
    private void emitSprite(Tile tile, int x, int y, int z) {
        int tex = tile.getTexture(1);
        int b = shade(level.getBrightness(x, y, z));
        for (int r = 0; r < 2; ++r) {
            double ang = (double) r * Math.PI / 2.0 + 0.7853981633974483;
            float xa = (float) (Math.sin(ang) * 0.5);
            float za = (float) (Math.cos(ang) * 0.5);
            float bx0 = x + 0.5f - xa, bx1 = x + 0.5f + xa;
            float bz0 = z + 0.5f - za, bz1 = z + 0.5f + za;
            quad(tex, b,
                bx0, y + 1.0f, bz0,
                bx1, y + 1.0f, bz1,
                bx1, y,        bz1,
                bx0, y,        bz0);
        }
    }

    private int shade(float brightness) {
        brightness *= globalLight;
        int v = (int) (brightness * 255.0f);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return (v << 16) | (v << 8) | v;
    }

    private int tintColor(int rgb) {
        int r = (int) (((rgb >> 16) & 0xFF) * globalLight);
        int g = (int) (((rgb >> 8) & 0xFF) * globalLight);
        int b = (int) ((rgb & 0xFF) * globalLight);
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;
        return (r << 16) | (g << 8) | b;
    }

    private float faceBrightness(float nx, float ny, float nz, float sky) {
        float diffuse = nx * SUN_X + ny * SUN_Y + nz * SUN_Z;
        if (diffuse < 0.0f) diffuse = 0.0f;
        return sky * (AMBIENT + diffuse * DIFFUSE);
    }

    private void beginBatch(int command) {
        beginBatch(command, texture);
    }

    private void beginBatch(int command, Texture tex) {
        flushBatch();
        curCommand = command;
        curTexture = tex;
    }

    private void quad(int tex, int color,
                      float ax, float ay, float az,
                      float bx, float by, float bz,
                      float cx, float cy, float cz,
                      float dx, float dy, float dz) {
        if (quadCount >= BATCH) flushBatch();

        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);

        int u0 = (tex % 16) * TILE;
        int v0 = (tex / 16) * TILE;
        int u1 = u0 + TILE;
        int v1 = v0 + TILE;
        if (u1 > 255) u1 = 255;
        if (v1 > 255) v1 = 255;
        int ti = quadCount * 8;
        tc[ti]     = u0; tc[ti + 1] = v1;
        tc[ti + 2] = u0; tc[ti + 3] = v0;
        tc[ti + 4] = u1; tc[ti + 5] = v0;
        tc[ti + 6] = u1; tc[ti + 7] = v1;

        col[quadCount] = color;
        quadCount++;
    }

    /** Shared empty texture-array for cmdFlat batches (no per-flush alloc). */
    private static final int[] NO_TEX = new int[0];

    private void flushBatch() {
        if (quadCount == 0) return;
        Effect3D fx = (curCommand == cmdTrans) ? effectTrans : effectOpaque;
        // renderPrimitives() reads exactly quadCount quads, so the big
        // persistent buffers can be passed as-is.  (The old code copied
        // every partial batch into 3 freshly allocated arrays, which
        // thrashed the GC and caused visible stutter on real devices.)
        g3d.renderPrimitives(curTexture, 0, 0, layout, fx,
            curCommand, quadCount, vc, nrm,
            (curCommand == cmdFlat) ? NO_TEX : tc, col);
        quadCount = 0;
    }

    public void renderNetPlayers(com.mojang.rubydung.net.NetPlayer[] players, float alpha) {
        if (players == null) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < players.length; ++i) {
            com.mojang.rubydung.net.NetPlayer p = players[i];
            if (p == null) continue;
            renderNetPlayer(p, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void renderNetPlayer(com.mojang.rubydung.net.NetPlayer p, float alpha) {
        float mx = p.xo + (p.x - p.xo) * alpha;
        float my = p.yo + (p.y - p.yo) * alpha;
        float mz = p.zo + (p.z - p.zo) * alpha;
        // server feet position; model 1.8 tall
        float feetY = my;
        double yaw = p.yaw * 360.0 / 256.0 * Math.PI / 180.0;
        float sin = (float) Math.sin(yaw);
        float cos = (float) Math.cos(yaw);

        int skin = 0xC8A064;   // head/arms
        int body = 0x5050C0;   // shirt (blue)
        int legs = 0x303060;   // trousers

        box(mx, mz, feetY + 0.75f, feetY + 1.5f, 0.25f, 0.125f, 0f, 0f, sin, cos, body);
        box(mx, mz, feetY + 1.5f, feetY + 2.0f, 0.25f, 0.25f, 0f, 0f, sin, cos, skin);
        box(mx, mz, feetY, feetY + 0.75f, 0.1f, 0.1f, -0.125f, 0f, sin, cos, legs);
        box(mx, mz, feetY, feetY + 0.75f, 0.1f, 0.1f, 0.125f, 0f, sin, cos, legs);
        box(mx, mz, feetY + 0.75f, feetY + 1.45f, 0.1f, 0.1f, -0.36f, 0f, sin, cos, skin);
        box(mx, mz, feetY + 0.75f, feetY + 1.45f, 0.1f, 0.1f, 0.36f, 0f, sin, cos, skin);
    }

    /**
     * Emit one axis-aligned-in-local-space box for a mob limb. cx/cz is the
     * mob centre; y0/y1 the world vertical span; hw/hd half-width/depth; ox a
     * sideways offset (left/right of body, in local X); zswing a forward Z
     * offset used for the walk cycle. sin/cos rotate local (X,Z) by body yaw.
     */
    private void box(float cx, float cz, float y0, float y1,
                     float hw, float hd, float ox, float zswing,
                     float sin, float cos, int color) {
        // local corners (before yaw): X in [ox-hw, ox+hw], Z in [zswing-hd, zswing+hd]
        float lx0 = ox - hw, lx1 = ox + hw;
        float lz0 = zswing - hd, lz1 = zswing + hd;
        // rotate the four (x,z) corners by yaw and translate to world
        float ax = cx + lx0 * cos - lz0 * sin, az = cz + lz0 * cos + lx0 * sin;
        float bx = cx + lx1 * cos - lz0 * sin, bz = cz + lz0 * cos + lx1 * sin;
        float cx2 = cx + lx1 * cos - lz1 * sin, cz2 = cz + lz1 * cos + lx1 * sin;
        float dx = cx + lx0 * cos - lz1 * sin, dz = cz + lz1 * cos + lx0 * sin;

        int top = color;
        int side = darken(color, 0.8f);
        int bottom = darken(color, 0.6f);

        // top
        flatQuad(top,  ax, y1, az,  bx, y1, bz,  cx2, y1, cz2,  dx, y1, dz);
        // bottom
        flatQuad(bottom, dx, y0, dz,  cx2, y0, cz2,  bx, y0, bz,  ax, y0, az);
        // 4 sides
        flatQuad(side, ax, y1, az,  bx, y1, bz,  bx, y0, bz,  ax, y0, az);
        flatQuad(side, bx, y1, bz,  cx2, y1, cz2, cx2, y0, cz2, bx, y0, bz);
        flatQuad(side, cx2, y1, cz2, dx, y1, dz,  dx, y0, dz,  cx2, y0, cz2);
        flatQuad(side, dx, y1, dz,  ax, y1, az,  ax, y0, az,  dx, y0, dz);
    }

    private int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * RED-mouse death-animation helpers: RIGID rotation of a whole box around
     * the local X axis through the feet-line (y=feet, z=0 in model space),
     * then the normal yaw + translate.  Every face corner is rotated for
     * real, so the model keeps its exact shape at every angle -- no squash,
     * no AABB bulge.  deathPitch=0 means standing; PI/2 means fallen flat on
     * its back behind the feet.
     */
    private void pitchedBox(float cx, float cz, float feet, float dPitch,
                            float y0, float y1,
                            float hw, float hd, float ox, float zswing,
                            float sin, float cos, int color) {
        pitchedCube6(cx, cz, feet, dPitch, y0, y1, hw, hd, ox, zswing, sin, cos,
                     color, darken(color, 0.60f),
                     darken(color, 0.80f), darken(color, 0.80f),
                     darken(color, 0.80f), darken(color, 0.80f));
    }

    /** Scratch space for the 8 rotated corners (x,y,z triples). */
    private final float[] _pc = new float[24];
    private void pitchCorners(float cx, float cz, float pivotY, float pivotZ,
                              float lift, float cp, float sp,
                              float lx0, float lx1, float ly0, float ly1,
                              float lz0, float lz1, float sin, float cos) {
        int i = 0;
        // order: A1 B1 C1 D1 A0 B0 C0 D0, corners labelled as in cube6():
        //   a = (-x,-z)  b = (+x,-z)  c = (+x,+z)  d = (-x,+z)
        //   1 = top (y1), 0 = bottom (y0)
        // Rotation is around the pivot LINE (y=pivotY, z=pivotZ); callers
        // pass sp NEGATED so the model falls BACKWARD / the bow tips UP.
        // lift keeps a lying corpse resting ON its back, not sunk in floor.
        for (int t = 0; t < 2; ++t) {
            float dy = (t == 0 ? ly1 : ly0) - pivotY;
            for (int k = 0; k < 4; ++k) {
                float lx = (k == 0 || k == 3) ? lx0 : lx1;
                float dz = ((k == 0 || k == 1) ? lz0 : lz1) - pivotZ;
                float ry = pivotY + lift + dy * cp - dz * sp;
                float rz = pivotZ +         dy * sp + dz * cp;
                _pc[i++] = cx + lx * cos - rz * sin;         // then the yaw
                _pc[i++] = ry;
                _pc[i++] = cz + rz * cos + lx * sin;
            }
        }
    }
    /** Emit the 6 faces of the box whose rotated corners sit in _pc[]. */
    private void emitScratchCube6(int topC, int botC, int frontC, int backC,
                                  int leftC, int rightC) {
        float a1x = _pc[0],  a1y = _pc[1],  a1z = _pc[2];
        float b1x = _pc[3],  b1y = _pc[4],  b1z = _pc[5];
        float c1x = _pc[6],  c1y = _pc[7],  c1z = _pc[8];
        float d1x = _pc[9],  d1y = _pc[10], d1z = _pc[11];
        float a0x = _pc[12], a0y = _pc[13], a0z = _pc[14];
        float b0x = _pc[15], b0y = _pc[16], b0z = _pc[17];
        float c0x = _pc[18], c0y = _pc[19], c0z = _pc[20];
        float d0x = _pc[21], d0y = _pc[22], d0z = _pc[23];
        // Same face winding as cube6(); corners are rigidly rotated and each
        // physical face keeps its own colour (top stays the top).
        flatQuad(topC,   a1x,a1y,a1z,  b1x,b1y,b1z,  c1x,c1y,c1z,  d1x,d1y,d1z);
        flatQuad(botC,   d0x,d0y,d0z,  c0x,c0y,c0z,  b0x,b0y,b0z,  a0x,a0y,a0z);
        flatQuad(leftC,  a1x,a1y,a1z,  b1x,b1y,b1z,  b0x,b0y,b0z,  a0x,a0y,a0z);
        flatQuad(backC,  b1x,b1y,b1z,  c1x,c1y,c1z,  c0x,c0y,c0z,  b0x,b0y,b0z);
        flatQuad(rightC, c1x,c1y,c1z,  d1x,d1y,d1z,  d0x,d0y,d0z,  c0x,c0y,c0z);
        flatQuad(frontC, d1x,d1y,d1z,  a1x,a1y,a1z,  a0x,a0y,a0z,  d0x,d0y,d0z);
    }
    /**
     * One piece of the crossbow.  While ALIVE the piece pivots around the
     * fist grip (y = feet+0.78, z = 0.30) by the aim pitch so the T-bow
     * always points at the player; while DYING it simply falls rigidly
     * backward with the rest of the body (feet-line pivot).
     */
    private void bowBox(float cx, float cz, float feet, float dPitch,
                        float aimPitch,
                        float y0, float y1, float hw, float hd,
                        float ox, float zswing,
                        float sin, float cos, int color) {
        int botC  = darken(color, 0.60f);
        int sideC = darken(color, 0.80f);
        if (dPitch != 0f) {
            pitchedCube6(cx, cz, feet, dPitch, y0, y1, hw, hd, ox, zswing,
                         sin, cos, color, botC, sideC, sideC, sideC, sideC);
            return;
        }
        if (aimPitch == 0f) {
            box(cx, cz, y0, y1, hw, hd, ox, zswing, sin, cos, color);
            return;
        }
        // negative sine => the +Z muzzle tips UP toward the player's eyes
        float cp = (float) Math.cos(aimPitch), sp = -(float) Math.sin(aimPitch);
        pitchCorners(cx, cz, feet + 0.78f, 0.30f, 0f, cp, sp,
                     ox - hw, ox + hw, y0, y1, zswing - hd, zswing + hd,
                     sin, cos);
        emitScratchCube6(color, botC, sideC, sideC, sideC, sideC);
    }
    private void pitchedCube6(float cx, float cz, float feet, float dPitch,
                              float y0, float y1,
                              float hw, float hd, float ox, float zswing,
                              float sin, float cos,
                              int topC, int botC, int frontC, int backC,
                              int leftC, int rightC) {
        if (dPitch == 0f) {
            cube6(cx, cz, y0, y1, hw, hd, ox, zswing, sin, cos,
                  topC, botC, frontC, backC, leftC, rightC);
            return;
        }
        float lx0 = ox - hw, lx1 = ox + hw;
        float lz0 = zswing - hd, lz1 = zswing + hd;
        // negative sine => the fall is BACKWARD (belly up, head behind feet)
        float cp = (float) Math.cos(dPitch), sp = -(float) Math.sin(dPitch);
        float lift = (1.0f - cp) * 0.12f;
        pitchCorners(cx, cz, feet, 0f, lift, cp, sp,
                     lx0, lx1, y0, y1, lz0, lz1, sin, cos);
        emitScratchCube6(topC, botC, frontC, backC, leftC, rightC);
    }

    /** Like quad() but with no texture coords (flat per-face colour). */
    private void flatQuad(int color,
                          float ax, float ay, float az,
                          float bx, float by, float bz,
                          float cx, float cy, float cz,
                          float dx, float dy, float dz) {
        if (quadCount >= BATCH) flushBatch();
        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);
        col[quadCount] = color;
        quadCount++;
    }

    public void renderParticles(java.util.Vector particles, Player p, float alpha) {
        if (particles == null || particles.size() == 0) return;
        beginBatch(cmdSprite);
        float rx = camRightX, rz = camRightZ;
        for (int i = 0; i < particles.size(); ++i) {
            Particle pa = (Particle) particles.elementAt(i);
            if (pa.removed) continue;
            float px = pa.xo + (pa.x - pa.xo) * alpha;
            float py = pa.yo + (pa.y - pa.yo) * alpha;
            float pz = pa.zo + (pa.z - pa.zo) * alpha;
            float s = 0.1f;
            // billboard quad facing camera: right vector (rx,0,rz), up (0,1,0)
            float ax = px - rx * s, az = pz - rz * s;
            float bx = px + rx * s, bz = pz + rz * s;
            int b = shade(level.getBrightness((int) px, (int) py, (int) pz));
            // sub-tile UVs sampled from the block atlas tile
            int tile = pa.tex;
            int u0 = (tile % 16) * TILE + (int) (pa.uo * TILE);
            int v0 = (tile / 16) * TILE + (int) (pa.vo * TILE);
            int u1 = u0 + 3, v1 = v0 + 3;
            partQuad(b,
                ax, py + s, az,  bx, py + s, bz,
                bx, py - s, bz,  ax, py - s, az,
                u0, v0, u1, v1);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    public void renderDroppedBlocks(java.util.Vector drops, float alpha) {
        if (drops == null || drops.size() == 0) return;
        beginBatch(cmdSprite);
        float rx = camRightX, rz = camRightZ;
        for (int i = 0; i < drops.size(); ++i) {
            DroppedBlock drop = (DroppedBlock) drops.elementAt(i);
            if (drop == null || drop.removed) continue;
            float px = drop.xo + (drop.x - drop.xo) * alpha;
            float py = drop.yo + (drop.y - drop.yo) * alpha;
            float pz = drop.zo + (drop.z - drop.zo) * alpha;
            float s = 0.18f;
            float ax = px - rx * s, az = pz - rz * s;
            float bx = px + rx * s, bz = pz + rz * s;
            int b = shade(level.getBrightness((int) px, (int) py, (int) pz));
            int tex = drop.tex;
            if (tex == 0) {
                Tile tile = Tile.tiles[drop.blockId];
                if (tile == null) continue;
                tex = tile.getTexture(1);
            }
            int u0 = (tex % 16) * TILE;
            int v0 = (tex / 16) * TILE;
            int u1 = u0 + TILE, v1 = v0 + TILE;
            if (u1 > 255) u1 = 255;
            if (v1 > 255) v1 = 255;
            partQuad(b,
                ax, py + s, az,  bx, py + s, bz,
                bx, py - s, bz,  ax, py - s, az,
                u0, v0, u1, v1);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    public void renderSlimes(java.util.Vector slimes, float alpha) {
        if (slimeTexture == null || slimes == null || slimes.size() == 0) return;
        beginBatch(cmdSprite, slimeTexture);
        for (int i = 0; i < slimes.size(); ++i) {
            Slime s = (Slime) slimes.elementAt(i);
            if (s == null || s.removed) continue;
            emitSlime(s, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
        curTexture = texture;
    }

    // ---- RED-mouse ----
    //
    // Pure-box bipedal model (no spritesheet).  Body yaw rotates (X,Z);
    // individual parts sit at fixed local offsets, which the box() helper
    // rotates into world space for us.  Every face of every cube gets a
    // solid colour, slightly darker on the sides and bottom.
    //
    // Palette (pulled directly from the user's reference renders):
    private static final int RM_FUR      = 0x3C2A1A;  // dark brown-black head/back/legs
    private static final int RM_FURDK    = 0x2E2014;  // slightly darker arms/legs
    private static final int RM_BELLY    = 0xE8E0CC;  // creamy white belly/chest
    private static final int RM_SKIN     = 0xD9A178;  // pink-flesh ears/nose top
    private static final int RM_SKIN2    = 0xE2B79A;  // lighter flesh feet/paws
    private static final int RM_NOSE     = 0xE0B088;  // nose tip (little square)
    private static final int RM_TAIL1    = 0xBB9878;  // tail base (bigger cube)
    private static final int RM_TAIL2    = 0xA68664;  // tail tip (thinner cube)
    private static final int RM_WOOD_LT  = 0xD5A25C;  // crossbow light wood
    private static final int RM_WOOD_DK  = 0x8A5A25;  // crossbow dark wood stripe
    private static final int RM_WHISK_W  = 0xF5F5F5;  // white whisker strand
    private static final int RM_WHISK_B  = 0x222222;  // black whisker strand
    private static final int RM_EYE_G    = 0x2E9E3A;  // eye green iris
    private static final int RM_EYE_W    = 0xFFFFFF;  // eye tiny highlight
    private static final int RM_EYE_R    = 0xC92127;  // side-view red eye patch
    private static final int RM_MUZZLE   = 0xF0E8D4;  // light grey muzzle rectangle
    private static final int RM_ARROW    = 0xC08850;  // fired arrow shaft colour

    public void renderRedMice(java.util.Vector mice, float alpha) {
        if (mice == null || mice.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < mice.size(); ++i) {
            RedMouse m = (RedMouse) mice.elementAt(i);
            if (m == null || m.removed) continue;
            // Cull mice too far away to see: each mouse is ~30 boxes and
            // software rasterisation pays for every quad.
            float ddx = m.x - camPX, ddy = m.y - camPY, ddz = m.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitRedMouse(m, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    public void renderArrows(java.util.Vector arrows, float alpha) {
        if (arrows == null || arrows.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < arrows.size(); ++i) {
            RedMouse.Arrow a = (RedMouse.Arrow) arrows.elementAt(i);
            if (a == null || a.removed) continue;
            float adx = a.x - camPX, ady = a.y - camPY, adz = a.z - camPZ;
            if (adx * adx + ady * ady + adz * adz > 24f * 24f) continue;
            float ax = a.xo + (a.x - a.xo) * alpha;
            float ay = a.yo + (a.y - a.yo) * alpha;
            float az = a.zo + (a.z - a.zo) * alpha;
            // Align the arrow shaft with its velocity direction.
            float vx = a.xd, vy = a.yd, vz = a.zd;
            float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl < 0.0001f) { vx = 0f; vy = 0f; vz = 1f; vl = 1f; }
            vx /= vl; vy /= vl; vz /= vl;
            // Build a local right vector that's perpendicular to the heading.
            float rx = 1f, rz = 0f, ry = 0f;
            float hx = vx, hz = vz;
            float hl = (float) Math.sqrt(hx * hx + hz * hz);
            if (hl > 0.001f) { rx =  hz / hl; rz = -hx / hl; } else { ry = 1f; }
            float ux = vy * rz - vz * ry;
            float uy = vz * rx - vx * rz;
            float uz = vx * ry - vy * rx;
            float s = 0.03f;  // shaft half-thickness
            float l = 0.16f;  // shaft half-length (total 0.32)
            // 8 corners: +/-along (vx*l), +/-right (rx*s), +/-up (ux*s*0.6)
            float up = 0.6f;
            float p0x = ax + (-vx * l) + (-rx * s) + (-ux * s * up);
            float p0y = ay + (-vy * l) + (-ry * s) + (-uy * s * up);
            float p0z = az + (-vz * l) + (-rz * s) + (-uz * s * up);
            float p1x = ax + (-vx * l) + ( rx * s) + (-ux * s * up);
            float p1y = ay + (-vy * l) + ( ry * s) + (-uy * s * up);
            float p1z = az + (-vz * l) + ( rz * s) + (-uz * s * up);
            float p2x = ax + ( vx * l) + ( rx * s) + (-ux * s * up);
            float p2y = ay + ( vy * l) + ( ry * s) + (-uy * s * up);
            float p2z = az + ( vz * l) + ( rz * s) + (-uz * s * up);
            float p3x = ax + ( vx * l) + (-rx * s) + (-ux * s * up);
            float p3y = ay + ( vy * l) + (-ry * s) + (-uy * s * up);
            float p3z = az + ( vz * l) + (-rz * s) + (-uz * s * up);
            float p4x = ax + (-vx * l) + (-rx * s) + (ux * s * up);
            float p4y = ay + (-vy * l) + (-ry * s) + (uy * s * up);
            float p4z = az + (-vz * l) + (-rz * s) + (uz * s * up);
            float p5x = ax + (-vx * l) + ( rx * s) + (ux * s * up);
            float p5y = ay + (-vy * l) + ( ry * s) + (uy * s * up);
            float p5z = az + (-vz * l) + ( rz * s) + (uz * s * up);
            float p6x = ax + ( vx * l) + ( rx * s) + (ux * s * up);
            float p6y = ay + ( vy * l) + ( ry * s) + (uy * s * up);
            float p6z = az + ( vz * l) + ( rz * s) + (uz * s * up);
            float p7x = ax + ( vx * l) + (-rx * s) + (ux * s * up);
            float p7y = ay + ( vy * l) + (-ry * s) + (uy * s * up);
            float p7z = az + ( vz * l) + (-rz * s) + (uz * s * up);
            int cSide = darken(RM_ARROW, 0.9f);
            int cTop  = darken(RM_ARROW, 1.0f);
            int cBot  = darken(RM_ARROW, 0.7f);
            flatQuad(cSide, p0x,p0y,p0z, p3x,p3y,p3z, p7x,p7y,p7z, p4x,p4y,p4z);
            flatQuad(cSide, p3x,p3y,p3z, p2x,p2y,p2z, p6x,p6y,p6z, p7x,p7y,p7z);
            flatQuad(cSide, p2x,p2y,p2z, p1x,p1y,p1z, p5x,p5y,p5z, p6x,p6y,p6z);
            flatQuad(cSide, p1x,p1y,p1z, p0x,p0y,p0z, p4x,p4y,p4z, p5x,p5y,p5z);
            flatQuad(cTop,  p4x,p4y,p4z, p7x,p7y,p7z, p6x,p6y,p6z, p5x,p5y,p5z);
            flatQuad(cBot,  p0x,p0y,p0z, p1x,p1y,p1z, p2x,p2y,p2z, p3x,p3y,p3z);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    /**
     * Crossbows dropped by dying RedMice: the plank-coloured T lying flat
     * on the ground where it fell.  It stays exactly where it landed until
     * the player punches it (turns into planks).
     */
    public void renderBowDrops(java.util.Vector bows) {
        if (bows == null || bows.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < bows.size(); ++i) {
            RedMouse.BowDrop b = (RedMouse.BowDrop) bows.elementAt(i);
            if (b == null || b.removed) continue;
            // Bows accumulate forever by design -- cull the far ones so
            // the render cost stays bounded.
            float bdx = b.x - camPX, bdy = b.y - camPY, bdz = b.z - camPZ;
            if (bdx * bdx + bdy * bdy + bdz * bdz > 28f * 28f) continue;
            // Yaw basis from the heading the bow had when it slipped loose.
            float fx = b.headingX, fz = b.headingZ;
            float len = (float) Math.sqrt(fx * fx + fz * fz);
            if (len < 0.0001f) { fx = 0f; fz = 1f; }
            else { fx /= len; fz /= len; }
            float sin = -fx, cos = fz;
            float gy = b.y;                 // lying flat on the ground
            // Stock (stem): runs from the centre out along the heading.
            box(b.x, b.z, gy, gy + 0.05f, 0.03f, 0.20f, 0f, 0f, sin, cos, RM_WOOD_LT);
            // Crossbar (top of the T) at the far end, perpendicular.
            box(b.x, b.z, gy, gy + 0.05f, 0.25f, 0.025f, 0f, 0.20f, sin, cos, RM_WOOD_LT);
            // Two dark grain stripes on the crossbar ends.
            box(b.x, b.z, gy, gy + 0.05f, 0.025f, 0.025f, -0.18f, 0.20f, sin, cos, RM_WOOD_DK);
            box(b.x, b.z, gy, gy + 0.05f, 0.025f, 0.025f,  0.18f, 0.20f, sin, cos, RM_WOOD_DK);
            // Dark binding near the grip end.
            box(b.x, b.z, gy, gy + 0.06f, 0.05f, 0.03f, 0f, 0.02f, sin, cos, RM_WOOD_DK);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    /**
     * Skeletons: melee chasers built from one head cube, one body cube,
     * thin limb cubes and a one-cube iron sword (RedMouse box style).
     */
    public void renderSkeletons(java.util.Vector skeletons, float alpha) {
        if (skeletons == null || skeletons.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < skeletons.size(); ++i) {
            Skeleton s = (Skeleton) skeletons.elementAt(i);
            if (s == null || s.removed) continue;
            // Cull like the mice: software rasterisation pays per quad.
            float ddx = s.x - camPX, ddy = s.y - camPY, ddz = s.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitSkeleton(s, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    // ---- Firecracker mob ----
    //
    // One head cube + one body cube (GREEN, creeper-like) standing on two
    // RED firecracker foot cubes, with a two-cube fuse rope on the head.
    // While lit the fuse RETRACTS into the head and the body flashes.
    private static final int FC_GREEN = 0x56A83F;  // creeper-green head/body
    private static final int FC_RED   = 0xC4342A;  // red firecracker feet
    private static final int FC_FUSE  = 0x4A3B28;  // fuse rope
    private static final int FC_FACE  = 0x1A1A1A;  // eyes/mouth
    private static final int FC_FLASH = 0xE8F2D8;  // about-to-blow flash tint

    // Debris parts: 0 head, 1 body, 2/3 feet, 4 fuse.
    private static final float[] FC_PART_HW = { 0.19f,  0.16f,   0.09f, 0.09f, 0.035f };
    private static final float[] FC_PART_HH = { 0.22f,  0.35f,   0.08f, 0.08f, 0.14f  };
    private static final float[] FC_PART_HD = { 0.19f,  0.13f,   0.09f, 0.09f, 0.035f };
    private static final int[]   FC_PART_C  = { FC_GREEN, FC_GREEN, FC_RED, FC_RED, FC_FUSE };

    public void renderFirecrackers(java.util.Vector firecrackers, float alpha) {
        if (firecrackers == null || firecrackers.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < firecrackers.size(); ++i) {
            Firecracker f = (Firecracker) firecrackers.elementAt(i);
            if (f == null || f.removed) continue;
            float ddx = f.x - camPX, ddy = f.y - camPY, ddz = f.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitFirecracker(f, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void emitFirecracker(Firecracker f, float alpha) {
        float px = f.xo + (f.x - f.xo) * alpha;
        float py = f.yo + (f.y - f.yo) * alpha;
        float pz = f.zo + (f.z - f.zo) * alpha;

        // Death, phase 2: debris cubes.  Phase 1 (the ~1 s frozen corpse)
        // renders the standing model below, minus its burned-away fuse.
        if (f.dying && !f.isFrozen()) {
            for (int i = 0; i < Firecracker.PARTS; ++i) {
                tumbleBox(f.partX(i, alpha), f.partY(i, alpha), f.partZ(i, alpha),
                          FC_PART_HW[i], FC_PART_HH[i], FC_PART_HD[i],
                          f.partYaw(i, alpha), f.partPitch(i, alpha),
                          FC_PART_C[i]);
            }
            return;
        }

        float fx = f.headingX, fz = f.headingZ;
        float sin = -fx;
        float cos =  fz;
        float feet = py;

        // Flash while the fuse burns (classic blink before the blast).
        boolean flash = f.isFusing() && ((f.fuseT >> 2) & 1) == 0;
        int bodyC = flash ? FC_FLASH : FC_GREEN;

        float bob = f.walkBob;
        float legFwd = (float) Math.sin(bob) * 0.14f;
        float legBwd = -legFwd;

        // ---------- Feet (two RED firecracker cubes) ----------
        pitchedBox(px, pz, feet, 0f, feet, feet + 0.16f,
            0.09f, 0.09f, -0.09f, legFwd, sin, cos, FC_RED);
        pitchedBox(px, pz, feet, 0f, feet, feet + 0.16f,
            0.09f, 0.09f,  0.09f, legBwd, sin, cos, FC_RED);

        // ---------- Body: ONE cube (creeper-green) ----------
        pitchedCube6(px, pz, feet, 0f, feet + 0.16f, feet + 0.86f,
              0.16f, 0.13f, 0f, 0f, sin, cos,
              bodyC, darken(bodyC, 0.60f),
              darken(bodyC, 0.90f), darken(bodyC, 0.80f),
              darken(bodyC, 0.80f), darken(bodyC, 0.80f));

        // ---------- Head: ONE cube with a black face ----------
        pitchedCube6(px, pz, feet, 0f, feet + 0.86f, feet + 1.30f,
              0.19f, 0.19f, 0f, 0f, sin, cos,
              bodyC, darken(bodyC, 0.60f),
              darken(bodyC, 0.92f), darken(bodyC, 0.80f),
              darken(bodyC, 0.80f), darken(bodyC, 0.80f));
        // Face: two eyes + a wide grimace mouth, proud of the +Z face.
        pitchedBox(px, pz, feet, 0f, feet + 1.10f, feet + 1.18f,
            0.040f, 0.012f, -0.070f, 0.195f, sin, cos, FC_FACE);
        pitchedBox(px, pz, feet, 0f, feet + 1.10f, feet + 1.18f,
            0.040f, 0.012f,  0.070f, 0.195f, sin, cos, FC_FACE);
        pitchedBox(px, pz, feet, 0f, feet + 0.94f, feet + 1.00f,
            0.070f, 0.012f,  0f, 0.195f, sin, cos, FC_FACE);

        // ---------- Fuse: two stacked rope cubes that RETRACT into the
        // ---------- head as the fuse burns down (fully in = boom). ----------
        float fuseH = 0.22f * (1.0f - f.fuseProgress());
        if (f.isFrozen()) fuseH = 0f;   // defused dud: the fuse is gone
        if (fuseH > 0.015f) {
            float h1 = fuseH < 0.11f ? fuseH : 0.11f;
            pitchedBox(px, pz, feet, 0f, feet + 1.30f, feet + 1.30f + h1,
                0.035f, 0.035f, 0f, 0f, sin, cos, FC_FUSE);
            if (fuseH > 0.11f) {
                pitchedBox(px, pz, feet, 0f, feet + 1.41f, feet + 1.30f + fuseH,
                    0.028f, 0.028f, 0f, 0f, sin, cos, FC_FUSE);
            }
        }
    }

    /**
     * Helper: emit a small per-face-coloured cube with local offsets + yaw.
     * Identical semantics to box() but lets us pass different colours per
     * face, which we need for the head (green eyes/ears/muzzle differ from
     * the brown front face).
     */
    private void cube6(float cx, float cz, float y0, float y1,
                       float hw, float hd, float ox, float zswing,
                       float sin, float cos,
                       int topC, int botC, int frontC, int backC,
                       int leftC, int rightC) {
        float lx0 = ox - hw, lx1 = ox + hw;
        float lz0 = zswing - hd, lz1 = zswing + hd;
        float ax = cx + lx0 * cos - lz0 * sin, az = cz + lz0 * cos + lx0 * sin;
        float bx = cx + lx1 * cos - lz0 * sin, bz = cz + lz0 * cos + lx1 * sin;
        float cx2 = cx + lx1 * cos - lz1 * sin, cz2 = cz + lz1 * cos + lx1 * sin;
        float dx = cx + lx0 * cos - lz1 * sin, dz = cz + lz1 * cos + lx0 * sin;
        flatQuad(topC,   ax, y1, az,  bx, y1, bz,  cx2, y1, cz2,  dx, y1, dz);
        flatQuad(botC,   dx, y0, dz,  cx2, y0, cz2,  bx, y0, bz,  ax, y0, az);
        // sides: left=a-b,  back=b-cx,  right=cx-dx,  front=dx-a
        flatQuad(leftC,  ax, y1, az,  bx, y1, bz,  bx, y0, bz,  ax, y0, az);
        flatQuad(backC,  bx, y1, bz,  cx2, y1, cz2, cx2, y0, cz2, bx, y0, bz);
        flatQuad(rightC, cx2, y1, cz2, dx, y1, dz,  dx, y0, dz,  cx2, y0, cz2);
        flatQuad(frontC, dx, y1, dz,  ax, y1, az,  ax, y0, az,  dx, y0, dz);
    }

    /**
     * Build one RED-mouse out of many independent cubes.
     *
     * Local coordinates:
     *   (0,0,0) = mouse centre, feet on the floor.
     *   +Y = up; +Z = heading (front of the mouse, crossbow points this way)
     *   +X = right of the mouse (viewer left when looking head-on).
     */
    private void emitRedMouse(RedMouse m, float alpha) {
        float px = m.xo + (m.x - m.xo) * alpha;
        float py = m.yo + (m.y - m.yo) * alpha;
        float pz = m.zo + (m.z - m.zo) * alpha;

        // Death: rigid backward fall around the feet line.  RedMouse stores
        // last tick's pitch so this interpolates smoothly between ticks.
        float dPitch = m.dying
                ? m.deathPitchPrev + (m.deathPitch - m.deathPitchPrev) * alpha
                : 0f;

        // Yaw basis: heading (fx,fz) = local +Z; right (rx,rz) = local +X.
        float fx = m.headingX, fz = m.headingZ;
        float rx = -fz, rz = fx;
        float sin = -fx;
        float cos =  fz;

        float feet = py;
        float hscale = 1.0f;   // the body never squashes; death is pure rotation

        // Walk cycle: legs and arms swing fore/aft with walkBob phase.
        float bob = m.walkBob;
        float legFwd  = (float) Math.sin(bob)          * 0.18f;
        float legBwd  = (float) Math.sin(bob + 3.14f)  * 0.18f;
        float armSwing = (float) Math.sin(bob)         * 0.10f;

        // ---------- Feet (two small flesh cubes, under legs) ----------
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.10f,
            0.10f, 0.08f, -0.12f, legFwd,  sin, cos, RM_SKIN2);
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.10f,
            0.10f, 0.08f,  0.12f, legBwd,  sin, cos, RM_SKIN2);

        // ---------- Legs (two tall dark-fur cubes) ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.10f, feet + 0.50f,
            0.09f, 0.09f, -0.12f, legFwd,  sin, cos, RM_FURDK);
        pitchedBox(px, pz, feet, dPitch, feet + 0.10f, feet + 0.50f,
            0.09f, 0.09f,  0.12f, legBwd,  sin, cos, RM_FURDK);

        // ---------- Body (white belly on front, brown fur elsewhere) ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 0.45f, feet + 0.75f,
              0.22f, 0.18f, 0f, 0f, sin, cos,
              darken(RM_FUR, 1.0f), darken(RM_FUR, 0.60f),
              darken(RM_BELLY, 0.92f), darken(RM_FUR, 0.80f),
              darken(RM_FUR, 0.80f), darken(RM_FUR, 0.80f));
        pitchedCube6(px, pz, feet, dPitch, feet + 0.72f, feet + 0.95f,
              0.24f, 0.20f, 0f, 0f, sin, cos,
              darken(RM_FUR, 1.0f), darken(RM_FUR, 0.60f),
              darken(RM_BELLY, 0.95f), darken(RM_FUR, 0.80f),
              darken(RM_FUR, 0.80f), darken(RM_FUR, 0.80f));

        // ---------- Arms (fur stumps reaching FORWARD toward the crossbow) ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.86f, feet + 1.00f,
            0.08f, 0.08f, -0.34f, 0.10f + armSwing, sin, cos, RM_FURDK);
        pitchedBox(px, pz, feet, dPitch, feet + 0.70f, feet + 0.86f,
            0.085f, 0.10f, -0.27f, 0.16f + armSwing, sin, cos, RM_FURDK);
        pitchedBox(px, pz, feet, dPitch, feet + 0.86f, feet + 1.00f,
            0.08f, 0.08f,  0.34f, 0.10f - armSwing, sin, cos, RM_FURDK);
        pitchedBox(px, pz, feet, dPitch, feet + 0.70f, feet + 0.86f,
            0.085f, 0.10f,  0.27f, 0.16f - armSwing, sin, cos, RM_FURDK);

        // ---------- Hands (gripping the crossbar out in FRONT of the chest) ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.72f, feet + 0.84f,
            0.06f, 0.05f, -0.33f, 0.29f, sin, cos, RM_SKIN2);
        pitchedBox(px, pz, feet, dPitch, feet + 0.72f, feet + 0.84f,
            0.06f, 0.05f,  0.33f, 0.29f, sin, cos, RM_SKIN2);

        // ---------- Crossbow (plank-coloured T held out in front, aimed at
        // the player).  The RIGHT way round: the wide crossbar sits at the
        // FAR muzzle end facing the player, the stock runs back into the
        // fists.  Skipped while dying -- the bow slips out of the dead
        // mouse's hands and drops to the ground as its own pickup. ----------
        if (!m.dying) {
            float bowAim = m.aimPitch;
            if (bowAim > 0.7f) bowAim = 0.7f;    // clamp so it never flips silly
            if (bowAim < -0.7f) bowAim = -0.7f;
            // Stock (stem of the T): from the fists out to the muzzle end.
            bowBox(px, pz, feet, dPitch, bowAim, feet + 0.75f, feet + 0.81f,
                0.05f, 0.19f, 0f, 0.47f, sin, cos, RM_WOOD_LT);
            // Crossbar (top of the T) at the FAR end, facing the player.
            bowBox(px, pz, feet, dPitch, bowAim, feet + 0.76f, feet + 0.82f,
                0.44f, 0.03f, 0f, 0.64f, sin, cos, RM_WOOD_LT);
            bowBox(px, pz, feet, dPitch, bowAim, feet + 0.77f, feet + 0.81f,
                0.025f, 0.035f, -0.18f, 0.64f, sin, cos, RM_WOOD_DK);
            bowBox(px, pz, feet, dPitch, bowAim, feet + 0.77f, feet + 0.81f,
                0.025f, 0.035f, 0.18f, 0.64f, sin, cos, RM_WOOD_DK);
            // Dark binding where the fists grip the near end.
            bowBox(px, pz, feet, dPitch, bowAim, feet + 0.74f, feet + 0.83f,
                0.06f, 0.05f, 0f, 0.32f, sin, cos, RM_WOOD_DK);
        }

        // ---------- Tail (two wooden cubes sticking out the back (-Z)) ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.55f, feet + 0.65f,
            0.06f, 0.10f, 0f, -0.28f, sin, cos, RM_TAIL1);
        pitchedBox(px, pz, feet, dPitch, feet + 0.57f, feet + 0.63f,
            0.045f, 0.16f, 0f, -0.50f, sin, cos, RM_TAIL2);

        // ---------- Head (brown fur box) ----------
        float headY0 = feet + 0.93f;
        float headY1 = feet + 1.28f;
        pitchedCube6(px, pz, feet, dPitch, headY0, headY1, 0.22f, 0.22f, 0f, 0.05f, sin, cos,
              darken(RM_FUR, 1.0f), darken(RM_FUR, 0.60f),
              makeHeadFace(hscale), darken(RM_FUR, 0.80f),
              makeHeadSide(true), makeHeadSide(false));

        // ---------- Ears (two flesh-coloured cubes above the head) ----------
        pitchedCube6(px, pz, feet, dPitch, headY1 - 0.02f, headY1 + 0.16f,
              0.07f, 0.07f, -0.14f, 0.11f, sin, cos,
              darken(RM_SKIN, 0.95f), darken(RM_SKIN, 0.70f),
              darken(RM_SKIN, 0.90f), darken(RM_SKIN, 0.90f),
              darken(RM_SKIN, 0.80f), darken(RM_SKIN, 0.80f));
        pitchedCube6(px, pz, feet, dPitch, headY1 - 0.02f, headY1 + 0.16f,
              0.07f, 0.07f,  0.14f, 0.11f, sin, cos,
              darken(RM_SKIN, 0.95f), darken(RM_SKIN, 0.70f),
              darken(RM_SKIN, 0.90f), darken(RM_SKIN, 0.90f),
              darken(RM_SKIN, 0.80f), darken(RM_SKIN, 0.80f));

        // ---------- Whiskers (little bars left/right, alternating W/B/W) ----------
        pitchedBox(px, pz, feet, dPitch, feet + 1.08f, feet + 1.11f,
            0.015f, 0.08f, -0.26f, 0.08f, sin, cos, RM_WHISK_W);
        pitchedBox(px, pz, feet, dPitch, feet + 1.04f, feet + 1.07f,
            0.015f, 0.085f, -0.26f, 0.08f, sin, cos, RM_WHISK_B);
        pitchedBox(px, pz, feet, dPitch, feet + 1.00f, feet + 1.03f,
            0.015f, 0.08f, -0.26f, 0.08f, sin, cos, RM_WHISK_W);
        pitchedBox(px, pz, feet, dPitch, feet + 1.08f, feet + 1.11f,
            0.015f, 0.08f,  0.26f, 0.08f, sin, cos, RM_WHISK_W);
        pitchedBox(px, pz, feet, dPitch, feet + 1.04f, feet + 1.07f,
            0.015f, 0.085f,  0.26f, 0.08f, sin, cos, RM_WHISK_B);
        pitchedBox(px, pz, feet, dPitch, feet + 1.00f, feet + 1.03f,
            0.015f, 0.08f,  0.26f, 0.08f, sin, cos, RM_WHISK_W);

        // ---------- Front-face details: eyes + nose ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 1.13f, feet + 1.21f,
              0.035f, 0.020f, -0.105f, 0.275f, sin, cos,
              darken(RM_EYE_G, 1.0f), darken(RM_EYE_G, 0.70f),
              darken(RM_EYE_G, 0.90f), darken(RM_MUZZLE, 0.90f),
              darken(RM_EYE_G, 0.85f), darken(RM_EYE_G, 0.85f));
        pitchedCube6(px, pz, feet, dPitch, feet + 1.13f, feet + 1.21f,
              0.035f, 0.020f, 0.105f, 0.275f, sin, cos,
              darken(RM_EYE_G, 1.0f), darken(RM_EYE_G, 0.70f),
              darken(RM_EYE_G, 0.90f), darken(RM_MUZZLE, 0.90f),
              darken(RM_EYE_G, 0.85f), darken(RM_EYE_G, 0.85f));
        pitchedBox(px, pz, feet, dPitch, feet + 1.185f, feet + 1.205f,
            0.010f, 0.005f, -0.085f, 0.280f, sin, cos, RM_EYE_W);
        pitchedBox(px, pz, feet, dPitch, feet + 1.185f, feet + 1.205f,
            0.010f, 0.005f,  0.085f, 0.280f, sin, cos, RM_EYE_W);

        pitchedCube6(px, pz, feet, dPitch, feet + 1.05f, feet + 1.10f,
              0.040f, 0.018f, 0f, 0.275f, sin, cos,
              darken(RM_NOSE, 0.95f), darken(RM_NOSE, 0.70f),
              darken(RM_NOSE, 1.00f), darken(RM_MUZZLE, 0.90f),
              darken(RM_NOSE, 0.88f), darken(RM_NOSE, 0.88f));

        // ---------- Side-view red eye patches ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 1.12f, feet + 1.20f,
              0.018f, 0.035f, -0.220f, 0.190f, sin, cos,
              darken(RM_EYE_R, 0.95f), darken(RM_EYE_R, 0.70f),
              darken(RM_FUR, 0.80f), darken(RM_FUR, 0.80f),
              darken(RM_EYE_R, 1.00f), darken(RM_FUR, 0.80f));
        pitchedCube6(px, pz, feet, dPitch, feet + 1.12f, feet + 1.20f,
              0.018f, 0.035f,  0.220f, 0.190f, sin, cos,
              darken(RM_EYE_R, 0.95f), darken(RM_EYE_R, 0.70f),
              darken(RM_FUR, 0.80f), darken(RM_FUR, 0.80f),
              darken(RM_FUR, 0.80f), darken(RM_EYE_R, 1.00f));
    }

    /**
     * Bones dropped by skeletons: camera-facing billboards on the 16x16
     * /bone.bmp sprite (palette index 0 = transparent), lit by the column
     * brightness at the bone's position.
     */
    public void renderBones(java.util.Vector bones, float alpha) {
        renderItemSprites(bones, boneTexture, alpha);
    }

    public void renderFeathers(java.util.Vector piles, float alpha) {
        renderItemSprites(piles, featherTexture, alpha);
    }

    public void renderTeeth(java.util.Vector piles, float alpha) {
        renderItemSprites(piles, toothTexture, alpha);
    }

    public void renderGunpowder(java.util.Vector piles, float alpha) {
        renderItemSprites(piles, gunpowderTexture, alpha);
    }

    public void renderHaizhuballs(java.util.Vector balls, float alpha) {
        renderItemSprites(balls, haizhuballTexture, alpha);
    }


    /** Shared billboard renderer for ground item drops (bones, slimeballs,
     *  gunpowder) -- all of them reuse the Skeleton.Bone entity. */
    private void renderItemSprites(java.util.Vector items, Texture tex, float alpha) {
        if (tex == null || items == null || items.size() == 0) return;
        beginBatch(cmdSprite, tex);
        float rx = camRightX, rz = camRightZ;
        for (int i = 0; i < items.size(); ++i) {
            Skeleton.Bone b = (Skeleton.Bone) items.elementAt(i);
            if (b == null || b.removed) continue;
            float px = b.xo + (b.x - b.xo) * alpha;
            float py = b.yo + (b.y - b.yo) * alpha;
            float pz = b.zo + (b.z - b.zo) * alpha;
            float s = 0.16f;
            int br = shade(level.getBrightness((int) px, (int) py, (int) pz));
            // partQuad vertex order: left-bottom, left-top, right-top,
            // right-bottom -- the sprite fills the whole 16x16 texture.
            partQuad(br,
                px - rx * s, py - s, pz - rz * s,
                px - rx * s, py + s, pz - rz * s,
                px + rx * s, py + s, pz + rz * s,
                px + rx * s, py - s, pz + rz * s,
                0, 0, 16, 16);
        }
        flushBatch();
        curCommand = cmdSprite;
        curTexture = texture;
    }

    // ---- HaiZhu: the dirt-cross-summoned pig head on a hovering carpet. ----
    private static final int HZ_CARPET  = 0x8C3B2E;  // dark red carpet
    private static final int HZ_CARPET2 = 0xC2543E;  // carpet top weave
    private static final int HZ_SKIN    = 0xF0A8A0;  // pig-pink head
    private static final int HZ_SNOUT   = 0xE29388;  // snout
    private static final int HZ_EYE     = 0x1A1A1A;  // eyes / nostrils
    private static final int HZ_GREEN   = 0x3FAE3F;  // the cube in its mouth

    public void renderHaiZhus(java.util.Vector mobs, float alpha) {
        if (mobs == null || mobs.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < mobs.size(); ++i) {
            HaiZhu m = (HaiZhu) mobs.elementAt(i);
            if (m == null || m.removed) continue;
            float ddx = m.x - camPX, ddy = m.y - camPY, ddz = m.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitHaiZhu(m, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void emitHaiZhu(HaiZhu m, float alpha) {
        float px = m.xo + (m.x - m.xo) * alpha;
        float py = m.yo + (m.y - m.yo) * alpha;
        float pz = m.zo + (m.z - m.zo) * alpha;

        float fx = m.headingX, fz = m.headingZ;
        float sin = -fx;
        float cos =  fz;

        // Death = continuous SHRINK around the carpet centre, to nothing.
        float s = m.scale();
        if (s <= 0.02f) return;

        // Gentle hover bob while alive; none while shrinking.
        float bob = m.dying ? 0f : (float) Math.sin(m.bob) * 0.06f;
        float feet = py + 0.10f + bob;

        // ---------- Carpet: ONE flat cube (its "feet", no legs) ----------
        box(px, pz, feet, feet + 0.10f * s, 0.34f * s, 0.34f * s, 0f, 0f, sin, cos, HZ_CARPET);
        // Weave pattern: a smaller flat box on the carpet top.
        box(px, pz, feet + 0.10f * s, feet + 0.13f * s, 0.21f * s, 0.21f * s, 0f, 0f, sin, cos, HZ_CARPET2);

        // ---------- Head: ONE big pig cube hovering over the carpet ----------
        float h0 = feet + 0.42f * s;
        float h1 = feet + 1.02f * s;
        pitchedCube6(px, pz, feet, 0f, h0, h1, 0.30f * s, 0.28f * s, 0f, 0f, sin, cos,
              HZ_SKIN, darken(HZ_SKIN, 0.60f),
              darken(HZ_SKIN, 0.95f), darken(HZ_SKIN, 0.80f),
              darken(HZ_SKIN, 0.80f), darken(HZ_SKIN, 0.80f));
        // Snout: proud flat box on the +Z face.
        box(px, pz, h0 + 0.22f * s, h0 + 0.42f * s, 0.13f * s, 0.045f * s, 0f, 0.315f * s, sin, cos, HZ_SNOUT);
        // Nostrils: two tiny dark cubes on the snout.
        box(px, pz, h0 + 0.26f * s, h0 + 0.36f * s, 0.022f * s, 0.012f * s, -0.06f * s, 0.362f * s, sin, cos, HZ_EYE);
        box(px, pz, h0 + 0.26f * s, h0 + 0.36f * s, 0.022f * s, 0.012f * s,  0.06f * s, 0.362f * s, sin, cos, HZ_EYE);
        // Eyes: two dark cubes above the snout.
        box(px, pz, h0 + 0.44f * s, h0 + 0.54f * s, 0.04f * s, 0.014f * s, -0.13f * s, 0.292f * s, sin, cos, HZ_EYE);
        box(px, pz, h0 + 0.44f * s, h0 + 0.54f * s, 0.04f * s, 0.014f * s,  0.13f * s, 0.292f * s, sin, cos, HZ_EYE);
        // The GREEN cube held in its mouth, below the snout.
        box(px, pz, h0 + 0.02f * s, h0 + 0.18f * s, 0.07f * s, 0.07f * s, 0f, 0.36f * s, sin, cos, HZ_GREEN);
    }

    // ---- YouJi Wang (the Rooster King): cobble-cross + ore summon. ----
    private static final int YJ_PINK  = 0xF0A0B0;  // head + body
    private static final int YJ_ORANGE = 0xE5892D; // beak + wings
    private static final int YJ_YELLOW = 0xE8C531; // feet
    private static final int YJ_COMB  = 0xE53935;  // the red crown
    private static final int YJ_EYE   = 0x1A1A1A;  // eyes

    public void renderYouJis(java.util.Vector mobs, float alpha) {
        if (mobs == null || mobs.size() == 0) return;
        // Bind the neutral WHITE texture: freej2me multiplies the face
        // colour by the bound texture's texel even for TEXURE_COORD_NONE
        // primitives (and null means "keep previous" there), which turned
        // the pink body olive-golden.  White leaves colours exact.
        beginBatch(cmdFlat, whiteTexture);
        for (int i = 0; i < mobs.size(); ++i) {
            YouJi m = (YouJi) mobs.elementAt(i);
            if (m == null || m.removed) continue;
            float ddx = m.x - camPX, ddy = m.y - camPY, ddz = m.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitYouJi(m, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void emitYouJi(YouJi m, float alpha) {
        float px = m.xo + (m.x - m.xo) * alpha;
        float py = m.yo + (m.y - m.yo) * alpha;
        float pz = m.zo + (m.z - m.zo) * alpha;

        // Death: rigid backward fall around the feet line.
        float dPitch = m.dying
                ? m.deathPitchPrev + (m.deathPitch - m.deathPitchPrev) * alpha
                : 0f;
        float sin = -m.headingX;
        float cos =  m.headingZ;
        float feet = py;

        // BEAST build (rooster silhouette): a HORIZONTAL body on short
        // legs, the head rises from the FRONT of the body (+Z = muzzle).

        // ---------- Feet: two short YELLOW legs under the body ----------
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.32f,
            0.04f, 0.04f, -0.10f, 0.05f, sin, cos, YJ_YELLOW);
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.32f,
            0.04f, 0.04f,  0.10f, 0.05f, sin, cos, YJ_YELLOW);

        // ---------- Body: ONE pink cube laid HORIZONTAL ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 0.30f, feet + 0.66f,
              0.22f, 0.30f, 0f, -0.02f, sin, cos,
              YJ_PINK, darken(YJ_PINK, 0.60f),
              darken(YJ_PINK, 0.95f), darken(YJ_PINK, 0.80f),
              darken(YJ_PINK, 0.80f), darken(YJ_PINK, 0.80f));

        // ---------- Wings: one flat ORANGE plate on each SIDE ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.34f, feet + 0.62f,
            0.035f, 0.20f, -0.25f, -0.05f, sin, cos, YJ_ORANGE);
        pitchedBox(px, pz, feet, dPitch, feet + 0.34f, feet + 0.62f,
            0.035f, 0.20f,  0.25f, -0.05f, sin, cos, YJ_ORANGE);

        // ---------- Head: pink cube rising from the body FRONT ----------
        float h0 = feet + 0.52f;
        float h1 = feet + 0.90f;
        pitchedCube6(px, pz, feet, dPitch, h0, h1, 0.17f, 0.17f, 0f, 0.34f, sin, cos,
              YJ_PINK, darken(YJ_PINK, 0.60f),
              darken(YJ_PINK, 0.95f), darken(YJ_PINK, 0.80f),
              darken(YJ_PINK, 0.80f), darken(YJ_PINK, 0.80f));

        // ---------- Beak: ORANGE cube poking forward of the head ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.62f, feet + 0.72f,
            0.055f, 0.05f, 0f, 0.55f, sin, cos, YJ_ORANGE);

        // ---------- Eyes: two dark cubes on the head sides ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.74f, feet + 0.82f,
            0.03f, 0.012f, -0.17f, 0.38f, sin, cos, YJ_EYE);
        pitchedBox(px, pz, feet, dPitch, feet + 0.74f, feet + 0.82f,
            0.03f, 0.012f,  0.17f, 0.38f, sin, cos, YJ_EYE);

        // ---------- Comb: the red crown on top of the head ----------
        pitchedBox(px, pz, feet, dPitch, h1, h1 + 0.08f,
            0.035f, 0.10f, 0f, 0.35f, sin, cos, YJ_COMB);
    }

    // ----- ZiShu Huang (the Rat Emperor) palette -----
    private static final int ZS_CREAM   = 0xE8E4D8; // head + body main
    private static final int ZS_BELLY   = 0xF2EEE0; // belly plate
    private static final int ZS_GREY    = 0xD8D4C8; // legs
    private static final int ZS_GREY2   = 0xB0ACA0; // arms
    private static final int ZS_EAR_OUT = 0xD2A66A; // ears + feet
    private static final int ZS_EAR_IN  = 0xE8A0A8; // inner ear pink
    private static final int ZS_PINK    = 0xE87C8C; // nose
    private static final int ZS_TAIL    = 0xE8A0A0; // segmented tail
    private static final int ZS_GOLD    = 0xF2C230; // crown spikes + emblem
    private static final int ZS_GOLD_D  = 0xE0A818; // crown base
    private static final int ZS_RED     = 0xD02818; // banner + gem
    private static final int ZS_BLACK   = 0x1A1A1A; // eyes
    private static final int ZS_WHISKER = 0xF0EDE2; // whiskers
    private static final int ZS_POLE    = 0x8A8578; // banner pole

    public void renderZiShus(java.util.Vector mobs, float alpha) {
        if (mobs == null || mobs.size() == 0) return;
        // Bind the neutral WHITE texture (see setWhiteTexture) so the
        // freej2me texel-multiplication quirk keeps colours exact.
        beginBatch(cmdFlat, whiteTexture);
        for (int i = 0; i < mobs.size(); ++i) {
            ZiShu m = (ZiShu) mobs.elementAt(i);
            if (m == null || m.removed) continue;
            float ddx = m.x - camPX, ddy = m.y - camPY, ddz = m.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitZiShu(m, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void emitZiShu(ZiShu m, float alpha) {
        float px = m.xo + (m.x - m.xo) * alpha;
        float py = m.yo + (m.y - m.yo) * alpha;
        float pz = m.zo + (m.z - m.zo) * alpha;

        // Death: rigid FORWARD fall around the feet line (the emperor's
        // final kowtow) -- YouJi falls BACKWARD, so the pitch is negated.
        float dPitch = m.dying
                ? -(m.deathPitchPrev + (m.deathPitch - m.deathPitchPrev) * alpha)
                : 0f;
        float sin = -m.headingX;
        float cos =  m.headingZ;
        float feet = py;

        // ---------- Legs + pale feet ----------
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.30f,
            0.045f, 0.045f, -0.09f, 0f, sin, cos, ZS_GREY);
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.30f,
            0.045f, 0.045f, 0.09f, 0f, sin, cos, ZS_GREY);
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.10f,
            0.055f, 0.06f, -0.09f, 0.03f, sin, cos, ZS_EAR_OUT);
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.10f,
            0.055f, 0.06f, 0.09f, 0.03f, sin, cos, ZS_EAR_OUT);

        // ---------- Body (upright) + belly plate + gold emblem ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 0.28f, feet + 0.72f,
              0.20f, 0.16f, 0f, 0f, sin, cos,
              ZS_CREAM, darken(ZS_CREAM, 0.60f),
              darken(ZS_CREAM, 0.95f), darken(ZS_CREAM, 0.80f),
              darken(ZS_CREAM, 0.80f), darken(ZS_CREAM, 0.80f));
        pitchedBox(px, pz, feet, dPitch, feet + 0.34f, feet + 0.66f,
            0.11f, 0.02f, 0f, 0.175f, sin, cos, ZS_BELLY);
        pitchedBox(px, pz, feet, dPitch, feet + 0.52f, feet + 0.60f,
            0.045f, 0.015f, 0f, 0.19f, sin, cos, ZS_GOLD);

        // ---------- Arms ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.44f, feet + 0.68f,
            0.05f, 0.06f, -0.245f, 0f, sin, cos, ZS_GREY2);
        pitchedBox(px, pz, feet, dPitch, feet + 0.44f, feet + 0.68f,
            0.05f, 0.06f, 0.245f, 0f, sin, cos, ZS_GREY2);

        // ---------- Head: BIG cream cube ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 0.72f, feet + 1.16f,
              0.24f, 0.22f, 0f, 0f, sin, cos,
              ZS_CREAM, darken(ZS_CREAM, 0.60f),
              darken(ZS_CREAM, 0.95f), darken(ZS_CREAM, 0.80f),
              darken(ZS_CREAM, 0.80f), darken(ZS_CREAM, 0.80f));

        // ---------- Ears: sand outer plate + pink inner ----------
        pitchedBox(px, pz, feet, dPitch, feet + 1.16f, feet + 1.31f,
            0.09f, 0.055f, -0.16f, -0.02f, sin, cos, ZS_EAR_OUT);
        pitchedBox(px, pz, feet, dPitch, feet + 1.16f, feet + 1.31f,
            0.09f, 0.055f, 0.16f, -0.02f, sin, cos, ZS_EAR_OUT);
        pitchedBox(px, pz, feet, dPitch, feet + 1.19f, feet + 1.28f,
            0.05f, 0.015f, -0.16f, 0.038f, sin, cos, ZS_EAR_IN);
        pitchedBox(px, pz, feet, dPitch, feet + 1.19f, feet + 1.28f,
            0.05f, 0.015f, 0.16f, 0.038f, sin, cos, ZS_EAR_IN);

        // ---------- Eyes ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.98f, feet + 1.06f,
            0.035f, 0.013f, -0.10f, 0.222f, sin, cos, ZS_BLACK);
        pitchedBox(px, pz, feet, dPitch, feet + 0.98f, feet + 1.06f,
            0.035f, 0.013f, 0.10f, 0.222f, sin, cos, ZS_BLACK);

        // ---------- Snout + pink nose ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.84f, feet + 0.98f,
            0.07f, 0.045f, 0f, 0.26f, sin, cos, ZS_CREAM);
        pitchedBox(px, pz, feet, dPitch, feet + 0.91f, feet + 0.98f,
            0.035f, 0.02f, 0f, 0.315f, sin, cos, ZS_PINK);

        // ---------- Whiskers: two per side ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.885f, feet + 0.90f,
            0.055f, 0.008f, -0.13f, 0.27f, sin, cos, ZS_WHISKER);
        pitchedBox(px, pz, feet, dPitch, feet + 0.945f, feet + 0.96f,
            0.055f, 0.008f, -0.13f, 0.27f, sin, cos, ZS_WHISKER);
        pitchedBox(px, pz, feet, dPitch, feet + 0.885f, feet + 0.90f,
            0.055f, 0.008f, 0.13f, 0.27f, sin, cos, ZS_WHISKER);
        pitchedBox(px, pz, feet, dPitch, feet + 0.945f, feet + 0.96f,
            0.055f, 0.008f, 0.13f, 0.27f, sin, cos, ZS_WHISKER);

        // ---------- Gold crown: base + three spikes + red gem ----------
        pitchedBox(px, pz, feet, dPitch, feet + 1.16f, feet + 1.22f,
            0.14f, 0.11f, 0f, -0.01f, sin, cos, ZS_GOLD_D);
        pitchedBox(px, pz, feet, dPitch, feet + 1.22f, feet + 1.34f,
            0.03f, 0.03f, 0f, 0.06f, sin, cos, ZS_GOLD);
        pitchedBox(px, pz, feet, dPitch, feet + 1.22f, feet + 1.34f,
            0.03f, 0.03f, -0.09f, -0.01f, sin, cos, ZS_GOLD);
        pitchedBox(px, pz, feet, dPitch, feet + 1.22f, feet + 1.34f,
            0.03f, 0.03f, 0.09f, -0.01f, sin, cos, ZS_GOLD);
        pitchedBox(px, pz, feet, dPitch, feet + 1.26f, feet + 1.30f,
            0.02f, 0.015f, 0f, 0.098f, sin, cos, ZS_RED);

        // ---------- Tail: three pink segments curling behind ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.30f, feet + 0.44f,
            0.035f, 0.045f, 0f, -0.19f, sin, cos, ZS_TAIL);
        pitchedBox(px, pz, feet, dPitch, feet + 0.44f, feet + 0.58f,
            0.035f, 0.045f, 0f, -0.27f, sin, cos, ZS_TAIL);
        pitchedBox(px, pz, feet, dPitch, feet + 0.58f, feet + 0.70f,
            0.035f, 0.045f, 0f, -0.23f, sin, cos, ZS_TAIL);

        // ---------- Banner: pole in the right hand + red-gold flag ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.25f, feet + 1.15f,
            0.02f, 0.02f, 0.27f, 0.10f, sin, cos, ZS_POLE);
        pitchedBox(px, pz, feet, dPitch, feet + 0.88f, feet + 1.14f,
            0.07f, 0.015f, 0.36f, 0.10f, sin, cos, ZS_RED);
        pitchedBox(px, pz, feet, dPitch, feet + 1.00f, feet + 1.06f,
            0.065f, 0.018f, 0.36f, 0.10f, sin, cos, ZS_GOLD);
    }

    // ----- Forward (the v1 finale monster) palette -----
    private static final int FW_DARK   = 0x2B2B2B; // charcoal body
    private static final int FW_DARK2  = 0x1E1E1E; // legs / shading
    private static final int FW_CRACK  = 0x7A1010; // crimson energy crack
    private static final int FW_EYE    = 0xE02020; // glowing red eyes
    private static final int FW_CROWN  = 0x5A0A0A; // dark red head plate

    public void renderForwards(java.util.Vector mobs, float alpha) {
        if (mobs == null || mobs.size() == 0) return;
        // Neutral WHITE texture so freej2me keeps the colours exact.
        beginBatch(cmdFlat, whiteTexture);
        for (int i = 0; i < mobs.size(); ++i) {
            Forward m = (Forward) mobs.elementAt(i);
            if (m == null || m.removed) continue;
            float ddx = m.x - camPX, ddy = m.y - camPY, ddz = m.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 28f * 28f) continue;
            emitForward(m, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void emitForward(Forward m, float alpha) {
        float px = m.xo + (m.x - m.xo) * alpha;
        float py = m.yo + (m.y - m.yo) * alpha;
        float pz = m.zo + (m.z - m.zo) * alpha;

        float dPitch = m.dying
                ? m.deathPitchPrev + (m.deathPitch - m.deathPitchPrev) * alpha
                : 0f;
        // While alive the lunge dips the whole pillar forward a little.
        if (!m.dying) dPitch = -m.lungePitch(alpha);
        float sin = -m.headingX;
        float cos =  m.headingZ;
        float feet = py;

        // ---------- Legs: two stubby charcoal cubes ----------
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.24f,
            0.05f, 0.05f, -0.08f, 0f, sin, cos, FW_DARK2);
        pitchedBox(px, pz, feet, dPitch, feet, feet + 0.24f,
            0.05f, 0.05f, 0.08f, 0f, sin, cos, FW_DARK2);

        // ---------- Body: ONE tall charcoal pillar ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 0.24f, feet + 1.55f,
              0.22f, 0.22f, 0f, 0f, sin, cos,
              FW_DARK, darken(FW_DARK, 0.60f),
              darken(FW_DARK, 0.95f), darken(FW_DARK, 0.80f),
              darken(FW_DARK, 0.80f), darken(FW_DARK, 0.80f));

        // ---------- Crimson energy crack: three dashes up the front ----------
        pitchedBox(px, pz, feet, dPitch, feet + 0.34f, feet + 0.62f,
            0.03f, 0.012f, 0f, 0.225f, sin, cos, FW_CRACK);
        pitchedBox(px, pz, feet, dPitch, feet + 0.80f, feet + 1.10f,
            0.03f, 0.012f, 0f, 0.225f, sin, cos, FW_CRACK);
        pitchedBox(px, pz, feet, dPitch, feet + 1.26f, feet + 1.48f,
            0.03f, 0.012f, 0f, 0.225f, sin, cos, FW_CRACK);

        // ---------- Head: slightly narrower charcoal cube ----------
        pitchedCube6(px, pz, feet, dPitch, feet + 1.55f, feet + 2.00f,
              0.20f, 0.20f, 0f, 0f, sin, cos,
              FW_DARK, darken(FW_DARK, 0.60f),
              darken(FW_DARK, 0.95f), darken(FW_DARK, 0.80f),
              darken(FW_DARK, 0.80f), darken(FW_DARK, 0.80f));

        // ---------- Eyes: two GLOWING red slits ----------
        pitchedBox(px, pz, feet, dPitch, feet + 1.76f, feet + 1.86f,
            0.04f, 0.014f, -0.08f, 0.202f, sin, cos, FW_EYE);
        pitchedBox(px, pz, feet, dPitch, feet + 1.76f, feet + 1.86f,
            0.04f, 0.014f, 0.08f, 0.202f, sin, cos, FW_EYE);

        // ---------- Crown: dark red plate on the head top ----------
        pitchedBox(px, pz, feet, dPitch, feet + 2.00f, feet + 2.10f,
            0.14f, 0.12f, 0f, -0.01f, sin, cos, FW_CROWN);
    }

    /**
     * Slimeball drops (the slime's loot): billboards on /slimeball.bmp,
     * same pipeline as the bones -- the entities reuse Skeleton.Bone.
     */
    public void renderSlimeBalls(java.util.Vector balls, float alpha) {
        renderItemSprites(balls, slimeballTexture, alpha);
    }

    // ---- Skeleton ----
    //
    // Box model in the RedMouse style: ONE head cube (with tiny black eye /
    // mouth detail cubes proud of the face), ONE thin body cube (with two
    // dark rib strips on the chest), thin limb cubes, and a ONE-cube iron
    // sword (plus a small wooden guard) held out front.  Same local frame:
    // (0,0,0) = centre, feet on the floor, +Z = heading, +X = right.
    private static final int SK_BONE   = 0xC8C8C8;  // head/body light bone gray
    private static final int SK_BONE_D = 0xAAAAAA;  // arms/legs slightly darker
    private static final int SK_DARK   = 0x2B2B2B;  // eyes/mouth/rib shadows
    private static final int SK_BLADE  = 0xDDDDDD;  // iron blade
    private static final int SK_GUARD  = 0x7A5230;  // wooden sword guard

    /**
     * One sword piece while ALIVE: pivots around the grip line
     * (y = feet+0.74, z = 0.12) by the swing pitch so the blade sweeps
     * down toward the player mid-swing.  Positive pitch tips the forward
     * (+Z) end UP, matching Skeleton.swordPitch()'s convention.
     */
    private void pivotBox(float cx, float cz, float pivotY, float pivotZ,
                          float pitch,
                          float y0, float y1, float hw, float hd,
                          float ox, float zswing,
                          float sin, float cos, int color) {
        if (pitch == 0f) {
            box(cx, cz, y0, y1, hw, hd, ox, zswing, sin, cos, color);
            return;
        }
        float cp = (float) Math.cos(pitch), sp = -(float) Math.sin(pitch);
        pitchCorners(cx, cz, pivotY, pivotZ, 0f, cp, sp,
                     ox - hw, ox + hw, y0, y1, zswing - hd, zswing + hd,
                     sin, cos);
        int sideC = darken(color, 0.80f);
        int botC  = darken(color, 0.60f);
        emitScratchCube6(color, botC, sideC, sideC, sideC, sideC);
    }

    // Debris pieces for the fall-apart death, indexed like Skeleton.PART_O*:
    // 0 head, 1 body, 2/3 legs, 4/5 arms, 6 sword blade.  Half-sizes + colour.
    private static final float[] SK_PART_HW = { 0.20f,   0.13f,   0.055f, 0.055f, 0.045f, 0.045f, 0.032f };
    private static final float[] SK_PART_HH = { 0.21f,   0.225f,  0.275f, 0.275f, 0.20f,  0.20f,  0.06f  };
    private static final float[] SK_PART_HD = { 0.20f,   0.075f,  0.055f, 0.055f, 0.05f,  0.05f,  0.30f  };
    private static final int[]   SK_PART_C  = { SK_BONE, SK_BONE, SK_BONE_D, SK_BONE_D, SK_BONE_D, SK_BONE_D, SK_BLADE };

    /**
     * One debris piece: a small box tumbling around its OWN centre -- pitch
     * in its local frame first, then the yaw -- used when the skeleton
     * falls apart on death.
     */
    private void tumbleBox(float wx, float wy, float wz,
                           float hw, float hh, float hd,
                           float yaw, float pitch, int color) {
        float sin = -(float) Math.sin(yaw);
        float cos =  (float) Math.cos(yaw);
        float cp  =  (float) Math.cos(pitch);
        float sp  = -(float) Math.sin(pitch);
        pitchCorners(wx, wz, wy, 0f, 0f, cp, sp,
                     -hw, hw, wy - hh, wy + hh, -hd, hd, sin, cos);
        int sideC = darken(color, 0.80f);
        emitScratchCube6(color, darken(color, 0.60f), sideC, sideC, sideC, sideC);
    }

    private void emitSkeleton(Skeleton s, float alpha) {
        float px = s.xo + (s.x - s.xo) * alpha;
        float py = s.yo + (s.y - s.yo) * alpha;
        float pz = s.zo + (s.z - s.zo) * alpha;

        // Death = falls apart: the assembled model is replaced by independent
        // tumbling boxes (head/body/limbs/sword) popping and scattering.
        if (s.dying) {
            for (int i = 0; i < Skeleton.PARTS; ++i) {
                tumbleBox(s.partX(i, alpha), s.partY(i, alpha), s.partZ(i, alpha),
                          SK_PART_HW[i], SK_PART_HH[i], SK_PART_HD[i],
                          s.partYaw(i, alpha), s.partPitch(i, alpha),
                          SK_PART_C[i]);
            }
            return;
        }

        float fx = s.headingX, fz = s.headingZ;
        float sin = -fx;
        float cos =  fz;
        float feet = py;

        float bob = s.walkBob;
        float legFwd = (float) Math.sin(bob) * 0.16f;
        float legBwd = -legFwd;

        // ---------- Legs (two thin bone columns) ----------
        pitchedBox(px, pz, feet, 0f, feet, feet + 0.55f,
            0.055f, 0.055f, -0.075f, legFwd, sin, cos, SK_BONE_D);
        pitchedBox(px, pz, feet, 0f, feet, feet + 0.55f,
            0.055f, 0.055f,  0.075f, legBwd, sin, cos, SK_BONE_D);

        // ---------- Arms (thin bones; the right one holds the sword) ----------
        pitchedBox(px, pz, feet, 0f, feet + 0.58f, feet + 0.98f,
            0.045f, 0.05f, -0.17f, 0f, sin, cos, SK_BONE_D);
        pitchedBox(px, pz, feet, 0f, feet + 0.58f, feet + 0.98f,
            0.045f, 0.05f,  0.17f, 0.04f, sin, cos, SK_BONE_D);

        // ---------- Body: ONE cube (the column from hips to shoulders) ----------
        pitchedCube6(px, pz, feet, 0f, feet + 0.55f, feet + 1.00f,
              0.13f, 0.075f, 0f, 0f, sin, cos,
              SK_BONE, darken(SK_BONE, 0.60f),
              darken(SK_BONE, 0.88f), darken(SK_BONE, 0.80f),
              darken(SK_BONE, 0.80f), darken(SK_BONE, 0.80f));
        // Rib strips: two dark slats proud of the chest.
        pitchedBox(px, pz, feet, 0f, feet + 0.64f, feet + 0.68f,
            0.10f, 0.012f, 0f, 0.085f, sin, cos, SK_DARK);
        pitchedBox(px, pz, feet, 0f, feet + 0.76f, feet + 0.80f,
            0.10f, 0.012f, 0f, 0.085f, sin, cos, SK_DARK);

        // ---------- Head: ONE big cube ----------
        pitchedCube6(px, pz, feet, 0f, feet + 1.00f, feet + 1.42f,
              0.20f, 0.20f, 0f, 0f, sin, cos,
              SK_BONE, darken(SK_BONE, 0.60f),
              darken(SK_BONE, 0.95f), darken(SK_BONE, 0.80f),
              darken(SK_BONE, 0.80f), darken(SK_BONE, 0.80f));
        // Face: two black eye cubes + a wide mouth cube, proud of the +Z face.
        pitchedBox(px, pz, feet, 0f, feet + 1.24f, feet + 1.32f,
            0.045f, 0.012f, -0.085f, 0.205f, sin, cos, SK_DARK);
        pitchedBox(px, pz, feet, 0f, feet + 1.24f, feet + 1.32f,
            0.045f, 0.012f,  0.085f, 0.205f, sin, cos, SK_DARK);
        pitchedBox(px, pz, feet, 0f, feet + 1.08f, feet + 1.13f,
            0.062f, 0.012f,  0f, 0.205f, sin, cos, SK_DARK);

        // ---------- Iron sword: ONE blade cube + wooden guard, held out in
        // ---------- front of the right arm, pivoting around the grip with
        // ---------- the swing.  On death it flies off as debris part 6. ----------
        float swPitch = s.swordPitch(alpha);
        pivotBox(px, pz, feet + 0.74f, 0.12f, swPitch,
            feet + 0.68f, feet + 0.80f, 0.032f, 0.30f, 0.17f, 0.18f,
            sin, cos, SK_BLADE);
        pivotBox(px, pz, feet + 0.74f, 0.12f, swPitch,
            feet + 0.70f, feet + 0.78f, 0.085f, 0.03f, 0.17f, -0.10f,
            sin, cos, SK_GUARD);
    }

    // Since we only have whole-face colours, we build the head "face" out of
    // many small stacked sub-cubes in a second pass would be ideal but that's
    // 30+ extra boxes per mouse.  For J2ME we compromise: treat the head front
    // face as the muzzle grey, then overlay four tiny boxes on top for the
    // eyes, nose and whiskers.
    private int makeHeadFace(float hscale) {
        // This returns the base front-face colour (the light muzzle rectangle).
        // The eyes, nose, and whiskers get drawn as separate small boxes so we
        // can still show the distinct shapes (see the overlay boxes in emitRedMouse).
        return darken(RM_MUZZLE, 0.95f);
    }
    private int makeHeadSide(boolean isLeftSide) {
        // Side-view face: the reference render shows a single red "eye" patch
        // on the visible side when looking from the left/right.  Here we use
        // the fur colour because the side view face is actually pure fur with
        // just one red-pixel detail; the dedicated eye/ear overlays cover it.
        return darken(RM_FUR, 0.82f);
    }


    /**
     * Draw one slime as a yaw-rotated cube, sampling each face from the
     * 3x2 slime sheet: front/up/down on the top row, left/right/back below.
     */
    private void emitSlime(Slime s, float alpha) {
        float px = s.xo + (s.x - s.xo) * alpha;
        float py = s.yo + (s.y - s.yo) * alpha;
        float pz = s.zo + (s.z - s.zo) * alpha;
        float h = Slime.HALF;
        // Jelly body: stretch tall when hopping, squash flat when landing or
        // dying; width bulges the opposite way to keep the volume lively.
        float form = s.squash * s.stretch;
        float body = h * 2.0f * form;
        float w = h * (1.0f + (1.0f - form) * 0.5f);
        float y0 = py, y1 = py + body;

        // Model local +Z (its front) faces (headingX, headingZ).
        // Right axis (local +X) is the perpendicular (rotate heading +90 deg).
        float hx = s.headingX;
        float hz = s.headingZ;
        float rx = -hz, rz = hx;   // local +X (right) in world space
        float fx =  hx, fz = hz;   // local +Z (front/heading) in world space

        // Four base corners with consistent local labels:
        //   a = local (-x, -z)  back-left
        //   b = local (+x, -z)  back-right
        //   c = local (+x, +z)  front-right
        //   d = local (-x, +z)  front-left
        float ax = px + (-w) * rx + (-w) * fx,   az = pz + (-w) * rz + (-w) * fz;
        float bx = px + ( w) * rx + (-w) * fx,   bz = pz + ( w) * rz + (-w) * fz;
        float cx = px + ( w) * rx + ( w) * fx,   cz = pz + ( w) * rz + ( w) * fz;
        float dx = px + (-w) * rx + ( w) * fx,   dz = pz + (-w) * rz + ( w) * fz;

        float sky = level.getBrightness((int) px, (int) (py + h), (int) pz);
        int topB   = shade(faceBrightness(0.0f, 1.0f, 0.0f, sky));
        int botB   = shade(faceBrightness(0.0f, -1.0f, 0.0f, sky));
        int frontB = shade(faceBrightness(fx, 0.0f, fz, sky));
        int backB  = shade(faceBrightness(-fx, 0.0f, -fz, sky));
        int leftB  = shade(faceBrightness(-rx, 0.0f, -rz, sky));
        int rightB = shade(faceBrightness(rx, 0.0f, rz, sky));

        int t = SLIME_TILE;
        // partQuad convention:
        //   Vertices  a-b-c-d  (left-bot -> left-top -> right-top -> right-bot)
        //   must form a CCW quad as seen from OUTSIDE the face.
        //   UV mapping:  a->(u0,v1), b->(u0,v0), c->(u1,v0), d->(u1,v1).
        //   When a face is viewed from outside, "left/top" refer to the viewer,
        //   so u increases along the viewer's right and v increases along viewer's down.
        //
        // Layout of the 3x2 8px-cell sheet:
        //   row 0 (v 0..t):     col 0 front,   col 1 top,     col 2 bottom
        //   row 1 (v t..2t):    col 0 left,    col 1 right,   col 2 back
        //
        // Texture direction per face: "texture top (smaller v)" -> model +Z (front)
        // ========================================================================

        // ---- Front face (local +Z, viewer at +Z looking back toward -Z) ----
        // Viewer left  = model front-left  (d = -x, +z)
        // Viewer right = model front-right (c = +x, +z)
        // cell (0,0): u in [0,t], v in [0,t];  v=0 (tex top) -> y1 viewer top
        partQuad(frontB, dx, y0, dz,   dx, y1, dz,   cx, y1, cz,   cx, y0, cz,
                 0, 0, t, t);

        // ---- Back face (local -Z, viewer at -Z looking forward toward +Z) ----
        // Viewer left  = model back-right (b = +x, -z)  (model R mirrors to viewer L)
        // Viewer right = model back-left  (a = -x, -z)  (model L mirrors to viewer R)
        // cell (2,1): u in [2t,3t], v in [t,2t]
        partQuad(backB,  bx, y0, bz,   bx, y1, bz,   ax, y1, az,   ax, y0, az,
                 2 * t, t, 3 * t, 2 * t);

        // ---- Left face (local -X, viewer at -X looking right toward +X) ----
        // Viewer up   = +Y;  viewer forward = +X
        // Viewer right = forward x up = (+X) x (+Y) = +Z -> model front (dx)
        // Viewer left  = -Z -> model back (ax)
        // cell (0,1): u in [0,t], v in [t,2t]
        partQuad(leftB,  ax, y0, az,   ax, y1, az,   dx, y1, dz,   dx, y0, dz,
                 0, t, t, 2 * t);

        // ---- Right face (local +X, viewer at +X looking left toward -X) ----
        // Viewer forward = -X, viewer up = +Y
        // Viewer right = (-X) x (+Y) = -Z -> model back (bx)
        // Viewer left  = +Z -> model front (cx)
        // cell (1,1): u in [t,2t], v in [t,2t]
        partQuad(rightB, cx, y0, cz,   cx, y1, cz,   bx, y1, bz,   bx, y0, bz,
                 t, t, 2 * t, 2 * t);

        // ---- Top face (local +Y, viewer at +Y looking DOWN toward -Y) ----
        // Viewer forward = -Y, viewer up = +Z (tex top -> model front)
        // Viewer right = (-Y) x (+Z) = -X -> model left
        // Viewer left  = +X -> model right
        //   viewer left-bot  (a) = (+X, -Z) = back-right (bx) -> (u0, v1) tex (2t, t)
        //   viewer left-top  (b) = (+X, +Z) = front-right (cx) -> (u0, v0) tex (2t, 0)
        //   viewer right-top (c) = (-X, +Z) = front-left  (dx) -> (u1, v0) tex (t, 0)
        //   viewer right-bot (d) = (-X, -Z) = back-left   (ax) -> (u1, v1) tex (t, t)
        // cell (1,0): u in [t,2t] (viewer R/L mirrors so u0=2t,u1=t)
        partQuad(topB,   bx, y1, bz,   cx, y1, cz,   dx, y1, dz,   ax, y1, az,
                 2 * t, 0, t, t);

        // ---- Bottom face (local -Y, viewer at -Y looking UP toward +Y) ----
        // Viewer forward = +Y, viewer up = +Z (tex top -> model front)
        // Viewer right = (+Y) x (+Z) = +X -> model right
        // Viewer left  = -X -> model left
        //   viewer left-bot  (a) = (-X, -Z) = back-left  (ax) -> (u0, v1) tex (2t, t)
        //   viewer left-top  (b) = (-X, +Z) = front-left (dx) -> (u0, v0) tex (2t, 0)
        //   viewer right-top (c) = (+X, +Z) = front-right (cx) -> (u1, v0) tex (3t, 0)
        //   viewer right-bot (d) = (+X, -Z) = back-right  (bx) -> (u1, v1) tex (3t, t)
        // cell (2,0): u in [2t,3t], v in [0,t]
        partQuad(botB,   ax, y0, az,   dx, y0, dz,   cx, y0, cz,   bx, y0, bz,
                 2 * t, 0, 3 * t, t);
    }

    private void partQuad(int color,
                          float ax, float ay, float az,
                          float bx, float by, float bz,
                          float cx, float cy, float cz,
                          float dx, float dy, float dz,
                          int u0, int v0, int u1, int v1) {
        if (quadCount >= BATCH) flushBatch();
        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);
        int ti = quadCount * 8;
        tc[ti]     = u0; tc[ti + 1] = v1;
        tc[ti + 2] = u0; tc[ti + 3] = v0;
        tc[ti + 4] = u1; tc[ti + 5] = v0;
        tc[ti + 6] = u1; tc[ti + 7] = v1;
        col[quadCount] = color;
        quadCount++;
    }

    public void renderHit(HitResult h) {
        if (h == null) return;
        // 12 edges of the unit cube at (h.x,h.y,h.z), slightly inflated so
        // the outline sits just outside the block faces (avoids z-fighting).
        float e = 0.003f;
        float x0 = h.x - e, y0 = h.y - e, z0 = h.z - e;
        float x1 = h.x + 1 + e, y1 = h.y + 1 + e, z1 = h.z + 1 + e;

        // 12 segments * 2 verts * 3 coords
        int[] v = new int[12 * 2 * 3];
        int i = 0;
        i = edge(v, i, x0,y0,z0, x1,y0,z0);
        i = edge(v, i, x1,y0,z0, x1,y0,z1);
        i = edge(v, i, x1,y0,z1, x0,y0,z1);
        i = edge(v, i, x0,y0,z1, x0,y0,z0);
        i = edge(v, i, x0,y1,z0, x1,y1,z0);
        i = edge(v, i, x1,y1,z0, x1,y1,z1);
        i = edge(v, i, x1,y1,z1, x0,y1,z1);
        i = edge(v, i, x0,y1,z1, x0,y1,z0);
        i = edge(v, i, x0,y0,z0, x0,y1,z0);
        i = edge(v, i, x1,y0,z0, x1,y1,z0);
        i = edge(v, i, x1,y0,z1, x1,y1,z1);
        i = edge(v, i, x0,y0,z1, x0,y1,z1);

        int[] colors = new int[12];
        for (int c = 0; c < 12; ++c) colors[c] = 0x000000;

        int cmd = Graphics3D.PRIMITVE_LINES |
                  Graphics3D.PDATA_NORMAL_NONE |
                  Graphics3D.PDATA_TEXURE_COORD_NONE |
                  Graphics3D.PDATA_COLOR_PER_FACE |
                  Graphics3D.PATTR_BLEND_NORMAL;
        try {
            g3d.renderPrimitives(texture, 0, 0, layout, effectOpaque,
                cmd, 12, v, nrm, null, colors);
        } catch (Throwable t) {
            // Some devices may not support line primitives; ignore.
        }
    }

    private int edge(int[] v, int i, float ax, float ay, float az,
                                     float bx, float by, float bz) {
        v[i++] = (int) (ax * BLOCK); v[i++] = (int) (ay * BLOCK); v[i++] = (int) (az * BLOCK);
        v[i++] = (int) (bx * BLOCK); v[i++] = (int) (by * BLOCK); v[i++] = (int) (bz * BLOCK);
        return i;
    }
}
