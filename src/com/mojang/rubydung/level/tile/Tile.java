package com.mojang.rubydung.level.tile;

import com.mojang.rubydung.level.ILevel;
import com.mojang.rubydung.phys.AABB;

import java.util.Random;

/**
 * Table-driven block registry, ported from ClassiCube's Block.c
 * (core_blockDefs). One Tile instance per block id holds the per-face
 * texture atlas indices, draw mode, collide mode, height, and flags.
 *
 * Draw modes (matching ClassiCube):
 *   0 OPAQUE   1 TRANSPARENT (glass, alpha-test)
 *   2 TRANSPARENT_THICK (leaves)  3 TRANSLUCENT (water, ice)
 *   4 GAS (air, no geometry)      5 SPRITE (crossed quads: plants)
 *
 * Collide modes:
 *   0 NONE  1 SOLID  2 WATER  3 LAVA  4 CLIMB
 *
 * Faces (as used by WorldRenderer): 0 bottom, 1 top, 2 z-, 3 z+, 4 x-, 5 x+.
 */
public class Tile {
    public static final int DRAW_OPAQUE = 0;
    public static final int DRAW_TRANSPARENT = 1;
    public static final int DRAW_TRANSPARENT_THICK = 2;
    public static final int DRAW_TRANSLUCENT = 3;
    public static final int DRAW_GAS = 4;
    public static final int DRAW_SPRITE = 5;

    public static final int COLLIDE_NONE = 0;
    public static final int COLLIDE_SOLID = 1;
    public static final int COLLIDE_WATER = 2;
    public static final int COLLIDE_LAVA = 3;
    public static final int COLLIDE_CLIMB = 4;

    public static final Tile[] tiles = new Tile[256];

    public final int id;
    public final String name;
    public final int topTex, sideTex, bottomTex;
    public final int height;       // 0..16 (16 = full cube)
    public final int draw;
    public final int collide;
    public final boolean blocksLight;
    public final boolean gravity;

    // Frequently-used named blocks (referenced by generator / behaviour).
    public static Tile rock, grass, dirt, cobble, wood, sapling, bedrock;
    public static Tile water, stillWater, lava, stillLava, sand, gravel;
    public static Tile log, leaves, sponge, glass, sandstone, dandelion, rose, rope;
    public static Tile coalOre, ironOre, goldOre;
    public static Tile brownMushroom, redMushroom, slab, doubleSlab, snow;

    private Tile(int id, String name, int topTex, int sideTex, int bottomTex,
                 int height, int draw, int collide, boolean blocksLight, boolean gravity) {
        this.id = id;
        this.name = name;
        this.topTex = topTex;
        this.sideTex = sideTex;
        this.bottomTex = bottomTex;
        this.height = height;
        this.draw = draw;
        this.collide = collide;
        this.blocksLight = blocksLight;
        this.gravity = gravity;
        tiles[id] = this;
    }

    private static void def(int id, String name, int topTex, int sideTex, int bottomTex,
                            int height, int draw, int collide, boolean blocksLight, boolean gravity) {
        new Tile(id, name, topTex, sideTex, bottomTex, height, draw, collide, blocksLight, gravity);
    }

    static {
        // Generated from ClassiCube core_blockDefs (Classic + CPE block set).
        def(0, "Air", 0,0,0, 16, 4,0, false,false);
        def(1, "Stone", 1,1,1, 16, 0,1, true,false);
        def(2, "Grass", 0,3,2, 16, 0,1, true,false);
        def(3, "Dirt", 2,2,2, 16, 0,1, true,false);
        def(4, "Cobblestone", 16,16,16, 16, 0,1, true,false);
        def(5, "Wood", 4,4,4, 16, 0,1, true,false);
        def(6, "Sapling", 15,15,15, 16, 5,0, false,false);
        def(7, "Bedrock", 17,17,17, 16, 0,1, true,false);
        def(8, "Water", 14,14,14, 16, 3,2, true,false);
        def(9, "Still water", 14,14,14, 16, 3,2, true,false);
        def(10, "Lava", 30,30,30, 16, 0,3, true,false);
        def(11, "Still lava", 30,30,30, 16, 0,3, true,false);
        def(12, "Sand", 18,18,18, 16, 0,1, true,true);
        def(13, "Gravel", 19,19,19, 16, 0,1, true,true);
        def(14, "Gold ore", 32,32,32, 16, 0,1, true,false);
        def(15, "Iron ore", 33,33,33, 16, 0,1, true,false);
        def(16, "Coal ore", 34,34,34, 16, 0,1, true,false);
        def(17, "Log", 21,20,21, 16, 0,1, true,false);
        def(18, "Leaves", 22,22,22, 16, 2,1, false,false);
        def(19, "Sponge", 48,48,48, 16, 0,1, true,false);
        def(20, "Glass", 49,49,49, 16, 1,1, false,false);
        def(21, "Red", 64,64,64, 16, 0,1, true,false);
        def(22, "Orange", 65,65,65, 16, 0,1, true,false);
        def(23, "Yellow", 66,66,66, 16, 0,1, true,false);
        def(24, "Lime", 67,67,67, 16, 0,1, true,false);
        def(25, "Green", 68,68,68, 16, 0,1, true,false);
        def(26, "Teal", 69,69,69, 16, 0,1, true,false);
        def(27, "Aqua", 70,70,70, 16, 0,1, true,false);
        def(28, "Cyan", 71,71,71, 16, 0,1, true,false);
        def(29, "Blue", 72,72,72, 16, 0,1, true,false);
        def(30, "Indigo", 73,73,73, 16, 0,1, true,false);
        def(31, "Violet", 74,74,74, 16, 0,1, true,false);
        def(32, "Magenta", 75,75,75, 16, 0,1, true,false);
        def(33, "Pink", 76,76,76, 16, 0,1, true,false);
        def(34, "Black", 77,77,77, 16, 0,1, true,false);
        def(35, "Gray", 78,78,78, 16, 0,1, true,false);
        def(36, "White", 79,79,79, 16, 0,1, true,false);
        def(37, "Dandelion", 13,13,13, 16, 5,0, false,false);
        def(38, "Rose", 12,12,12, 16, 5,0, false,false);
        def(39, "Brown mushroom", 29,29,29, 16, 5,0, false,false);
        def(40, "Red mushroom", 28,28,28, 16, 5,0, false,false);
        def(41, "Gold", 24,40,56, 16, 0,1, true,false);
        def(42, "Iron", 23,39,55, 16, 0,1, true,false);
        def(43, "Double slab", 6,5,6, 16, 0,1, true,false);
        def(44, "Slab", 6,5,6, 8, 0,1, true,false);
        def(45, "Brick", 7,7,7, 16, 0,1, true,false);
        def(46, "TNT", 9,8,10, 16, 0,1, true,false);
        def(47, "Bookshelf", 4,35,4, 16, 0,1, true,false);
        def(48, "Mossy rocks", 36,36,36, 16, 0,1, true,false);
        def(49, "Obsidian", 37,37,37, 16, 0,1, true,false);
        def(50, "Cobblestone slab", 16,16,16, 8, 0,1, true,false);
        def(51, "Rope", 11,11,11, 16, 5,4, false,false);
        def(52, "Sandstone", 25,41,57, 16, 0,1, true,false);
        def(53, "Snow", 50,50,50, 4, 0,0, true,false);
        def(54, "Fire", 38,38,38, 16, 5,0, false,false);
        def(55, "Light pink", 80,80,80, 16, 0,1, true,false);
        def(56, "Forest green", 81,81,81, 16, 0,1, true,false);
        def(57, "Brown", 82,82,82, 16, 0,1, true,false);
        def(58, "Deep blue", 83,83,83, 16, 0,1, true,false);
        def(59, "Turquoise", 84,84,84, 16, 0,1, true,false);
        def(60, "Ice", 51,51,51, 16, 3,1, true,false);
        def(61, "Ceramic tile", 54,54,54, 16, 0,1, true,false);
        def(62, "Magma", 86,86,86, 16, 0,1, true,false);
        def(63, "Pillar", 26,42,58, 16, 0,1, true,false);
        def(64, "Crate", 53,53,53, 16, 0,1, true,false);
        def(65, "Stone brick", 52,52,52, 16, 0,1, true,false);

        rock = tiles[1]; grass = tiles[2]; dirt = tiles[3]; cobble = tiles[4];
        wood = tiles[5]; sapling = tiles[6]; bedrock = tiles[7];
        water = tiles[8]; stillWater = tiles[9]; lava = tiles[10]; stillLava = tiles[11];
        sand = tiles[12]; gravel = tiles[13];
        log = tiles[17]; leaves = tiles[18]; sponge = tiles[19]; glass = tiles[20];
        dandelion = tiles[37]; rose = tiles[38];
        brownMushroom = tiles[39]; redMushroom = tiles[40];
        doubleSlab = tiles[43]; slab = tiles[44]; sandstone = tiles[52]; snow = tiles[53];
        rope = tiles[51];   // climb-type decoration, sprinkled on islands
        goldOre = tiles[14]; ironOre = tiles[15]; coalOre = tiles[16];
    }

    public int getTexture(int face) {
        if (face == 1) return topTex;     // top
        if (face == 0) return bottomTex;  // bottom
        return sideTex;                   // sides
    }

    /** True if this block fills its whole cube opaquely (full neighbour cull). */
    public boolean isFullOpaqueCube() {
        return draw == DRAW_OPAQUE && height == 16;
    }

    public boolean isSprite() {
        return draw == DRAW_SPRITE;
    }

    public boolean isLiquid() {
        return collide == COLLIDE_WATER || collide == COLLIDE_LAVA;
    }

    public boolean isTranslucent() {
        return draw == DRAW_TRANSLUCENT;
    }

    /** Whether geometry should be emitted at all. */
    public boolean isRenderable() {
        return draw != DRAW_GAS;
    }

    public boolean blocksLight() {
        return blocksLight;
    }

    /** Solid for movement/collision (old call sites). */
    public boolean isSolid() {
        return collide == COLLIDE_SOLID;
    }

    /** Whether this block's faces fully hide an adjacent face. */
    public boolean occludes() {
        return draw == DRAW_OPAQUE && height == 16;
    }

    public float topHeight() {
        return height / 16.0f;
    }

    public AABB getAABB(int x, int y, int z) {
        if (collide != COLLIDE_SOLID) return null;
        return new AABB(x, y, z, x + 1, y + topHeight(), z + 1);
    }

    /**
     * Random tick behaviour. Faithful to Classic:
     *   grass spreads to lit dirt / dies in shadow;
     *   saplings, flowers and mushrooms decay without light or soil;
     *   sand and gravel fall.
     */
    public void tick(ILevel level, int x, int y, int z, Random random) {
        if (this == grass) {
            if (!level.isLit(x, y, z)) {
                level.setTile(x, y, z, dirt.id);
            } else {
                for (int i = 0; i < 4; ++i) {
                    int xt = x + random.nextInt(3) - 1;
                    int yt = y + random.nextInt(5) - 3;
                    int zt = z + random.nextInt(3) - 1;
                    if (level.getTile(xt, yt, zt) == dirt.id && level.isLit(xt, yt, zt)) {
                        level.setTile(xt, yt, zt, grass.id);
                    }
                }
            }
        } else if (draw == DRAW_SPRITE && collide == COLLIDE_NONE) {
            int below = level.getTile(x, y - 1, z);
            boolean soil = (below == dirt.id || below == grass.id);
            if (this == sapling || this == dandelion || this == rose) {
                if (!level.isLit(x, y, z) || !soil) level.setTile(x, y, z, 0);
            } else if (this == brownMushroom || this == redMushroom) {
                if (level.isLit(x, y, z) || below == 0) level.setTile(x, y, z, 0);
            }
        } else if (gravity) {
            // Sand / gravel fall into air below.
            if (y > 0 && level.getTile(x, y - 1, z) == 0) {
                level.setTile(x, y, z, 0);
                int yy = y - 1;
                while (yy > 0 && level.getTile(x, yy - 1, z) == 0) yy--;
                level.setTile(x, yy, z, id);
            }
        }
    }
}
