package com.mojang.rubydung.level;

import com.mojang.rubydung.level.tile.Tile;

import java.util.Random;

/**
 * Terrain generator. Based on Minecraft Classic 0.0.11a's LevelGen (two height
 * fields blended by a "cliff" field, a rock layer), extended
 * toward ClassiCube's classic generator with a sea level (water), sand beaches,
 * gravel, and scattered trees + flowers.
 */
public class LevelGen {
    private int width;
    private int height;
    private int depth;
    private int waterLevel;
    private Random random = new Random();

    public LevelGen(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.waterLevel = depth / 2 + 5;
    }

    private int idx(int x, int y, int z) {
        return (y * this.height + z) * this.width + x;
    }

    public byte[] generateMap() {
        int w = this.width;
        int h = this.height;
        int d = this.depth;
        int[] heightmap1 = new NoiseMap(0).read(w, h);
        int[] heightmap2 = new NoiseMap(0).read(w, h);
        int[] cf = new NoiseMap(1).read(w, h);
        int[] rockMap = new NoiseMap(1).read(w, h);
        byte[] blocks = new byte[w * h * d];

        int[] surface = new int[w * h];

        for (int x = 0; x < w; ++x) {
            for (int z = 0; z < h; ++z) {
                int dh1 = heightmap1[x + z * this.width];
                int dh2 = heightmap2[x + z * this.width];
                int cfh = cf[x + z * this.width];
                if (cfh < 128) {
                    dh2 = dh1;
                }
                int dh = dh1;
                if (dh2 > dh) {
                    dh = dh2;
                } else {
                    dh2 = dh1;
                }
                dh = dh / 8 + d / 3;
                int rh = rockMap[x + z * this.width] / 8 + d / 3;
                if (rh > dh - 2) {
                    rh = dh - 2;
                }
                surface[x + z * this.width] = dh;

                boolean nearWater = dh <= this.waterLevel + 1;

                for (int y = 0; y < d; ++y) {
                    int id = 0;
                    if (y == dh) {
                        id = nearWater ? Tile.sand.id : Tile.grass.id;
                    } else if (y < dh) {
                        if (y <= rh) {
                            id = Tile.rock.id;
                        } else if (nearWater && y >= dh - 2) {
                            id = Tile.sand.id;
                        } else {
                            id = Tile.dirt.id;
                        }
                    }
                    // Fill open air below sea level with water.
                    if (id == 0 && y <= this.waterLevel) {
                        id = Tile.stillWater.id;
                    }
                    // Bedrock floor.
                    if (y == 0) {
                        id = Tile.bedrock.id;
                    }
                    blocks[idx(x, y, z)] = (byte) id;
                }
            }
        }

        sprinkleOres(blocks, w, h, d);
        plantTrees(blocks, surface, w, h, d);
        plantFlowers(blocks, surface, w, h, d);
        sprinkleRopes(blocks, surface, w, h, d);
        return blocks;
    }

    /**
     * Rope (the climb-type decoration block) on every island: flood-fill
     * the dry-land regions, then give EACH island at least two ropes
     * (bigger islands scale up: +1 per 512 columns).
     */
    private void sprinkleRopes(byte[] blocks, int[] surface, int w, int h, int d) {
        int[] region = new int[w * h];
        int[] stack = new int[w * h];
        int[] cells = new int[w * h];   // the current island's columns
        int regions = 0;
        for (int start = 0; start < w * h; ++start) {
            if (region[start] != 0 || surface[start] <= this.waterLevel + 1) continue;
            ++regions;
            int sp = 0, size = 0;
            stack[sp++] = start;
            region[start] = regions;
            while (sp > 0) {
                int c = stack[--sp];
                cells[size++] = c;
                int cx = c % w, cz = c / w;
                if (cx > 0 && region[c - 1] == 0 && surface[c - 1] > this.waterLevel + 1) {
                    region[c - 1] = regions; stack[sp++] = c - 1;
                }
                if (cx < w - 1 && region[c + 1] == 0 && surface[c + 1] > this.waterLevel + 1) {
                    region[c + 1] = regions; stack[sp++] = c + 1;
                }
                if (cz > 0 && region[c - w] == 0 && surface[c - w] > this.waterLevel + 1) {
                    region[c - w] = regions; stack[sp++] = c - w;
                }
                if (cz < h - 1 && region[c + w] == 0 && surface[c + w] > this.waterLevel + 1) {
                    region[c + w] = regions; stack[sp++] = c + w;
                }
            }
            int want = 2 + size / 512;
            int placed = 0;
            // Pick from THIS island's own columns, so even a tiny isle
            // reliably gets its guaranteed ropes.  A second pick on the
            // same column just makes the rope hang one block taller.
            for (int k = 0; k < want * 16 && placed < want; ++k) {
                int c = cells[this.random.nextInt(size)];
                int x = c % w, z = c / w;
                int gy = surface[c];
                if (gy + 1 >= d) continue;
                if (blocks[idx(x, gy, z)] != Tile.grass.id) continue;
                int y = gy + 1;
                while (y < d && y <= gy + 4 && blocks[idx(x, y, z)] == Tile.rope.id) ++y;
                if (y <= gy + 4 && y < d && blocks[idx(x, y, z)] == 0) {
                    blocks[idx(x, y, z)] = (byte) Tile.rope.id;
                    ++placed;
                }
            }
        }
    }

    /** Ores seeded through the stone layer -- generous rates matching
     *  InfiniteLevel: coal everywhere (~5%), iron y<28, gold y<20. */
    private void sprinkleOres(byte[] blocks, int w, int h, int d) {
        for (int x = 0; x < w; ++x) {
            for (int z = 0; z < h; ++z) {
                for (int y = 1; y < d; ++y) {
                    int ii = idx(x, y, z);
                    if (blocks[ii] != (byte) Tile.rock.id) continue;
                    float o = this.random.nextFloat();
                    if (o < 0.012f && y < 20) {
                        blocks[ii] = (byte) Tile.goldOre.id;
                    } else if (o < 0.037f && y < 28) {
                        blocks[ii] = (byte) Tile.ironOre.id;
                    } else if (o < 0.087f) {
                        blocks[ii] = (byte) Tile.coalOre.id;
                    }
                }
            }
        }
    }

    private void plantTrees(byte[] blocks, int[] surface, int w, int h, int d) {
        int attempts = w * h / 64;
        for (int i = 0; i < attempts; ++i) {
            int x = 2 + this.random.nextInt(w - 4);
            int z = 2 + this.random.nextInt(h - 4);
            int gy = surface[x + z * this.width];
            if (gy <= this.waterLevel + 1 || gy + 6 >= d) continue;
            if (blocks[idx(x, gy, z)] != Tile.grass.id) continue;

            int trunk = 4 + this.random.nextInt(2);
            // leaves: two full 5x5 layers, then a 3x3 cap
            int top = gy + trunk;
            for (int yy = top - 2; yy <= top + 1; ++yy) {
                int rad = (yy <= top) ? 2 : 1;
                for (int xx = x - rad; xx <= x + rad; ++xx) {
                    for (int zz = z - rad; zz <= z + rad; ++zz) {
                        if (xx < 0 || zz < 0 || xx >= w || zz >= h || yy >= d) continue;
                        // skip the very corners of the widest layers
                        if (rad == 2 && Math.abs(xx - x) == 2 && Math.abs(zz - z) == 2
                                && yy < top) continue;
                        int ii = idx(xx, yy, zz);
                        if (blocks[ii] == 0) blocks[ii] = (byte) Tile.leaves.id;
                    }
                }
            }
            for (int t = 0; t < trunk; ++t) {
                blocks[idx(x, gy + t, z)] = (byte) Tile.log.id;
            }
        }
    }

    private void plantFlowers(byte[] blocks, int[] surface, int w, int h, int d) {
        int[] plants = new int[] {Tile.dandelion.id, Tile.rose.id,
                                  Tile.brownMushroom.id, Tile.redMushroom.id};
        int attempts = w * h / 32;
        for (int i = 0; i < attempts; ++i) {
            int x = this.random.nextInt(w);
            int z = this.random.nextInt(h);
            int gy = surface[x + z * this.width];
            if (gy + 1 >= d) continue;
            int below = blocks[idx(x, gy, z)] & 0xFF;
            int here = idx(x, gy + 1, z);
            if (below == Tile.grass.id && blocks[here] == 0) {
                blocks[here] = (byte) plants[this.random.nextInt(plants.length)];
            }
        }
    }
}
