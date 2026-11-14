package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * RED-mouse: a small bipedal mob that walks on two legs, holds a T-shaped
 * wooden crossbow, and shoots tiny arrows at the player from range.
 *
 * Every visible part is its own axis-aligned cube (head, ears, whiskers,
 * nose, body, two arms holding the crossbow, crossbow T-piece, legs,
 * feet, and tail) -- no spritesheet, just pure boxes like the netplayer.
 *
 * AI (stationary sentry):
 *   - NEVER walks: it stands exactly where it spawned; its shooting post
 *     never changes (no approach, no retreat -- the player makes the distance).
 *   - Turns to face the player and pivots the held-out crossbow up/down to
 *     aim; every ~1.6s when grounded it fires one arrow (arrows inherit the
 *     level, have gravity, damage on hit -- currently 0 damage).
 */
public class RedMouse {
    private static final Random rng = new Random();

    // --------- Collision / model constants ---------
    public  static final float HALF   = 0.22f;   // body half-width for hit-test
    public  static final float HEIGHT = 0.95f;   // total hitbox height
    private static final float GRAVITY  = 0.04f;

    // --------- Per-mob state ---------
    public final ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean onGround = false;
    public boolean removed  = false;
    public boolean dying    = false;
    public int health       = 4;
    public int deathTime    = 0;
    /** Backwards fall during death: 0 = standing, PI/2 = flat on the ground behind the feet. */
    public float deathPitch = 0.0f;
    /** deathPitch at the end of the previous tick, for smooth in-between rendering. */
    public float deathPitchPrev = 0.0f;
    /** True once the dying mouse has settled on the ground (falling done). */
    public boolean deathLanded = false;
    /** Ticks since the corpse settled: 0..10 fall over, 10..30 lie flat (1s), then planks. */
    public int lieTime = 0;

    // Heading / aim: heading is the body yaw (body forward = +Z local),
    // aimPitch is the up/down tilt of the crossbow arm in radians.
    public float headingX = 0.0f;
    public float headingZ = 1.0f;
    public float aimPitch = 0.0f;

    // Timers
    public  float walkBob = 0.0f;   // leg swing phase, settles to 0 (no walking)
    private int fireCooldown = 60 + rng.nextInt(40);

    public RedMouse(ILevel level, float x, float y, float z) {
        this.level = level;
        this.x = x;   this.y = y;   this.z = z;
        this.xo = x;  this.yo = y;  this.zo = z;
        this.headingX = rng.nextFloat() - 0.5f;
        this.headingZ = rng.nextFloat() - 0.5f;
        float l = (float) Math.sqrt(headingX * headingX + headingZ * headingZ);
        if (l < 0.0001f) { headingX = 0f; headingZ = 1f; }
        else { headingX /= l; headingZ /= l; }
    }

    public boolean hurt(int damage, Player source) {
        if (this.dying) return false;
        this.health -= damage;
        if (this.health <= 0) {
            this.dying = true;
            this.deathTime = 0;
            this.deathPitch = 0.0f;
            this.deathPitchPrev = 0.0f;
            this.deathLanded = false;
            this.lieTime = 0;
            this.xd = 0f; this.zd = 0f;
        }
        // Knock back a little when hit (away from the attacker); the sentry
        // never walks on its own, so it just slides and settles at the new
        // post (handled in tick()'s knockback-slide section).
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

    /** Create one arrow projectile in the world, aimed at the player. */
    private void fireArrow(Player player, java.util.Vector arrows) {
        if (player == null) return;
        // Muzzle at the far end of the held-out crossbow (the crossbar end
        // that points at the player, ~0.55 blocks in front of the chest).
        float mx = this.x + headingX * 0.55f;
        float my = this.y + 0.78f;
        float mz = this.z + headingZ * 0.55f;
        // Aim toward player eye (y + 1.0) with a small lead.
        float tx = player.x - mx;
        float ty = (player.y + 1.0f) - my;
        float tz = player.z - mz;
        // Arrow speed ~0.28 / tick with slight arc compensation (add 1.5% of
        // gravity distance to the aim height so the shot doesn't always land
        // at the feet).
        float distSq = tx * tx + tz * tz;
        float dist   = (float) Math.sqrt(distSq);
        if (dist < 0.0001f) return;
        float time   = dist / 0.28f;                  // ~ ticks of flight
        float ty2    = ty + 0.5f * GRAVITY * time * time * 0.5f;
        float tlen   = (float) Math.sqrt(tx * tx + ty2 * ty2 + tz * tz);
        float spd    = 0.28f;
        float ax = tx / tlen * spd;
        float ay = ty2 / tlen * spd;
        float az = tz / tlen * spd;
        arrows.addElement(new Arrow(level, mx, my, mz, ax, ay, az, 0));
    }

    public void tick(Player player, java.util.Vector arrows) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        // --- Death: fall to the ground first, then fall BACKWARDS (rigid
        //     rotation, the body keeps its shape -- no squash), lie flat for
        //     a full second so the player can look at the corpse, and only
        //     then disappear (GameCanvas turns it into two plank drops). ---
        if (this.dying) {
            this.yd -= GRAVITY;
            this.y  += this.yd;
            if (this.yd < 0f && collides()) {
                this.y = (int) Math.floor(this.y) + 1.0f;
                this.yd = 0f;
                this.deathLanded = true;
            } else if (this.yd > 0f && collides()) {
                this.yd = 0f;
            }
            if (this.y < -8f) { this.removed = true; return; }
            ++this.deathTime;
            if (this.deathLanded) {
                this.deathPitchPrev = this.deathPitch;
                // Rigid backward fall: pitch 0 -> PI/2 across 10 ticks.
                this.deathPitch = Math.min(
                        this.deathPitch + 0.5f * 3.14159f / 10f,
                        0.5f * 3.14159f);
                // 10 ticks falling over + 20 ticks lying flat (1 second),
                // then the corpse is removed and becomes two planks.
                if (++this.lieTime >= 30) this.removed = true;
            }
            return;
        }

        // --- Stationary sentry AI: the mouse NEVER walks.  It stands
        //     exactly where it spawned (its shooting post never changes),
        //     turns to face the player and fires arrows from range. ---
        if (player != null && !player.isDead()) {
            float dx = player.x - this.x;
            float dz = player.z - this.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            // Face the player continuously (body yaw + aim pitch, pivoting
            // the crossbow at fist height).
            if (dist > 0.0001f) {
                this.headingX = dx / dist;
                this.headingZ = dz / dist;
                float dyAim = (player.y + 1.0f) - (this.y + 0.78f);
                this.aimPitch = (float) Math.atan2(dyAim, Math.max(dist, 0.01f));
            }
            // Fire when grounded and cooldown has expired.  No chase: it
            // shoots from its fixed post at anything within reach.
            if (this.onGround && --this.fireCooldown <= 0 && dist < 12.0f) {
                fireArrow(player, arrows);
                this.fireCooldown = 90 + rng.nextInt(40);
            }
        }

        // Knockback slide: the sentry never walks by itself, but being hit
        // pushes it back a little; friction settles it at the new post.
        float step = this.xd;
        this.x += step;
        if (collides()) { this.x -= step; this.xd = 0f; }
        step = this.zd;
        this.z += step;
        if (collides()) { this.z -= step; this.zd = 0f; }
        this.xd *= 0.80f;
        this.zd *= 0.80f;

        // Gravity (plus the knockback slide above; no self-walking).
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

        // No walking -> walk bob settles back to a static pose.
        this.walkBob *= 0.8f;
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

    // -------------------------------------------------------------------
    //  Simple arrow projectile spawned by RedMouse.fireArrow().
    //  Lives in the same GameCanvas-managed Vector as mobs and drops.
    // -------------------------------------------------------------------
    public static class Arrow {
        public final ILevel level;
        public float xo, yo, zo;
        public float x, y, z;
        public float xd, yd, zd;
        public boolean removed = false;
        public final int damage;
        private int age = 0;

        public Arrow(ILevel level, float x, float y, float z,
                     float vx, float vy, float vz, int damage) {
            this.level = level;
            this.x = x; this.y = y; this.z = z;
            this.xo = x; this.yo = y; this.zo = z;
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.damage = damage;
        }

        /** @return true when it hit the player this tick. */
        public boolean tick(Player player) {
            if (this.removed) return false;
            if (++this.age > 20 * 6) { this.removed = true; return false; }
            this.xo = this.x; this.yo = this.y; this.zo = this.z;
            this.yd -= GRAVITY * 0.5f;

            // Move step by step so small arrows don't tunnel through walls.
            float steps = 4f;
            float sx = this.xd / steps;
            float sy = this.yd / steps;
            float sz = this.zd / steps;
            for (int s = 0; s < steps; ++s) {
                this.x += sx;
                if (hitsSolid()) { this.x -= sx; this.removed = true; return false; }
                this.y += sy;
                if (hitsSolid()) { this.y -= sy; this.removed = true; return false; }
                this.z += sz;
                if (hitsSolid()) { this.z -= sz; this.removed = true; return false; }
            }

            // Hit test against the player's body AABB.
            if (player != null && !player.isDead()) {
                float hw = 0.3f;
                float px0 = player.x - hw, px1 = player.x + hw;
                float py0 = player.y,      py1 = player.y + 1.6f;
                float pz0 = player.z - hw, pz1 = player.z + hw;
                if (this.x >= px0 && this.x <= px1 &&
                    this.y >= py0 && this.y <= py1 &&
                    this.z >= pz0 && this.z <= pz1) {
                    this.removed = true;
                    return true;
                }
            }
            return false;
        }

        private boolean hitsSolid() {
            int bx = (int) Math.floor(this.x);
            int by = (int) Math.floor(this.y);
            int bz = (int) Math.floor(this.z);
            return level.isSolidTile(bx, by, bz);
        }
    }

    // -------------------------------------------------------------------
    //  The T-crossbow that slips out of a dying RedMouse's hands and
    //  clatters to the ground nearby.  It NEVER despawns on its own --
    //  punch it (crosshair + attack) to turn it into wooden planks.
    // -------------------------------------------------------------------
    public static class BowDrop {
        public final ILevel level;
        public float x, y, z;
        public float xd, yd, zd;
        public float headingX, headingZ;   // yaw the bow lies at
        public boolean resting = false;
        public boolean removed = false;

        public BowDrop(ILevel level, float x, float y, float z,
                       float hx, float hz,
                       float vx, float vy, float vz) {
            this.level = level;
            this.x = x; this.y = y; this.z = z;
            this.xd = vx; this.yd = vy; this.zd = vz;
            this.headingX = hx; this.headingZ = hz;
        }

        public void tick() {
            if (this.removed) return;
            if (this.resting) {
                // Resume falling if the block underneath got mined away.
                int bx = (int) Math.floor(this.x);
                int by = (int) Math.floor(this.y - 0.05f);
                int bz = (int) Math.floor(this.z);
                if (!level.isSolidTile(bx, by, bz)) this.resting = false;
                return;
            }
            this.yd -= 0.04f;
            // Axis-separated movement so the bow never tunnels into walls.
            this.x += this.xd;
            if (hitsSolid()) { this.x -= this.xd; this.xd = 0f; }
            this.z += this.zd;
            if (hitsSolid()) { this.z -= this.zd; this.zd = 0f; }
            this.y += this.yd;
            if (this.yd < 0f && hitsSolid()) {
                this.y = (int) Math.floor(this.y) + 1.0f;
                this.yd = 0f; this.xd = 0f; this.zd = 0f;
                this.resting = true;                    // lies there for good
            } else if (this.yd > 0f && hitsSolid()) {
                this.yd = 0f;
            }
            if (this.y < -8f) this.removed = true;
        }

        private boolean hitsSolid() {
            return level.isSolidTile((int) Math.floor(this.x),
                                     (int) Math.floor(this.y),
                                     (int) Math.floor(this.z));
        }

        /** Approximate radius for the crosshair ray hit-test. */
        public float hitRadius() { return 0.35f; }
    }
}
