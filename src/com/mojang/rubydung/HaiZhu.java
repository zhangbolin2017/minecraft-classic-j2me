package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

import java.util.Random;

/**
 * HaiZhu (the summoned pig-head-on-a-carpet mob).
 *
 * Model (box style): one FLAT CARPET cube under it (its "feet"), one big
 * pig-HEAD cube hovering above (with snout/eye detail cubes), and one GREEN
 * cube sticking out of its mouth.
 *
 * Behaviour: it never spawns naturally.  It is SUMMONED by building a dirt
 * cross (crucifix shape): the dirt blocks vanish and this mob appears in
 * their place.  It then holds PERFECTLY STILL where it was summoned (no
 * walking, no hopping -- only the carpet's gentle hover bob), attacks
 * nothing, and takes exactly one damage per punch -- 10 HP means ten
 * punches to kill.  On death it SHRINKS continuously until nothing is left.
 */
public class HaiZhu {
    private static final Random rng = new Random();

    public static final float HALF   = 0.35f;   // wide, the carpet slides
    public static final float HEIGHT = 1.05f;   // carpet + hovering head
    private static final float GRAVITY = 0.02f; // gentle: it settles, then hovers
    /** Death: shrink over this many ticks, then gone. */
    public static final int DEATH_TICKS = 20;

    public final ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean onGround = false;
    public boolean removed  = false;
    public boolean dying    = false;
    public int health       = 10;      // one damage per punch: 10 punches
    public int deathTime    = 0;

    /** Facing (kept for the renderer; the mob itself never moves). */
    public float headingX = 0.0f;
    public float headingZ = 1.0f;
    /** Hover-bob phase for the carpet. */
    public float bob = 0.0f;

    public HaiZhu(ILevel level, float x, float y, float z) {
        this.level = level;
        this.x = x;   this.y = y;   this.z = z;
        this.xo = x;  this.yo = y;  this.zo = z;
    }

    public boolean hurt(int damage, Player source) {
        if (this.dying) return false;
        this.health -= damage;
        if (this.health <= 0) {
            this.dying = true;   // the death pose is drawn by the renderer
            this.deathTime = 0;
            this.xd = 0f; this.zd = 0f;
        }
        // Stationary idol: punches do NOT push it around.
        return true;
    }

    /** 1 -> 0 across the death shrink; 1 while alive. */
    public float scale() {
        if (!this.dying) return 1.0f;
        float s = 1.0f - (float) this.deathTime / (float) DEATH_TICKS;
        return s < 0.0f ? 0.0f : s;
    }

    public void tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.bob += 0.06f;

        // --- Death: hold still and SHRINK until nothing is left. ---
        if (this.dying) {
            ++this.bob;                              // keep the pose alive
            if (++this.deathTime >= DEATH_TICKS) this.removed = true;
            return;
        }

        // Summoned idols NEVER move sideways: no glide, no hopping, no
        // knockback.  Only gravity settles the carpet onto the ground.

        // Gentle gravity; the renderer adds a hover bob above the ground.
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
        } else if (collides()) {
            this.y = (int) Math.floor(this.y + HEIGHT) - HEIGHT;
            this.yd = 0f;
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
        int y1 = (int) Math.floor(this.y + 0.2f);   // only the carpet collides
        int z0 = (int) Math.floor(this.z - w);
        int z1 = (int) Math.floor(this.z + w);
        for (int bx = x0; bx <= x1; ++bx)
            for (int by = y0; by <= y1; ++by)
                for (int bz = z0; bz <= z1; ++bz)
                    if (level.isSolidTile(bx, by, bz)) return true;
        return false;
    }
}
