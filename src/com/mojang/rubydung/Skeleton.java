package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * Skeleton: a MELEE mob built from boxes -- one head cube, one body cube
 * (plus thin limb cubes and tiny face detail cubes, in the RedMouse style),
 * holding a one-cube iron sword with a small wooden guard.
 *
 * AI (melee chaser):
 *   - Walks toward the player on the ground (heading kept as a unit vector,
 *     no atan2 needed) and hops up 1-block steps when blocked.
 *   - Once in range it swings the sword ONCE per attack: windup (raise),
 *     slash (sweep down), recover (~12 ticks total), then a cooldown.
 *     The swing currently deals 0 damage (SWING_DAMAGE) -- the player-hurt
 *     hook in GameCanvas.tickSkeletons() is kept so it can be enabled later.
 *   - Does NOT burn in daylight, by design (there is no day/night cycle in
 *     this port anyway, and the classic skeleton sun-burn rule stays off).
 */
public class Skeleton {
    private static final Random rng = new Random();

    // --------- Collision / model constants ---------
    public  static final float HALF   = 0.20f;   // body half-width for hit-test
    public  static final float HEIGHT = 1.45f;   // total hitbox height
    private static final float GRAVITY  = 0.04f;
    private static final float WALK_SPEED = 0.04f;   // blocks/tick (~0.8/s)

    /** Melee swing damage -- 0 by design (the swing scares, never hurts). */
    public static final int SWING_DAMAGE = 0;

    /** Start a swing when the player is horizontally closer than this. */
    private static final float ATTACK_RANGE = 1.5f;

    // --------- Per-mob state ---------
    public final ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean onGround = false;
    public boolean removed  = false;
    public boolean dying    = false;
    public int health       = 6;         // three punches at 2 dmg
    public int deathTime    = 0;

    // ---- Death = the skeleton FALLS APART: every part becomes
    // an independent tumbling box that pops away from the body centre,
    // bounces on the ground and the pile fades after ~1.3s.  The part
    // order is fixed: 0 head, 1 body, 2/3 legs, 4/5 arms, 6 sword blade. ----
    public static final int PARTS = 7;
    /** Local offsets of each part's centre in the assembled model. */
    public static final float[] PART_OX =
        { 0f, 0f, -0.075f, 0.075f, -0.17f, 0.17f, 0.17f };
    public static final float[] PART_OY =
        { 1.21f, 0.775f, 0.275f, 0.275f, 0.78f, 0.78f, 0.74f };
    public static final float[] PART_OZ =
        { 0f, 0f, 0f, 0f, 0f, 0.04f, 0.18f };
    private static final int STRIDE = 13;
    private static final int IX = 0, IXO = 1, IY = 2, IYO = 3, IZ = 4, IZO = 5,
                             IVX = 6, IVY = 7, IVZ = 8,
                             IYAW = 9, IPIT = 10, IYAWR = 11, IPITR = 12;
    /** Per-part debris state: position + previous position (render
     *  interpolation), velocity, yaw/pitch spin angles and their rates. */
    private final float[] part = new float[PARTS * STRIDE];

    /** Unit heading toward the player (the model's local +Z, its front). */
    public float headingX = 0.0f;
    public float headingZ = 1.0f;

    // Timers
    public  float walkBob = 0.0f;             // leg swing phase while walking
    /** Ticks since the swing started, -1 = idle. A swing lasts 12 ticks. */
    public  int   swingT  = -1;
    private int attackCooldown = 30 + rng.nextInt(30);

    public Skeleton(ILevel level, float x, float y, float z) {
        this.level = level;
        this.x = x;   this.y = y;   this.z = z;
        this.xo = x;  this.yo = y;  this.zo = z;
    }

    public boolean hurt(int damage, Player source) {
        if (this.dying) return false;
        this.health -= damage;
        if (this.health <= 0) {
            this.dying = true;
            this.deathTime = 0;
            this.xd = 0f; this.zd = 0f;
            this.swingT = -1;
            this.explodeParts();
        }
        // Knock back a little when hit (away from the attacker); the chase
        // logic re-establishes walking speed within a few ticks.
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

    /**
     * Sword pitch ("tip-up" radians) for rendering, interpolated between
     * ticks.  Idle holds the blade slightly raised; a swing raises it high
     * (ticks 0-3), slashes it down past horizontal (3-6), then eases back
     * to idle (6-12).
     */
    public float swordPitch(float alpha) {
        final float IDLE = 0.15f, UP = 1.0f, DOWN = -0.9f;
        if (this.swingT < 0) return IDLE;
        float t = this.swingT + alpha;
        if (t < 3f)  return IDLE + (UP - IDLE) * (t / 3f);
        if (t < 6f)  return UP + (DOWN - UP) * ((t - 3f) / 3f);
        if (t < 12f) return DOWN + (IDLE - DOWN) * ((t - 6f) / 6f);
        return IDLE;
    }

    /** True on the single tick mid-slash, when the blade would connect. */
    public boolean isSlashTick() {
        return this.swingT == 4;
    }

    public void tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        // --- Death = the whole skeleton FALLS APART: every part is its own
        //     tumbling box that pops away from the body centre, bounces on
        //     the ground and the scattered pile fades after ~1.3 seconds.
        //     No drops (the Classic palette has no bone block). ---
        if (this.dying) {
            ++this.deathTime;
            boolean anyAlive = false;
            for (int i = 0; i < PARTS; ++i) {
                int b = i * STRIDE;
                part[b + IXO] = part[b + IX];
                part[b + IYO] = part[b + IY];
                part[b + IZO] = part[b + IZ];
                float vy = part[b + IVY] - GRAVITY;
                part[b + IVY] = vy;

                // Axis-separated movement; pieces are small, so a point
                // solid check at the part centre is enough.
                float nx = part[b + IX] + part[b + IVX];
                if (partSolid(nx, part[b + IY], part[b + IZ])) part[b + IVX] *= -0.3f;
                else part[b + IX] = nx;
                float nz = part[b + IZ] + part[b + IVZ];
                if (partSolid(part[b + IX], part[b + IY], nz)) part[b + IVZ] *= -0.3f;
                else part[b + IZ] = nz;

                float ny = part[b + IY] + vy;
                if (vy < 0f && partSolid(part[b + IX], ny, part[b + IZ])) {
                    part[b + IY] = (float) Math.floor(ny) + 1.0f;
                    part[b + IVY] = vy * -0.30f;               // small bounce
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

        // --- Swing timer runs even if the player moved away mid-swing. ---
        if (this.swingT >= 0) {
            ++this.swingT;
            if (this.swingT > 12) this.swingT = -1;
        }
        boolean swinging = this.swingT >= 0;

        // --- Melee chase AI: walk at the player; in range, stand and
        //     swing the sword once per cooldown.  No daylight burning. ---
        if (player != null && !player.isDead()) {
            float dx = player.x - this.x;
            float dz = player.z - this.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist > 0.0001f) {
                this.headingX = dx / dist;
                this.headingZ = dz / dist;
            }
            if (!swinging) {
                if (dist > ATTACK_RANGE) {
                    this.xd += (this.headingX * WALK_SPEED - this.xd) * 0.4f;
                    this.zd += (this.headingZ * WALK_SPEED - this.zd) * 0.4f;
                    this.walkBob += 0.22f;
                } else {
                    this.xd *= 0.6f;
                    this.zd *= 0.6f;
                    this.walkBob *= 0.8f;
                    if (this.onGround && --this.attackCooldown <= 0) {
                        this.swingT = 0;                       // ONE swing
                        this.attackCooldown = 45 + rng.nextInt(30);
                    }
                }
            } else {
                this.xd *= 0.6f;
                this.zd *= 0.6f;
                this.walkBob *= 0.8f;
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

    /**
     * Seed the debris: every part pops away from the body centre (plus an
     * upward bias and jitter) with its own tumble spin.  Called once when
     * dying starts.  The head and the sword get an extra-hard kick.
     */
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
            if (i == 0 || i == PARTS - 1) kick += 0.05f;   // head & sword fly farthest
            part[b + IVX] = dirx * kick + (rng.nextFloat() - 0.5f) * 0.04f;
            part[b + IVZ] = dirz * kick + (rng.nextFloat() - 0.5f) * 0.04f;
            part[b + IVY] = 0.10f + rng.nextFloat() * 0.12f;
            part[b + IYAW] = rng.nextFloat() * 6.283f;
            part[b + IPIT] = (rng.nextFloat() - 0.5f) * 0.6f;
            part[b + IYAWR] = (rng.nextFloat() - 0.5f) * 0.50f;
            part[b + IPITR] = (rng.nextFloat() - 0.5f) * 0.45f;
        }
    }

    /** Point solid check for one debris piece (pieces are small). */
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
    /** Spin angles advance linearly per tick; extrapolate by alpha. */
    public float partYaw(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IYAW] + part[b + IYAWR] * alpha;
    }
    public float partPitch(int i, float alpha) {
        int b = i * STRIDE;
        return part[b + IPIT] + part[b + IPITR] * alpha;
    }

    // -------------------------------------------------------------------
    //  Bone drop: the skeleton's loot.  When a skeleton falls apart, a few
    //  of these pop out of the pile; they lie on the ground, magnet toward
    //  the player and are picked up on touch.  Physics mirrors
    //  DroppedBlock; rendering is a billboard on the /bone.bmp sprite.
    // -------------------------------------------------------------------
    public static class Bone {
        public float xo, yo, zo;
        public float x, y, z;
        public float xd, yd, zd;
        public boolean removed = false;

        private final ILevel level;
        private int age = 0;
        private int pickupDelay = 10;

        public Bone(ILevel level, float x, float y, float z,
                    float vx, float vy, float vz) {
            this.level = level;
            this.x = x;  this.y = y;  this.z = z;
            this.xo = x; this.yo = y; this.zo = z;
            this.xd = vx; this.yd = vy; this.zd = vz;
        }

        /** @return true when picked up by the player this tick. */
        public boolean tick(Player player) {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;

            // Lie around for a minute before despawning.
            if (++this.age > 20 * 60) { this.removed = true; return false; }

            if (this.pickupDelay > 0) --this.pickupDelay;

            if (player != null && !player.isDead() && this.pickupDelay <= 0) {
                // player.y is the EYE height (bb.y0 + 1.62) -- measure the
                // distance against the body centre instead, or a bone lying
                // at feet+1.15 could never come within the pickup radius.
                float dx = player.x - this.x;
                float dy = (player.y - 0.8f) - this.y;
                float dz = player.z - this.z;
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < 4.0f && distSq > 0.0001f) {
                    float inv = 1.0f / (float) Math.sqrt(distSq);
                    this.xd += dx * inv * 0.05f;
                    this.yd += dy * inv * 0.05f;
                    this.zd += dz * inv * 0.05f;
                }
                if (distSq < 0.36f) {
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

            if (this.y < -8f) this.removed = true;
            return false;
        }
    }
}
