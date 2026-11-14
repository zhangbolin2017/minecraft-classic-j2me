package com.mojang.rubydung.level;

import com.mojang.rubydung.phys.AABB;

import java.util.Vector;

public interface ILevel {
    int getWidth();
    int getHeight();
    int getDepth();

    int getTile(int x, int y, int z);
    boolean setTile(int x, int y, int z, int type);
    /** How many setTile calls have actually changed a block so far.  The
     *  renderer uses it as a cheap dirty signal for its geometry cache. */
    int modCount();
    boolean isSolidTile(int x, int y, int z);
    boolean isLit(int x, int y, int z);
    boolean occludes(int x, int y, int z);
    boolean hidesFace(int x, int y, int z, int selfId);
    float getBrightness(int x, int y, int z);
    Vector getCubes(AABB aabb);
    void tick();

    boolean load();
    void save();
}
