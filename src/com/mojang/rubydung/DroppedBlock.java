package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * Lightweight dropped-block pickup spawned when a block is mined.
 * It uses simple gravity, rests on solid ground, and gets pulled
 * toward the player once they are nearby.
 */
public class DroppedBlock {
    private static final Random random = new Random();

    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public final int blockId;
    public final int tex;
    public final String name;
    public boolean removed = false;

    private final ILevel level;
    private int age = 0;
    private int pickupDelay = 10;

    public DroppedBlock(ILevel level, float x, float y, float z, int blockId) {
        this(level, x, y, z, blockId, 0, null);
    }

    public DroppedBlock(ILevel level, float x, float y, float z, int blockId, int tex, String name) {
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
        this.tex = tex;
        this.name = name;
        this.xd = (random.nextFloat() - 0.5f) * 0.08f;
        this.yd = 0.10f + random.nextFloat() * 0.05f;
        this.zd = (random.nextFloat() - 0.5f) * 0.08f;
    }

    public boolean tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (++this.age > 20 * 30) {
            this.removed = true;
            return false;
        }

        if (this.pickupDelay > 0) --this.pickupDelay;

        if (player != null && this.pickupDelay <= 0) {
            float dx = player.x - this.x;
            float dy = player.y - this.y;
            float dz = player.z - this.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < 2.25f && distSq > 0.0001f) {
                float inv = 1.0f / (float) Math.sqrt(distSq);
                this.xd += dx * inv * 0.04f;
                this.yd += dy * inv * 0.03f;
                this.zd += dz * inv * 0.04f;
            }
            if (distSq < 0.20f) {
                this.removed = true;
                return true;
            }
        }

        this.yd -= 0.02f;
        this.x += this.xd;
        this.y += this.yd;
        this.z += this.zd;

        this.xd *= 0.96f;
        this.yd *= 0.96f;
        this.zd *= 0.96f;

        int bx = (int) Math.floor(this.x);
        int by = (int) Math.floor(this.y - 0.15f);
        int bz = (int) Math.floor(this.z);
        if (level.isSolidTile(bx, by, bz) && this.yd < 0.0f) {
            this.y = by + 1.15f;
            this.yd *= -0.15f;
            this.xd *= 0.75f;
            this.zd *= 0.75f;
            if (this.yd > -0.01f && this.yd < 0.01f) this.yd = 0.0f;
        }

        return false;
    }

    public String getDisplayName() {
        if (this.name != null) return this.name;
        return this.blockId == 0 ? "Item" : "Block";
    }
}
