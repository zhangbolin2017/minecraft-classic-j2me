package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

/**
 * ZiShu Huang (the Rat Emperor): summoned by an IRON-ORE cross topped
 * with a GOLD-ORE cap.  A stationary idol like the HaiZhu / YouJi: it
 * never moves after being summoned, takes exactly ONE damage per punch
 * (10 HP = 10 punches), and its death action is a rigid FORWARD topple
 * (the emperor's final kowtow, around the feet line).  Not targetable
 * by the Haizhuball.  No drops.
 */
public class ZiShu {
    public static final float HALF = 0.30f;
    public static final float HEIGHT = 1.35f;   // crown tip ~1.34
    private static final float GRAVITY = 0.04f;

    public final ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public boolean onGround = false;
    public boolean removed = false;
    public boolean dying = false;
    public int health = 10;      // ten punches at 1 damage each
    public int deathTime = 0;
    /** Forward fall during death: 0 = standing, PI/2 = flat on its face. */
    public float deathPitch = 0.0f;
    public float deathPitchPrev = 0.0f;
    public boolean deathLanded = false;
    public int lieTime = 0;

    public float headingX = 0.0f;
    public float headingZ = 1.0f;

    public ZiShu(ILevel level, float x, float y, float z) {
        this.level = level;
        this.x = x;   this.y = y;   this.z = z;
        this.xo = x;  this.yo = y;  this.zo = z;
    }

    public boolean hurt(int damage, Player source) {
        if (this.dying) return false;
        this.health -= damage;
        if (this.health <= 0) {
            this.dying = true;   // topple forward (the renderer interpolates)
            this.deathTime = 0;
            this.deathPitch = 0.0f;
            this.deathPitchPrev = 0.0f;
            this.deathLanded = false;
            this.lieTime = 0;
            this.xd = 0f; this.zd = 0f;
        }
        return true;   // stationary idol: punches never push it
    }

    public void tick(Player player) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        // Death: fall to the ground, then topple FORWARD around the feet
        // line (rigid rotation), lie flat for a second, then vanish.
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

        // Summoned idols NEVER move sideways: only gravity settles the feet.
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

    private boolean collides() {
        float w = HALF;
        int x0 = (int) Math.floor(this.x - w);
        int x1 = (int) Math.floor(this.x + w);
        int y0 = (int) Math.floor(this.y + 0.01f);
        int y1 = (int) Math.floor(this.y + 0.2f);   // only the feet collide
        int z0 = (int) Math.floor(this.z - w);
        int z1 = (int) Math.floor(this.z + w);
        for (int bx = x0; bx <= x1; ++bx)
            for (int by = y0; by <= y1; ++by)
                for (int bz = z0; bz <= z1; ++bz)
                    if (level.isSolidTile(bx, by, bz)) return true;
        return false;
    }
}
