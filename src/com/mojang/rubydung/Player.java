package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;
import com.mojang.rubydung.level.InfiniteLevel;
import com.mojang.rubydung.level.tile.Tile;
import com.mojang.rubydung.phys.AABB;

import java.util.Random;
import java.util.Vector;

/**
 * Survival-mode player, ported toward Minecraft Alpha 1.0.1_01 behaviour:
 *   - 20 health points (10 hearts), with a short invulnerability window
 *   - fall damage (ceil(fallDistance - 3))
 *   - lava damage and an air supply that drowns the player underwater
 *   - swimming buoyancy in water/lava
 *   - death + automatic respawn at a fresh spawn point
 */
public class Player {
    private static final Random random = new Random();
    public static final int MAX_HEALTH = 20;
    public static final int MAX_AIR = 300;
    private static final int RESPAWN_DELAY = 40;

    private ILevel level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public float yRot;
    public float xRot;
    public AABB bb;
    public boolean onGround = false;

    // Input state, driven by the Canvas key handler.
    public boolean kForward, kBack, kLeft, kRight, kJump, kReset;
    /** Flying Wing: set by the game each tick while the wing is selected,
     *  charged and jump held -- grants a steady climb. */
    public boolean wingFly = false;
    /** A descent that started from wing flight: fall damage is skipped
     *  until the next landing. */
    public boolean wingGlide = false;

    // ---- Survival state ----
    public int health = MAX_HEALTH;
    public int air = MAX_AIR;
    public int hurtTime = 0;        // counts down; nonzero = recently hurt
    public int invulnerableTime = 0; // damage immunity frames after a hit
    public int deathTime = 0;       // counts up once dead
    public int deathCount = 0;
    public float fallDistance = 0.0f;
    public boolean inWater = false;
    public boolean inLava = false;
    private int airHurtTimer = 0;

    public Player(ILevel level) {
        this.level = level;
        resetPos();
    }

    public void resetPos() {
        int w = this.level.getWidth();
        int h = this.level.getHeight();
        int d = this.level.getDepth();

        int bestScore = -1;
        int bestX = 0, bestZ = 0, bestY = d / 2;

        for (int i = 0; i < 200; ++i) {
            int ix;
            int iz;
            if (w > 2 && h > 2) {
                ix = 1 + random.nextInt(w - 2);
                iz = 1 + random.nextInt(h - 2);
            } else {
                ix = random.nextInt(256) - 128;
                iz = random.nextInt(256) - 128;
            }

            int sy = findSurfaceSolidY(ix, iz, d);
            if (sy < 1) continue;
            if (level instanceof InfiniteLevel) {
                int surface = ((InfiniteLevel) level).estimateSurfaceY(ix, iz);
                if (sy < surface - 2) continue;
            }

            if (!isSpawnClear(ix, sy, iz, d)) continue;
            if (!level.isLit(ix, sy + 1, iz)) continue;

            int score = shoreScore(ix, sy, iz);
            if (score > bestScore) {
                bestScore = score;
                bestX = ix;
                bestZ = iz;
                bestY = sy;
                if (bestScore >= 6) break;
            }
        }

        float sx = bestX + 0.5f;
        float sz = bestZ + 0.5f;
        float sy = bestY + 2.0f;
        setPos(sx, sy, sz);
    }

    private void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        float w = 0.3f;
        float h = 0.9f;
        this.bb = new AABB(x - w, y - h, z - w, x + w, y + h, z + w);
    }

    public void teleportTo(float x, float y, float z) {
        setPos(x, y, z);
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.xd = 0.0f;
        this.yd = 0.0f;
        this.zd = 0.0f;
        this.onGround = false;
        this.fallDistance = 0.0f;
    }

    public void setRotation(float yRot, float xRot) {
        this.yRot = yRot;
        this.xRot = xRot;
        if (this.xRot < -90.0f) this.xRot = -90.0f;
        if (this.xRot > 90.0f) this.xRot = 90.0f;
    }

    private int findSurfaceSolidY(int x, int z, int d) {
        for (int y = d - 1; y > 0; --y) {
            if (level.isSolidTile(x, y, z)) return y;
        }
        return -1;
    }

    private boolean isSpawnClear(int x, int surfaceY, int z, int d) {
        int top = surfaceY + 3;
        if (top >= d) return false;
        if (isLiquid(level.getTile(x, surfaceY, z))) return false;
        for (int y = surfaceY + 1; y <= top; ++y) {
            int id = level.getTile(x, y, z);
            if (id != 0) return false;
        }
        return true;
    }

    private int shoreScore(int x, int y, int z) {
        int score = 0;
        int r = 4;
        for (int dz = -r; dz <= r; ++dz) {
            for (int dx = -r; dx <= r; ++dx) {
                if (dx == 0 && dz == 0) continue;
                int id0 = level.getTile(x + dx, y, z + dz);
                int id1 = level.getTile(x + dx, y + 1, z + dz);
                if (isWater(id0) || isWater(id1)) score++;
            }
        }
        return score;
    }

    private boolean isLiquid(int id) {
        return isWater(id) || isLava(id);
    }

    private boolean isWater(int id) {
        return (Tile.water != null && id == Tile.water.id) ||
               (Tile.stillWater != null && id == Tile.stillWater.id);
    }

    private boolean isLava(int id) {
        return (Tile.lava != null && id == Tile.lava.id) ||
               (Tile.stillLava != null && id == Tile.stillLava.id);
    }

    /** Reset the player back to full health for a new game. */
    public void revive() {
        this.health = MAX_HEALTH;
        this.air = MAX_AIR;
        this.hurtTime = 0;
        this.invulnerableTime = 0;
        this.deathTime = 0;
        this.fallDistance = 0.0f;
        this.xd = this.yd = this.zd = 0.0f;
        resetPos();
    }

    private void die() {
        if (this.health <= 0 && this.deathTime > 0) return;
        this.health = 0;
        this.deathTime = 0;
        this.deathCount++;
    }

    public boolean isDead() {
        return this.health <= 0;
    }

    public void turn(float xo, float yo) {
        this.yRot = (float) ((double) this.yRot + (double) xo * 0.15);
        this.xRot = (float) ((double) this.xRot - (double) yo * 0.15);
        if (this.xRot < -90.0f) this.xRot = -90.0f;
        if (this.xRot > 90.0f) this.xRot = 90.0f;
    }

    /** Apply damage with the Alpha-style invulnerability window. */
    public void hurt(int amount) {
        if (this.health <= 0) return;
        if (this.invulnerableTime > 0) return;
        this.health -= amount;
        this.hurtTime = 10;
        this.invulnerableTime = 10;
        if (this.health <= 0) die();
    }

    public void heal(int amount) {
        if (this.health <= 0) return;
        this.health += amount;
        if (this.health > MAX_HEALTH) this.health = MAX_HEALTH;
    }

    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.hurtTime > 0) --this.hurtTime;
        if (this.invulnerableTime > 0) --this.invulnerableTime;

        if (this.health <= 0) {
            // Dead: stop accepting input, fall limp, then auto-respawn.
            ++this.deathTime;
            this.yd = (float) ((double) this.yd - 0.005);
            move(0.0f, this.yd, 0.0f);
            this.yd *= 0.98f;
            if (this.deathTime >= RESPAWN_DELAY) revive();
            return;
        }

        float xa = 0.0f;
        float ya = 0.0f;
        if (kReset) {
            resetPos();
        }
        // Camera forward is +Z (see renderer/raycast), so forward/back input
        // must use the same sign to avoid inverted movement.
        if (kForward) ya += 1.0f;
        if (kBack) ya -= 1.0f;
        if (kLeft) xa -= 1.0f;
        if (kRight) xa += 1.0f;

        this.inWater = isInLiquid(Tile.COLLIDE_WATER);
        this.inLava = isInLiquid(Tile.COLLIDE_LAVA);

        if (this.inWater || this.inLava) {
            // Swimming: slower, with buoyancy when jump is held.
            float drag = this.inLava ? 0.5f : 0.8f;
            moveRelative(xa, ya, 0.02f);
            this.yd = (float) ((double) this.yd - 0.02);
            if (kJump) this.yd += 0.04f;
            move(this.xd, this.yd, this.zd);
            this.xd *= drag;
            this.yd *= drag;
            this.zd *= drag;
        } else {
            if (kJump && this.onGround) {
                this.yd = 0.12f;
            }
            if (this.wingFly) {
                // Flying Wing: holding jump lifts the player steadily
                // (gravity is tiny, so a small velocity suffices).
                this.yd = 0.10f;
                this.wingGlide = true;
            }
            moveRelative(xa, ya, this.onGround ? 0.02f : 0.005f);
            this.yd = (float) ((double) this.yd - 0.005);
            move(this.xd, this.yd, this.zd);
            this.xd *= 0.91f;
            this.yd *= 0.98f;
            this.zd *= 0.91f;
            if (this.onGround) {
                this.xd *= 0.8f;
                this.zd *= 0.8f;
            }
        }
        if (this.wingGlide) {
            // A glide that began with the wing never deals fall damage;
            // the protection ends on the next landing.
            this.fallDistance = 0.0f;
            if (this.onGround) this.wingGlide = false;
        }

        tickEnvironment();
    }

    /** Air supply, drowning, lava burn and void death (Alpha-style). */
    private void tickEnvironment() {
        if (this.inWater) {
            --this.air;
            if (this.air <= 0) {
                this.air = 0;
                // Out of air: take 2 damage about once a second.
                if (++this.airHurtTimer >= 20) {
                    this.airHurtTimer = 0;
                    hurt(2);
                }
            }
        } else {
            this.air = MAX_AIR;
            this.airHurtTimer = 0;
        }

        if (this.inLava) {
            // Lava is brutal: continuous burn while submerged.
            if (this.invulnerableTime == 0) hurt(4);
        }

        if (this.y < -64.0f) {
            // Fell out of the world.
            die();
        }
    }

    private boolean isInLiquid(int collideKind) {
        int x0 = (int) (this.bb.x0 + 0.001f);
        int x1 = (int) (this.bb.x1 - 0.001f);
        int y0 = (int) (this.bb.y0 + 0.001f);
        int y1 = (int) (this.bb.y1 - 0.001f);
        int z0 = (int) (this.bb.z0 + 0.001f);
        int z1 = (int) (this.bb.z1 - 0.001f);
        for (int x = x0; x <= x1; ++x) {
            for (int y = y0; y <= y1; ++y) {
                for (int z = z0; z <= z1; ++z) {
                    Tile t = Tile.tiles[this.level.getTile(x, y, z)];
                    if (t == null) continue;
                    if (collideKind == Tile.COLLIDE_WATER && t.collide == Tile.COLLIDE_WATER) return true;
                    if (collideKind == Tile.COLLIDE_LAVA && t.collide == Tile.COLLIDE_LAVA) return true;
                }
            }
        }
        return false;
    }

    public void move(float xa, float ya, float za) {
        float xaOrg = xa;
        float yaOrg = ya;
        float zaOrg = za;
        Vector aABBs = this.level.getCubes(this.bb.expand(xa, ya, za));
        for (int i = 0; i < aABBs.size(); ++i) {
            ya = ((AABB) aABBs.elementAt(i)).clipYCollide(this.bb, ya);
        }
        this.bb.move(0.0f, ya, 0.0f);
        for (int i = 0; i < aABBs.size(); ++i) {
            xa = ((AABB) aABBs.elementAt(i)).clipXCollide(this.bb, xa);
        }
        this.bb.move(xa, 0.0f, 0.0f);
        for (int i = 0; i < aABBs.size(); ++i) {
            za = ((AABB) aABBs.elementAt(i)).clipZCollide(this.bb, za);
        }
        this.bb.move(0.0f, 0.0f, za);
        boolean wasOnGround = this.onGround;
        this.onGround = yaOrg != ya && yaOrg < 0.0f;

        // ---- Fall damage accounting ----
        if (this.health > 0) {
            if (this.onGround) {
                if (!wasOnGround) {
                    causeFallDamage(this.fallDistance);
                }
                this.fallDistance = 0.0f;
            } else if (ya < 0.0f) {
                // Only count real downward movement while airborne.
                this.fallDistance += -ya;
            } else if (yaOrg > 0.0f) {
                this.fallDistance = 0.0f;
            }
            // Water / lava cancels fall damage on entry.
            if (this.inWater || this.inLava) this.fallDistance = 0.0f;
        }

        if (xaOrg != xa) this.xd = 0.0f;
        if (yaOrg != ya) this.yd = 0.0f;
        if (zaOrg != za) this.zd = 0.0f;
        this.x = (this.bb.x0 + this.bb.x1) / 2.0f;
        this.y = this.bb.y0 + 1.62f;
        this.z = (this.bb.z0 + this.bb.z1) / 2.0f;
    }

    /** Alpha rule: damage = ceil(fallDistance - 3). */
    private void causeFallDamage(float dist) {
        int dmg = (int) Math.ceil((double) (dist - 3.0f));
        if (dmg > 0) hurt(dmg);
    }

    public void moveRelative(float xa, float za, float speed) {
        float dist = xa * xa + za * za;
        if (dist < 0.01f) return;
        dist = speed / (float) Math.sqrt(dist);
        xa *= dist;
        za *= dist;
        float sin = (float) Math.sin((double) this.yRot * Math.PI / 180.0);
        float cos = (float) Math.cos((double) this.yRot * Math.PI / 180.0);
        this.xd += xa * cos - za * sin;
        this.zd += za * cos + xa * sin;
    }
}
