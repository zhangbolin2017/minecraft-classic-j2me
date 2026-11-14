package com.mojang.rubydung.level;

import com.mojang.rubydung.level.tile.Tile;
import com.mojang.rubydung.phys.AABB;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;
import java.util.Vector;

import javax.microedition.rms.RecordStore;

/**
 * Minecraft Classic 0.0.11a world, ported to CLDC 1.1 / MIDP 2.0.
 *
 * Differences from the desktop original:
 *   - ArrayList -> Vector
 *   - GZIP file I/O (level.dat) -> RMS RecordStore (raw block bytes)
 *   - everything else (lighting, random tile ticks, LevelGen) is ported verbatim.
 */
public class Level implements ILevel {
    private static final int TILE_UPDATE_INTERVAL = 400;

    public final int width;
    public final int height;
    public final int depth;
    private byte[] blocks;
    private int[] lightDepths;
    private Vector levelListeners = new Vector();
    private Random random = new Random();
    private int unprocessed = 0;

    private static final String STORE_NAME = "mc_classic_level";

    public Level(int w, int h, int d) {
        this.width = w;
        this.height = h;
        this.depth = d;
        this.blocks = new byte[w * h * d];
        this.lightDepths = new int[w * h];
        boolean mapLoaded = this.load();
        if (!mapLoaded) {
            this.blocks = new LevelGen(w, h, d).generateMap();
        }
        this.calcLightDepths(0, 0, w, h);
    }

    /** Multiplayer: build a level from blocks received from the server (no RMS, no gen). */
    public Level(int w, int h, int d, byte[] blocks) {
        this.width = w;
        this.height = h;
        this.depth = d;
        this.blocks = (blocks != null && blocks.length == w * h * d) ? blocks : new byte[w * h * d];
        this.lightDepths = new int[w * h];
        this.calcLightDepths(0, 0, w, h);
    }

    public boolean load() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE_NAME, false);
            if (rs.getNumRecords() < 1) {
                return false;
            }
            byte[] data = rs.getRecord(1);
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            dis.readFully(this.blocks);
            dis.close();
            ++this.mods;   // whole world changed: invalidate geometry caches
            this.calcLightDepths(0, 0, this.width, this.height);
            for (int i = 0; i < this.levelListeners.size(); ++i) {
                ((LevelListener) this.levelListeners.elementAt(i)).allChanged();
            }
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    public void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.write(this.blocks);
            dos.close();
            byte[] data = baos.toByteArray();
            try {
                RecordStore.deleteRecordStore(STORE_NAME);
            } catch (Exception e) {}
            rs = RecordStore.openRecordStore(STORE_NAME, true);
            rs.addRecord(data, 0, data.length);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    public void calcLightDepths(int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x0 + x1; ++x) {
            for (int z = y0; z < y0 + y1; ++z) {
                int oldDepth = this.lightDepths[x + z * this.width];
                int y = this.depth - 1;
                while (y > 0 && !this.isLightBlocker(x, y, z)) {
                    --y;
                }
                this.lightDepths[x + z * this.width] = y;
                if (oldDepth != y) {
                    int yl0 = oldDepth < y ? oldDepth : y;
                    int yl1 = oldDepth > y ? oldDepth : y;
                    for (int i = 0; i < this.levelListeners.size(); ++i) {
                        ((LevelListener) this.levelListeners.elementAt(i)).lightColumnChanged(x, z, yl0, yl1);
                    }
                }
            }
        }
    }

    public void addListener(LevelListener levelListener) {
        this.levelListeners.addElement(levelListener);
    }

    public void removeListener(LevelListener levelListener) {
        this.levelListeners.removeElement(levelListener);
    }

    public boolean isLightBlocker(int x, int y, int z) {
        Tile tile = Tile.tiles[this.getTile(x, y, z)];
        if (tile == null) {
            return false;
        }
        return tile.blocksLight();
    }

    public Vector getCubes(AABB aABB) {
        Vector aABBs = new Vector();
        int x0 = (int) aABB.x0;
        int x1 = (int) (aABB.x1 + 1.0f);
        int y0 = (int) aABB.y0;
        int y1 = (int) (aABB.y1 + 1.0f);
        int z0 = (int) aABB.z0;
        int z1 = (int) (aABB.z1 + 1.0f);
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (z0 < 0) z0 = 0;
        if (x1 > this.width) x1 = this.width;
        if (y1 > this.depth) y1 = this.depth;
        if (z1 > this.height) z1 = this.height;
        for (int x = x0; x < x1; ++x) {
            for (int y = y0; y < y1; ++y) {
                for (int z = z0; z < z1; ++z) {
                    Tile tile = Tile.tiles[this.getTile(x, y, z)];
                    if (tile != null) {
                        AABB aabb = tile.getAABB(x, y, z);
                        if (aabb != null) {
                            aABBs.addElement(aabb);
                        }
                    }
                }
            }
        }
        return aABBs;
    }

    /** Total successful setTile mutations; geometry-cache dirty signal. */
    private int mods = 0;
    public int modCount() { return this.mods; }

    public boolean setTile(int x, int y, int z, int type) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return false;
        }
        if (type == this.blocks[(y * this.height + z) * this.width + x]) {
            return false;
        }
        this.blocks[(y * this.height + z) * this.width + x] = (byte) type;
        ++this.mods;
        this.calcLightDepths(x, z, 1, 1);
        for (int i = 0; i < this.levelListeners.size(); ++i) {
            ((LevelListener) this.levelListeners.elementAt(i)).tileChanged(x, y, z);
        }
        return true;
    }

    public boolean isLit(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return true;
        }
        return y >= this.lightDepths[x + z * this.width];
    }

    public int getTile(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return 0;
        }
        return this.blocks[(y * this.height + z) * this.width + x];
    }

    public boolean isSolidTile(int x, int y, int z) {
        Tile tile = Tile.tiles[this.getTile(x, y, z)];
        if (tile == null) {
            return false;
        }
        return tile.isSolid();
    }

    /** Tile object at a coord, or null for air / out of bounds. */
    public Tile getTileObj(int x, int y, int z) {
        return Tile.tiles[this.getTile(x, y, z)];
    }

    /**
     * Whether the tile at (x,y,z) fully occludes an adjacent face, so the
     * neighbour can cull that face. Only full opaque cubes occlude; glass,
     * leaves, water, slabs, sprites and air never do.
     */
    public boolean occludes(int x, int y, int z) {
        Tile tile = Tile.tiles[this.getTile(x, y, z)];
        if (tile == null) return false;
        return tile.occludes();
    }

    /**
     * Whether the neighbour at (x,y,z) hides the face of a block whose id is
     * selfId. Hidden if the neighbour is a full opaque cube, or it is the same
     * block id (non-sprite) so adjacent glass / water / leaves merge cleanly.
     */
    public boolean hidesFace(int x, int y, int z, int selfId) {
        int nid = this.getTile(x, y, z);
        if (nid == 0) return false;
        Tile tile = Tile.tiles[nid];
        if (tile == null) return false;
        if (tile.occludes()) return true;
        return nid == selfId && !tile.isSprite();
    }

    public float getBrightness(int x, int y, int z) {
        float dark = 0.6f;
        float mid = 0.8f;
        float light = 1.0f;
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return light;
        }
        int depth = this.lightDepths[x + z * this.width];
        if (y >= depth) {
            return light;
        }
        // One block below the sky-light boundary gets a softer shadow,
        // deeper blocks get the full dark value (cheap two-step gradient).
        if (y == depth - 1) {
            return mid;
        }
        return dark;
    }

    public void tick() {
        this.unprocessed += this.width * this.height * this.depth;
        int ticks = this.unprocessed / TILE_UPDATE_INTERVAL;
        this.unprocessed -= ticks * TILE_UPDATE_INTERVAL;
        for (int i = 0; i < ticks; ++i) {
            int x = this.random.nextInt(this.width);
            int y = this.random.nextInt(this.depth);
            int z = this.random.nextInt(this.height);
            Tile tile = Tile.tiles[this.getTile(x, y, z)];
            if (tile != null) {
                tile.tick(this, x, y, z, this.random);
            }
        }
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getDepth() {
        return this.depth;
    }
}
