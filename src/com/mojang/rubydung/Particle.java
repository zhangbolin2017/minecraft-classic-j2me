package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * Block-break particle, ported from Classic 0.0.11a (com.mojang.minecraft.particle).
 * A small textured shard that obeys gravity and collides with the ground.
 */
public class Particle {
    private static final Random random = new Random();

    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public int tex;            // atlas tile index it samples from
    public float uo, vo;       // sub-tile offset (0..1) for a small patch
    public int life;
    public boolean removed = false;

    private final ILevel level;

    public Particle(ILevel level, float x, float y, float z,
                    float xd, float yd, float zd, int tex) {
        this.level = level;
        this.x = x; this.y = y; this.z = z;
        this.tex = tex;
        this.xd = xd + (random.nextFloat() * 2.0f - 1.0f) * 0.08f;
        this.yd = yd + (random.nextFloat() * 2.0f - 1.0f) * 0.08f + 0.1f;
        this.zd = zd + (random.nextFloat() * 2.0f - 1.0f) * 0.08f;
        float speed = (random.nextFloat() + random.nextFloat() + 1.0f) * 0.15f;
        float d = (float) Math.sqrt(this.xd * this.xd + this.yd * this.yd + this.zd * this.zd);
        if (d > 0.0001f) {
            this.xd = this.xd / d * speed * 0.4f;
            this.yd = this.yd / d * speed + 0.1f;
            this.zd = this.zd / d * speed * 0.4f;
        }
        this.uo = random.nextFloat() * 0.6f;
        this.vo = random.nextFloat() * 0.6f;
        this.life = 8 + random.nextInt(12);
    }

    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (--this.life <= 0) {
            this.removed = true;
            return;
        }
        this.yd = (float) (this.yd - 0.04);
        this.x += this.xd;
        this.y += this.yd;
        this.z += this.zd;
        this.xd *= 0.98f;
        this.yd *= 0.98f;
        this.zd *= 0.98f;
        // ground collision: stop at the first solid block below
        if (level.isSolidTile((int) this.x, (int) this.y, (int) this.z)) {
            this.yd = 0.0f;
            this.xd *= 0.7f;
            this.zd *= 0.7f;
        }
    }
}
