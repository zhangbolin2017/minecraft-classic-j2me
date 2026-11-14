package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * Forward: the v1 finale monster -- a tall charcoal pillar with glowing
 * red eyes and a crimson energy crack.  It NEVER spawns naturally: when
 * the player fells a summoned idol (HaiZhu / YouJi / ZiShu) there is a
 * chance it CRAWLS OUT OF THE GROUND where the idol died, then hunts
 * the player.  50 HP at 1 damage per punch (50 punches), each of its
 * own swings deals 5 damage.  Death = a rigid topple, no drops.
 *
 * Phases:
 *   crawling -- rises 2 blocks up out of the ground, no AI, no gravity;
 *               GameCanvas sprinkles dirt particles at its feet.
 *   active   -- Skeleton-style melee chase (hop up 1-block steps), one
 *               lunge per cooldown; the lunge tick deals 5 damage.
 *   dying    -- falls over backward around the feet line (YouJi style),
 *               lies flat for a moment, then vanishes.  No drops.
 */
public class Forward {
    private static final Random rng = new Random();

    public  static final float HALF   = 0.25f;
    public  static final float HEIGHT = 2.00f;
    private static final float GRAVITY = 0.04f;
    private static final float WALK_SPEED = 0.045f;
    private static final float ATTACK_RANGE = 1.6f;

    public static final int ATTACK_DAMAGE = 5;      // a fifth of the player's HP
    public static final int MAX_HEALTH = 50;        // 50 punches at 1 dmg

    public final ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean onGround = false;
    public boolean removed  = false;
    public boolean dying    = false;
    public int health       = MAX_HEALTH;
    public int deathTime    = 0;

    /** Crawl-out phase: rises until y reaches crawlTargetY. */
    public boolean crawling = true;
    public float crawlTargetY;
    private int crawlDust = 0;

    /** Backward topple during death (YouJi-style): 0 = up, PI/2 = flat. */
    public float deathPitch = 0.0f;
    public float deathPitchPrev = 0.0f;
    public boolean deathLanded = false;
    public int lieTime = 0;

    public float headingX = 0.0f;
    public float headingZ = 1.0f;

    /** Ticks since the lunge started, -1 = idle.  A lunge lasts 12 ticks. */
    public int swingT = -1;
    private int attackCooldown = 40 + rng.nextInt(30);

    public Forward(ILevel level, float x, float y, float z, float crawlTargetY) {
        this.level = level;
        this.x = x;   this.y = y;   this.z = z;
        this.xo = x;  this.yo = y;  this.zo = z;
        this.crawlTargetY = crawlTargetY;
    }

    public boolean hurt(int damage, Player source) {
        if (this.dying || this.crawling) return false;
        this.health -= damage;
        if (this.health <= 0) {
            this.health = 0;
            this.dying = true;
            this.deathTime = 0;
            this.deathPitch = 0.0f;
            this.deathPitchPrev = 0.0f;
            this.deathLanded = false;
            this.lieTime = 0;
            this.xd = 0f; this.zd = 0f;
            this.swingT = -1;
        }
        // Knock back from the attacker so 50 punches stay survivable.
        if (source != null) {
            float dx = this.x - source.x;
            float dz = this.z - source.z;
            float dl = (float) Math.sqrt(dx * dx + dz * dz);
            if (dl > 0.0001f) {
                this.xd += dx / dl * 0.12f;
                this.zd += dz / dl * 0.12f;
            }
        }
        return true;
    }

    /** True on the single tick mid-lunge, when the blow connects. */
    public boolean isSlashTick() {
        return this.swingT == 4;
    }

    /** Lunge lean (radians) for rendering: a short forward dip. */
    public float lungePitch(float alpha) {
        if (this.swingT < 0) return 0f;
        float t = this.swingT + alpha;
        if (t < 3f)  return -0.35f * (t / 3f);
        if (t < 6f)  return -0.35f + 0.55f * ((t - 3f) / 3f);
        if (t < 12f) return 0.20f - 0.20f * ((t - 6f) / 6f);
        return 0f;
    }

    public void tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        // --- Phase 1: crawl out of the ground. ---
        if (this.crawling) {
            this.y += 0.035f;
            this.headingX = 0f; this.headingZ = 1f;
            if (this.y >= this.crawlTargetY) {
                this.y = this.crawlTargetY;
                this.crawling = false;
            }
            return;
        }

        // --- Phase 3: death = rigid backward topple, then gone. ---
        if (this.dying) {
            this.yd -= GRAVITY;
            this.y += this.yd;
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
                this.deathPitch = Math.min(
                        this.deathPitch + 0.5f * 3.14159f / 10f,
                        0.5f * 3.14159f);
                if (++this.lieTime >= 20) this.removed = true;
            }
            return;
        }

        // --- Lunge timer runs even if the player moved away mid-lunge. ---
        if (this.swingT >= 0) {
            ++this.swingT;
            if (this.swingT > 12) this.swingT = -1;
        }
        boolean swinging = this.swingT >= 0;

        // --- Phase 2: melee chase (Skeleton style). ---
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
                } else {
                    this.xd *= 0.6f;
                    this.zd *= 0.6f;
                    if (this.onGround && --this.attackCooldown <= 0) {
                        this.swingT = 0;
                        this.attackCooldown = 50 + rng.nextInt(30);
                    }
                }
            } else {
                this.xd *= 0.6f;
                this.zd *= 0.6f;
            }
        } else {
            this.xd *= 0.8f;
            this.zd *= 0.8f;
        }

        // --- Horizontal move with collision; hop up 1-block steps. ---
        float step = this.xd;
        this.x += step;
        if (collides()) {
            this.x -= step;
            if (this.onGround && this.yd <= 0f) this.yd = 0.30f;
            this.xd = 0f;
        }
        step = this.zd;
        this.z += step;
        if (collides()) {
            this.z -= step;
            if (this.onGround && this.yd <= 0f) this.yd = 0.30f;
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
}
