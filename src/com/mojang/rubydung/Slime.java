package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * A slime mob: a small textured cube that idles on the ground, then hops
 * toward the player. Its six faces are sampled from /slime.bmp.
 *
 * The heading is kept as a unit vector instead of an angle so no atan2 is
 * needed (CLDC 1.1 has no Math.atan2).
 */
public class Slime {
    private static final Random random = new Random();

    /** Half extent of the body in blocks (0.6 blocks wide and tall). */
    public static final float HALF = 0.3f;

    /** Horizontal hop impulse per tick: total drift is ~0.5 blocks per hop
     *  (slow crawl -- was 0.10, which zipped ~2.9 blocks per hop). */
    private static final float HOP_SPEED = 0.02f;

    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean removed = false;

    /** Unit heading; the model's local +Z (its front) points this way. */
    public float headingX = 0.0f;
    public float headingZ = -1.0f;

    private final ILevel level;
    private int hopTimer;
    private boolean onGround = false;

    /** Death animation: squashing to a pancake, then removed. */
    public boolean dying = false;
    public float squash = 1.0f;   // 1 = normal, animates down to 0.08
    private int deathTime = 0;

    /** Jelly animation: rises > 1 stretches tall, < 1 squashes wide. */
    public float stretch = 1.0f;

    public Slime(ILevel level, float x, float y, float z) {
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hopTimer = 5 + random.nextInt(20);
    }

    public void tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.dying) {
            // First finish falling to the ground so the corpse doesn't hover.
            this.yd -= 0.04f;
            this.y += this.yd;
            if (this.yd < 0.0f) {
                int feet = (int) Math.floor(this.y);
                if (collides()) {
                    this.y = feet + 1.0f;
                    this.yd = 0.0f;
                }
            } else if (collides()) {
                this.y = (int) Math.floor(this.y + HALF * 2.0f) - HALF * 2.0f;
                this.yd = 0.0f;
            }
            if (this.y < -8.0f) { this.removed = true; return; }
            // Only once landed do we start the ~0.1s/tick squash countdown so
            // the body visibly settles on the ground before flattening away.
            // Squash ramp:  1 -> 0.08  over  8 ticks  (~0.13s per step).
            ++this.deathTime;
            float f = 1.0f - this.deathTime * 0.12f;
            this.squash = f > 0.08f ? f : 0.08f;
            if (this.deathTime >= 10) this.removed = true;
            return;
        }

        this.yd -= 0.04f;
        if (this.onGround && --this.hopTimer <= 0) hop(player);

        // Resolve X and Z separately so a wall on one axis does not block
        // movement along the other.
        float step = this.xd;
        this.x += step;
        if (collides()) { this.x -= step; this.xd = 0.0f; }

        step = this.zd;
        this.z += step;
        if (collides()) { this.z -= step; this.zd = 0.0f; }

        this.xd *= 0.98f;
        this.zd *= 0.98f;

        this.y += this.yd;
        if (this.yd < 0.0f) {
            int feet = (int) Math.floor(this.y);
            if (collides()) {
                this.y = feet + 1.0f;
                this.yd = 0.0f;
                this.xd = 0.0f;
                this.zd = 0.0f;
                this.onGround = true;
            } else {
                this.onGround = false;
            }
        } else {
            if (collides()) {
                this.y = (int) Math.floor(this.y + HALF * 2.0f) - HALF * 2.0f;
                this.yd = 0.0f;
            }
            this.onGround = false;
        }

        if (this.y < -8.0f) this.removed = true;

        // Jelly squash & stretch: launching slimes elongate, landing/falling
        // ones flatten; the value eases back to 1 while resting.
        float target = 1.0f + this.yd * 0.9f;
        this.stretch += (target - this.stretch) * 0.35f;
    }

    /** Hit points; two punches from the player kill it. */
    public int health = 4;

    /** Take damage from the player: knock back, then squash-die at 0 hp. */
    public void hurt(int amount, Player player) {
        if (this.removed || this.dying) return;
        this.health -= amount;
        if (this.health <= 0) {
            this.dying = true;
            this.deathTime = 0;
            this.xd = 0.0f;
            this.zd = 0.0f;
            return;
        }
        // Knock back away from the attacker.
        float dx = this.x - (player != null ? player.x : this.x);
        float dz = this.z - (player != null ? player.z : this.z);
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len > 0.001f) {
            this.xd = dx / len * 0.20f;
            this.zd = dz / len * 0.20f;
        }
        this.yd = 0.20f;
    }

    /** Pick a heading toward the player (or a random wander) and launch. */
    private void hop(Player player) {
        float dx = 0.0f, dz = 0.0f;
        if (player != null) {
            dx = player.x - this.x;
            dz = player.z - this.z;
        }
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0f) {
            dx = random.nextFloat() * 2.0f - 1.0f;
            dz = random.nextFloat() * 2.0f - 1.0f;
            len = (float) Math.sqrt(dx * dx + dz * dz);
        }
        if (len > 0.0001f) {
            this.headingX = dx / len;
            this.headingZ = dz / len;
        }
        // Launch strong enough to hop up a one-block step.
        this.xd = this.headingX * HOP_SPEED;
        this.zd = this.headingZ * HOP_SPEED;
        this.yd = 0.42f;
        this.hopTimer = 20 + random.nextInt(24);
    }

    /** True when the body box overlaps any solid tile. */
    private boolean collides() {
        int x0 = (int) Math.floor(this.x - HALF);
        int x1 = (int) Math.floor(this.x + HALF);
        int z0 = (int) Math.floor(this.z - HALF);
        int z1 = (int) Math.floor(this.z + HALF);
        int y0 = (int) Math.floor(this.y + 0.02f);
        int y1 = (int) Math.floor(this.y + HALF * 2.0f - 0.02f);
        for (int bx = x0; bx <= x1; ++bx) {
            for (int by = y0; by <= y1; ++by) {
                for (int bz = z0; bz <= z1; ++bz) {
                    if (level.isSolidTile(bx, by, bz)) return true;
                }
            }
        }
        return false;
    }
}
