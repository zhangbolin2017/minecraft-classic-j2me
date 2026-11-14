package com.mojang.rubydung.level;

import com.mojang.rubydung.level.tile.Tile;
import com.mojang.rubydung.phys.AABB;

import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

public class InfiniteLevel implements ILevel {
    private static final int CHUNK = 16;
    private static final int MAX_CHUNKS = 96;
    private final int depth;
    private final int seed;

    private final Hashtable chunks = new Hashtable();
    private final Vector lru = new Vector();
    private final Hashtable chunkDeltas = new Hashtable();
    private final Random random;
    private final Object chunkLock = new Object();
    private int unprocessed = 0;
    private static final int TILE_UPDATE_INTERVAL = 400;
    private int focusCx = 0;
    private int focusCz = 0;

    public InfiniteLevel(int depth, int seed) {
        this.depth = depth;
        this.seed = seed;
        this.random = new Random(seed ^ 0x2F6E2B1D);
    }

    public void setFocus(int blockX, int blockZ) {
        this.focusCx = floorDiv(blockX, CHUNK);
        this.focusCz = floorDiv(blockZ, CHUNK);
    }

    public int getWidth() {
        return 0;
    }

    public int getHeight() {
        return 0;
    }

    public int getDepth() {
        return depth;
    }

    public boolean load() {
        return false;
    }

    public void save() {
    }

    public int estimateSurfaceY(int x, int z) {
        float n = fbm(x, z, 4, 64.0f, 0.55f);
        float m = fbm(x + 1013, z - 771, 3, 128.0f, 0.6f);
        float h = 30.0f + n * 18.0f + m * 10.0f;
        int ih = (int) h;
        if (ih < 1) ih = 1;
        if (ih > depth - 2) ih = depth - 2;
        return ih - 1;
    }

    public int getTile(int x, int y, int z) {
        if (y < 0 || y >= depth) return 0;
        Chunk c = getChunkForBlock(x, z);
        int lx = mod(x, CHUNK);
        int lz = mod(z, CHUNK);
        return c.get(lx, y, lz);
    }

    public boolean setTile(int x, int y, int z, int type) {
        if (y < 0 || y >= depth) return false;
        Chunk c = getChunkForBlock(x, z);
        int lx = mod(x, CHUNK);
        int lz = mod(z, CHUNK);
        int old = c.get(lx, y, lz);
        if (old == type) return false;
        c.set(lx, y, lz, type);
        ++this.mods;   // geometry-cache dirty signal
        c.recalcLightDepth(lx, lz);
        int cx = floorDiv(x, CHUNK);
        int cz = floorDiv(z, CHUNK);
        Long key = new Long(pack(cx, cz));
        int li = (y * CHUNK + lz) * CHUNK + lx;
        recordDelta(key, li, (byte) type);
        return true;
    }

    public boolean isSolidTile(int x, int y, int z) {
        Tile tile = Tile.tiles[getTile(x, y, z)];
        return tile != null && tile.isSolid();
    }

    /** Geometry-cache dirty signal, same contract as Level.modCount(). */
    private int mods = 0;
    public int modCount() { return this.mods; }

    public boolean isLit(int x, int y, int z) {
        if (y < 0 || y >= depth) return true;
        Chunk c = getChunkForBlock(x, z);
        int lx = mod(x, CHUNK);
        int lz = mod(z, CHUNK);
        return y >= c.getLightDepth(lx, lz);
    }

    public boolean occludes(int x, int y, int z) {
        Tile tile = Tile.tiles[getTile(x, y, z)];
        return tile != null && tile.occludes();
    }

    public boolean hidesFace(int x, int y, int z, int selfId) {
        int nid = getTile(x, y, z);
        if (nid == 0) return false;
        Tile tile = Tile.tiles[nid];
        if (tile == null) return false;
        if (tile.occludes()) return true;
        return nid == selfId && !tile.isSprite();
    }

    public float getBrightness(int x, int y, int z) {
        if (y < 0 || y >= depth) return 1.0f;
        Chunk c = getChunkForBlock(x, z);
        int lx = mod(x, CHUNK);
        int lz = mod(z, CHUNK);
        int ld = c.getLightDepth(lx, lz);
        if (y >= ld) return 1.0f;
        if (y == ld - 1) return 0.8f;
        return 0.6f;
    }

    public Vector getCubes(AABB aabb) {
        Vector aabbs = new Vector();
        int x0 = (int) Math.floor(aabb.x0);
        int x1 = (int) Math.floor(aabb.x1 + 1.0f);
        int y0 = (int) Math.floor(aabb.y0);
        int y1 = (int) Math.floor(aabb.y1 + 1.0f);
        int z0 = (int) Math.floor(aabb.z0);
        int z1 = (int) Math.floor(aabb.z1 + 1.0f);
        if (y0 < 0) y0 = 0;
        if (y1 > depth) y1 = depth;
        for (int x = x0; x < x1; ++x) {
            for (int y = y0; y < y1; ++y) {
                for (int z = z0; z < z1; ++z) {
                    Tile tile = Tile.tiles[getTile(x, y, z)];
                    if (tile != null) {
                        AABB bb = tile.getAABB(x, y, z);
                        if (bb != null) aabbs.addElement(bb);
                    }
                }
            }
        }
        return aabbs;
    }

    public void tick() {
        unprocessed += 128 * 128 * depth;
        int ticks = unprocessed / TILE_UPDATE_INTERVAL;
        unprocessed -= ticks * TILE_UPDATE_INTERVAL;
        for (int i = 0; i < ticks; ++i) {
            int cx = focusCx + random.nextInt(7) - 3;
            int cz = focusCz + random.nextInt(7) - 3;
            Chunk c = getChunk(cx, cz);
            int x = random.nextInt(CHUNK);
            int y = random.nextInt(depth);
            int z = random.nextInt(CHUNK);
            int wx = c.cx * CHUNK + x;
            int wz = c.cz * CHUNK + z;
            Tile tile = Tile.tiles[c.get(x, y, z)];
            if (tile != null) tile.tick(this, wx, y, wz, random);
        }
    }

    private Chunk getChunkForBlock(int x, int z) {
        int cx = floorDiv(x, CHUNK);
        int cz = floorDiv(z, CHUNK);
        return getChunk(cx, cz);
    }

    // Last-chunk cache: block queries have strong locality (render scans run
    // along rows), so most getChunk calls return without locking. Volatile
    // keeps the cached reference visible across the render/tick threads.
    private volatile Chunk lastChunk;

    private Chunk getChunk(int cx, int cz) {
        Chunk c = lastChunk;
        if (c != null && c.cx == cx && c.cz == cz) return c;
        synchronized (chunkLock) {
            Long key = new Long(pack(cx, cz));
            c = (Chunk) chunks.get(key);
            if (c == null) {
                c = new Chunk(cx, cz);
                chunks.put(key, c);
                lru.addElement(key);
                evictIfNeeded();
            }
            // Note: no per-access LRU touch. Rescanning the list on every
            // block query dominated frame time; eviction is plain FIFO now.
            lastChunk = c;
            return c;
        }
    }

    private void evictIfNeeded() {
        while (lru.size() > MAX_CHUNKS) {
            Long k = (Long) lru.elementAt(0);
            lru.removeElementAt(0);
            chunks.remove(k);
            Chunk c = lastChunk;
            if (c != null && pack(c.cx, c.cz) == k.longValue()) lastChunk = null;
        }
    }

    private long pack(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static class ChunkDelta {
        int[] idx = new int[64];
        byte[] val = new byte[64];
        int size = 0;
    }

    private void recordDelta(Long key, int idx, byte val) {
        ChunkDelta d = (ChunkDelta) chunkDeltas.get(key);
        if (d == null) {
            d = new ChunkDelta();
            chunkDeltas.put(key, d);
        }
        for (int i = 0; i < d.size; ++i) {
            if (d.idx[i] == idx) {
                d.val[i] = val;
                return;
            }
        }
        if (d.size >= d.idx.length) {
            int n = d.idx.length * 2;
            int[] ni = new int[n];
            byte[] nv = new byte[n];
            System.arraycopy(d.idx, 0, ni, 0, d.size);
            System.arraycopy(d.val, 0, nv, 0, d.size);
            d.idx = ni;
            d.val = nv;
        }
        d.idx[d.size] = idx;
        d.val[d.size] = val;
        d.size++;
    }

    private void applyDeltas(Chunk c) {
        Long key = new Long(pack(c.cx, c.cz));
        ChunkDelta d = (ChunkDelta) chunkDeltas.get(key);
        if (d == null) return;
        for (int i = 0; i < d.size; ++i) {
            int li = d.idx[i];
            if (li >= 0 && li < c.blocks.length) c.blocks[li] = d.val[i];
        }
    }

    private int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && a % b != 0) --r;
        return r;
    }

    private int mod(int a, int b) {
        int m = a % b;
        if (m < 0) m += b;
        return m;
    }

    private float fbm(int x, int z, int oct, float scale, float gain) {
        float sum = 0.0f;
        float amp = 1.0f;
        float norm = 0.0f;
        float s = scale;
        for (int i = 0; i < oct; ++i) {
            sum += noise2(x / s, z / s) * amp;
            norm += amp;
            amp *= gain;
            s *= 0.5f;
        }
        return sum / norm;
    }

    private float noise2(float x, float z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        float fx = x - x0;
        float fz = z - z0;
        float v00 = rand2(x0, z0);
        float v10 = rand2(x1, z0);
        float v01 = rand2(x0, z1);
        float v11 = rand2(x1, z1);
        float u = fade(fx);
        float v = fade(fz);
        float a = lerp(v00, v10, u);
        float b = lerp(v01, v11, u);
        return lerp(a, b, v);
    }

    private float rand2(int x, int z) {
        int h = x * 374761393 + z * 668265263 + seed * 1442695041;
        h = (h ^ (h >> 13)) * 1274126177;
        h ^= (h >> 16);
        return ((h & 0x7FFFFFFF) / 1073741824.0f) - 1.0f;
    }

    private float fade(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private class Chunk {
        final int cx, cz;
        final byte[] blocks;
        final int[] lightDepth;

        Chunk(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
            this.blocks = new byte[CHUNK * CHUNK * depth];
            this.lightDepth = new int[CHUNK * CHUNK];
            generate();
            applyDeltas(this);
            recalcAllLight();
        }

        int idx(int x, int y, int z) {
            return (y * CHUNK + z) * CHUNK + x;
        }

        int get(int x, int y, int z) {
            return blocks[idx(x, y, z)] & 0xFF;
        }

        void set(int x, int y, int z, int v) {
            blocks[idx(x, y, z)] = (byte) v;
        }

        int getLightDepth(int x, int z) {
            return lightDepth[x + z * CHUNK];
        }

        void recalcLightDepth(int x, int z) {
            int y = depth - 1;
            while (y > 0) {
                int id = get(x, y, z);
                Tile t = Tile.tiles[id];
                if (t != null && t.blocksLight()) break;
                --y;
            }
            lightDepth[x + z * CHUNK] = y;
        }

        void recalcAllLight() {
            for (int z = 0; z < CHUNK; ++z) {
                for (int x = 0; x < CHUNK; ++x) {
                    recalcLightDepth(x, z);
                }
            }
        }

        void generate() {
            int water = 32;
            for (int z = 0; z < CHUNK; ++z) {
                int wz = cz * CHUNK + z;
                for (int x = 0; x < CHUNK; ++x) {
                    int wx = cx * CHUNK + x;
                    int h = terrainHeight(wx, wz);
                    if (h < 1) h = 1;
                    if (h > depth - 2) h = depth - 2;
                    for (int y = 0; y < depth; ++y) {
                        int id = 0;
                        if (y == 0) {
                            id = Tile.bedrock.id;
                        } else if (y < h - 4) {
                            id = Tile.rock.id;
                            // Ores seeded into the stone layer (deterministic
                            // hash, so regenerated chunks stay identical).
                            // Generous rates per user request: bump into coal
                            // after digging just a few stones; iron a bit
                            // down, gold deeper -- but nothing is "rare".
                            float o = (rand2(wx + y * 7919, wz - y * 104729) + 1.0f) * 0.5f;
                            if (o < 0.012f && y < 20) {
                                id = Tile.goldOre.id;
                            } else if (o < 0.037f && y < 28) {
                                id = Tile.ironOre.id;
                            } else if (o < 0.087f) {
                                id = Tile.coalOre.id;
                            }
                        } else if (y < h - 1) {
                            id = Tile.dirt.id;
                        } else if (y == h - 1) {
                            id = (h <= water + 1) ? Tile.dirt.id : Tile.grass.id;
                        } else {
                            id = 0;
                        }
                        if (id == 0 && y <= water) id = Tile.stillWater.id;
                        set(x, y, z, id);
                    }
                }
            }
            sprinkleRope(water);
        }

        /**
         * Rope (the climb-type decoration block) on dry land: every chunk
         * that has any land gets AT LEAST TWO ropes, so every island grows
         * some.  Must stay deterministic per chunk (seeded hash picks only)
         * or chunks would change when regenerated after unload.
         */
        void sprinkleRope(int water) {
            int[] cols = new int[CHUNK * CHUNK];
            int n = 0;
            for (int z = 0; z < CHUNK; ++z) {
                for (int x = 0; x < CHUNK; ++x) {
                    int h = terrainHeight(cx * CHUNK + x, cz * CHUNK + z);
                    if (h < 1) h = 1;
                    if (h > depth - 2) h = depth - 2;
                    // dry land (grass tops) with headroom for the sprite
                    if (h > water + 1 && h + 1 < depth
                            && get(x, h, z) == 0 && get(x, h + 1, z) == 0) {
                        cols[n++] = x + z * CHUNK;
                    }
                }
            }
            if (n == 0) return;
            int place = 2 + (rand2(cx + 999, cz - 999) > 0.4f ? 2 : 0);
            for (int k = 0; k < place * 2 && place > 0; ++k) {
                float pick = (rand2(cx * 131 + k, cz * 173 - k) + 1.0f) * 0.5f;
                int idx = (int) (pick * n) % n;
                int col = cols[idx];
                int lx = col % CHUNK;
                int lz = col / CHUNK;
                int h = terrainHeight(cx * CHUNK + lx, cz * CHUNK + lz);
                if (h < 1) h = 1;
                if (h > depth - 2) h = depth - 2;
                // Climb up past our own ropes: a second pick on the same
                // column just hangs the rope one block taller.
                int y = h;
                while (y < depth && y <= h + 4 && get(lx, y, lz) == Tile.rope.id) ++y;
                if (y <= h + 4 && y < depth && get(lx, y, lz) == 0) {
                    set(lx, y, lz, Tile.rope.id);
                    --place;
                }
            }
        }

        int terrainHeight(int x, int z) {
            float n = fbm(x, z, 4, 64.0f, 0.55f);
            float m = fbm(x + 1013, z - 771, 3, 128.0f, 0.6f);
            float h = 30.0f + n * 18.0f + m * 10.0f;
            return (int) h;
        }

        float fbm(int x, int z, int oct, float scale, float gain) {
            float sum = 0.0f;
            float amp = 1.0f;
            float norm = 0.0f;
            float s = scale;
            for (int i = 0; i < oct; ++i) {
                sum += noise2(x / s, z / s) * amp;
                norm += amp;
                amp *= gain;
                s *= 0.5f;
            }
            return sum / norm;
        }

        float noise2(float x, float z) {
            int x0 = (int) Math.floor(x);
            int z0 = (int) Math.floor(z);
            int x1 = x0 + 1;
            int z1 = z0 + 1;
            float fx = x - x0;
            float fz = z - z0;
            float v00 = rand2(x0, z0);
            float v10 = rand2(x1, z0);
            float v01 = rand2(x0, z1);
            float v11 = rand2(x1, z1);
            float u = fade(fx);
            float v = fade(fz);
            float a = lerp(v00, v10, u);
            float b = lerp(v01, v11, u);
            return lerp(a, b, v);
        }

        float rand2(int x, int z) {
            int h = x * 374761393 + z * 668265263 + seed * 1442695041;
            h = (h ^ (h >> 13)) * 1274126177;
            h ^= (h >> 16);
            return ((h & 0x7FFFFFFF) / 1073741824.0f) - 1.0f;
        }

        float fade(float t) {
            return t * t * (3.0f - 2.0f * t);
        }

        float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }
}
