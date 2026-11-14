package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * Firecracker mob (bao zhu guai): a hostile walking firecracker.
 *
 * Model (box style): one head cube + one body cube (GREEN, creeper-like)
 * standing on two RED firecracker foot cubes, with a two-cube fuse rope
 * sticking out of the top of the head.
 *
 * AI (hostile walking bomb):
 *   - Chases the player like the skeleton.
 *   - Once in range it LIGHTS: the fuse cubes retract into the head over
 *     FUSE_TICKS while the body flashes, then it explodes.  The blast is
 *     executed by GameCanvas.doFirecrackerBlast(): it destroys terrain
 *     (bedrock is safe) and hurts the PLAYER only -- other mobs are immune
 *     to explosion damage (user spec).  Blast damage is 3 HP = 0.15 x the
 *     20 HP health bar.
 *   - Punch it to death first and it falls apart into debris cubes
 *     (like the skeleton) instead of exploding; no drops either way.
 */
public class Firecracker {
    private static final Random rng = new Random();

    // --------- Collision / model constants ---------
    public  static final float HALF   = 0.20f;
    public  static final float HEIGHT = 1.30f;
    private static final float GRAVITY = 0.04f;
    private static final float WALK_SPEED = 0.045f;   // slightly faster than a skeleton
    private static final float TRIGGER_DIST = 1.7f;
    /** Lit-fuse length in ticks; the fuse visually retracts over this time. */
    public static final int FUSE_TICKS = 40;

    // --------- Per-mob state ---------
    public final ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean onGround = false;
    public boolean removed  = false;
    public boolean dying    = false;
    public int health       = 6;         // three punches defuse it
    public int deathTime    = 0;

    /** True on the single tick the bomb goes off; GameCanvas blasts it. */
    public boolean explodeNow = false;
    /** 0 = chasing; >= 1 = ticks since the fuse was lit. */
    public int fuseT = 0;
    /**
     * Punch-death phase 1: the defused firecracker FREEZES on the spot for
     * this many ticks (~1 s).  Walking into it during the freeze is safe --
     * it can never explode any more.  Phase 2 (debris) starts afterwards.
     */
    public static final int DEATH_FREEZE_TICKS = 60;
    public int freezeT = 0;

    public float headingX = 0.0f;
    public float headingZ = 1.0f;
    public float walkBob = 0.0f;

    public Firecracker(ILevel level, float x, float y, float z) {
        this.level = level;
        this.x = x;   this.y = y;   this.z = z;
        this.xo = x;  this.yo = y;  this.zo = z;
    }

    // ---- Death = falls apart into debris cubes (skeleton style). ----
    // Part order: 0 head, 1 body, 2/3 feet, 4 fuse.
    public static final int PARTS = 5;
    public static final float[] PART_OX = { 0f, 0f, -0.09f, 0.09f, 0f };
    public static final float[] PART_OY = { 1.08f, 0.51f, 0.08f, 0.08f, 1.44f };
    public static final float[] PART_OZ = { 0f, 0f, 0f, 0f, 0f };
    private static final int STRIDE = 13;
    private static final int IX = 0, IXO = 1, IY = 2, IYO = 3, IZ = 4, IZO = 5,
                             IVX = 6, IVY = 7, IVZ = 8,
                             IYAW = 9, IPIT = 10, IYAWR = 11, IPITR = 12;
    private final float[] part = new float[PARTS * STRIDE];

    public boolean hurt(int damage, Player source) {
        if (this.dying) return false;
        this.health -= damage;
        if (this.health <= 0) {
            // Defused by punching: freeze on the spot for ~1 s first (safe
            // to walk into -- it can never explode), THEN fall apart.
            this.dying = true;
            this.deathTime = 0;
            this.fuseT = 0;
            this.freezeT = DEATH_FREEZE_TICKS;
            this.xd = 0f; this.zd = 0f;
        }
        if (source != null) {
            float dx = this.x - source.x;
            float dz = this.z - source.z;
            float dl = (float) Math.sqrt(dx * dx + dz * dz);
            if (dl > 0.0001f) {
                this.xd += dx / dl * 0.10f;
                this.zd += dz / dl * 0.10f;
            }
        }
        return true;
    }

    public boolean isFusing() {
        return this.fuseT > 0;
    }

    /** True while the punch-killed corpse stands frozen (before debris). */
    public boolean isFrozen() {
        return this.dying && this.freezeT > 0;
    }

    /** 0..1 how far the fuse has burned (and retracted into the head). */
    public float fuseProgress() {
        if (this.fuseT <= 0) return 0f;
        float p = (float) this.fuseT / (float) FUSE_TICKS;
        return p > 1f ? 1f : p;
    }

    public void tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        // --- Death, phase 1: frozen on the spot for ~1 second (a defused
        //     dud -- nothing can make it explode now); phase 2: the debris
        //     cubes pop out, bounce and fade (skeleton style). ---
        if (this.dying) {
            if (this.freezeT > 0) {
                --this.freezeT;
                if (this.freezeT == 0) this.explodeParts();
                return;
            }
            ++this.deathTime;
            boolean anyAlive = false;
            for (int i = 0; i < PARTS; ++i) {
                int b = i * STRIDE;
                part[b + IXO] = part[b + IX];
                part[b + IYO] = part[b + IY];
                part[b + IZO] = part[b + IZ];
                float vy = part[b + IVY] - GRAVITY;
                part[b + IVY] = vy;

                float nx = part[b + IX] + part[b + IVX];
                if (partSolid(nx, part[b + IY], part[b + IZ])) part[b + IVX] *= -0.3f;
                else part[b + IX] = nx;
                float nz = part[b + IZ] + part[b + IVZ];
                if (partSolid(part[b + IX], part[b + IY], nz)) part[b + IVZ] *= -0.3f;
                else part[b + IZ] = nz;

                float ny = part[b + IY] + vy;
                if (vy < 0f && partSolid(part[b + IX], ny, part[b + IZ])) {
                    part[b + IY] = (float) Math.floor(ny) + 1.0f;
                    part[b + IVY] = vy * -0.30f;
                    part[b + IVX] *= 0.55f;
                    part[b + IVZ] *= 0.55f;
                    part[b + IYAWR] *= 0.55f;
                    part[b + IPITR] *= 0.55f;
                } else if (vy > 0f && partSolid(part[b + IX], ny, part[b + IZ])) {
                    part[b + IVY] = 0f;
                } else {
                    part[b + IY] = ny;
                }
                part[b + IYAW] += part[b + IYAWR];
                part[b + IPIT] += part[b + IPITR];
                if (part[b + IY] > -8f) anyAlive = true;
            }
            if (this.deathTime >= 26 || !anyAlive) this.removed = true;
            return;
        }

        // --- Lit fuse: stand still, retract, count down, blow. ---
        if (this.fuseT > 0) {
            ++this.fuseT;
            this.xd *= 0.6f;
            this.zd *= 0.6f;
            this.walkBob *= 0.8f;
            if (this.fuseT >= FUSE_TICKS) {
                this.explodeNow = true;   // GameCanvas performs the blast
            }
        } else if (player != null && !player.isDead()) {
            // --- Hostile chase: walk at the player, hop 1-block steps. ---
            float dx = player.x - this.x;
            float dz = player.z - this.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > 0.0001f) {
                this.headingX = dx / dist;
                this.headingZ = dz / dist;
            }
            if (dist > TRIGGER_DIST) {
                this.xd += (this.headingX * WALK_SPEED - this.xd) * 0.4f;
                this.zd += (this.headingZ * WALK_SPEED - this.zd) * 0.4f;
                this.walkBob += 0.26f;
            } else {
                this.xd *= 0.6f;
                this.zd *= 0.6f;
                this.fuseT = 1;           // light the fuse
            }
        } else {
            this.xd *= 0.8f;
            this.zd *= 0.8f;
            this.walkBob *= 0.8f;
        }

        // --- Horizontal move with collision; hop up 1-block steps. ---
        float step = this.xd;
        this.x += step;
        if (collides()) {
            this.x -= step;
            if (this.onGround && this.yd <= 0f) this.yd = 0.28f;
            this.xd = 0f;
        }
        step = this.zd;
        this.z += step;
        if (collides()) {
            this.z -= step;
            if (this.onGround && this.yd <= 0f) this.yd = 0.28f;
            this.zd = 0f;
        }
        this.xd *= 0.86f;
        this.zd *= 0.86f;

        // --- Gravity / ground. ---
        this.yd -= GRAVITY;
        this.y += this.yd;
        if (this.yd < 0f) {
            int feet = (int) Math.floor(this.y);
            if (collides()) {
                this.y = feet + 1.0f;
                this.yd = 0f;
                this.onGround = true;
            } else {
                this.onGround = false;
            }
        } else {
            if (collides()) {
                this.y = (int) Math.floor(this.y + HEIGHT) - HEIGHT;
                this.yd = 0f;
            }
            this.onGround = false;
        }

        if (this.y < -8f) this.removed = true;
    }

    /** AABB collision check against the solid tile grid. */
    private boolean collides() {
        float w = HALF;
        int x0 = (int) Math.floor(this.x - w);
        int x1 = (int) Math.floor(this.x + w);
        int y0 = (int) Math.floor(this.y + 0.01f);
        int y1 = (int) Math.floor(this.y + HEIGHT - 0.01f);
        int z0 = (int) Math.floor(this.z - w);
        int z1 = (int) Math.floor(this.z + w);
        for (int bx = x0; bx <= x1; ++bx)
            for (int by = y0; by <= y1; ++by)
                for (int bz = z0; bz <= z1; ++bz)
                    if (level.isSolidTile(bx, by, bz)) return true;
        return false;
    }

    /** Seed the debris: every part pops away from the body centre. */
    private void explodeParts() {
        for (int i = 0; i < PARTS; ++i) {
            float ox = PART_OX[i], oy = PART_OY[i], oz = PART_OZ[i];
            int b = i * STRIDE;
            part[b + IX] = part[b + IXO] = this.x + ox;
            part[b + IY] = part[b + IYO] = this.y + oy;
            part[b + IZ] = part[b + IZO] = this.z + oz;
            float jl = (float) Math.sqrt(ox * ox + oz * oz);
            float dirx = jl > 0.001f ? ox / jl : (rng.nextFloat() - 0.5f);
            float dirz = jl > 0.001f ? oz / jl : (rng.nextFloat() - 0.5f);
            float kick = 0.06f + rng.nextFloat() * 0.08f;
            if (i == 0 || i == PARTS - 1) kick += 0.05f;   // head & fuse fly farthest
            part[b + IVX] = dirx * kick + (rng.nextFloat() - 0.5f) * 0.04f;
            part[b + IVZ] = dirz * kick + (rng.nextFloat() - 0.5f) * 0.04f;
            part[b + IVY] = 0.10f + rng.nextFloat() * 0.12f;
            part[b + IYAW] = rng.nextFloat() * 6.283f;
            part[b + IPIT] = (rng.nextFloat() - 0.5f) * 0.6f;
            part[b + IYAWR] = (rng.nextFloat() - 0.5f) * 0.50f;
            part[b + IPITR] = (rng.nextFloat() - 0.5f) * 0.45f;
        }
    }

    private boolean partSolid(float x, float y, float z) {
        return level.isSolidTile((int) Math.floor(x),
                                 (int) Math.floor(y),
                                 (int) Math.floor(z));
    }

    // ---- Debris render accessors, interpolated between ticks. ----
    public float partX(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IXO] + (part[b + IX] - part[b + IXO]) * alpha;
    }
    public float partY(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IYO] + (part[b + IY] - part[b + IYO]) * alpha;
    }
    public float partZ(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IZO] + (part[b + IZ] - part[b + IZO]) * alpha;
    }
    public float partYaw(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IYAW] + part[b + IYAWR] * alpha;
    }
    public float partPitch(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IPIT] + part[b + IPITR] * alpha;
    }
}
