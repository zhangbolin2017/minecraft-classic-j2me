import io

# ---- WorldRenderer: HaiZhu render ----
p = 'src/com/mojang/rubydung/level/WorldRenderer.java'
s = io.open(p, encoding='utf-8').read()

s = s.replace('import com.mojang.rubydung.Firecracker;',
              'import com.mojang.rubydung.Firecracker;\nimport com.mojang.rubydung.HaiZhu;', 1)

anchor = '''    /**
     * Slimeball drops (the slime's loot): billboards on /slimeball.bmp,'''
addition = '''    // ---- HaiZhu: the dirt-cross-summoned pig head on a hovering carpet. ----
    private static final int HZ_CARPET  = 0x8C3B2E;  // dark red carpet
    private static final int HZ_CARPET2 = 0xC2543E;  // carpet top weave
    private static final int HZ_SKIN    = 0xF0A8A0;  // pig-pink head
    private static final int HZ_SNOUT   = 0xE29388;  // snout
    private static final int HZ_EYE     = 0x1A1A1A;  // eyes / nostrils
    private static final int HZ_GREEN   = 0x3FAE3F;  // the cube in its mouth

    public void renderHaiZhus(java.util.Vector mobs, float alpha) {
        if (mobs == null || mobs.size() == 0) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < mobs.size(); ++i) {
            HaiZhu m = (HaiZhu) mobs.elementAt(i);
            if (m == null || m.removed) continue;
            float ddx = m.x - camPX, ddy = m.y - camPY, ddz = m.z - camPZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 24f * 24f) continue;
            emitHaiZhu(m, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void emitHaiZhu(HaiZhu m, float alpha) {
        float px = m.xo + (m.x - m.xo) * alpha;
        float py = m.yo + (m.y - m.yo) * alpha;
        float pz = m.zo + (m.z - m.zo) * alpha;

        float fx = m.headingX, fz = m.headingZ;
        float sin = -fx;
        float cos =  fz;

        // Hover bob above the ground; the dying one sinks instead.
        float bob = (float) Math.sin(m.bob) * 0.06f;
        float hover = m.dying ? -m.deathTime * 0.02f : 0.10f + bob;
        float feet = py + (hover > -0.05f ? hover : -0.05f);

        // ---------- Carpet: ONE flat cube (its "feet") ----------
        box(px, pz, feet, feet + 0.10f, 0.34f, 0.34f, 0f, 0f, sin, cos, HZ_CARPET);
        // Weave pattern: a smaller flat box on the carpet top.
        box(px, pz, feet + 0.10f, feet + 0.13f, 0.21f, 0.21f, 0f, 0f, sin, cos, HZ_CARPET2);

        // ---------- Head: ONE big pig cube hovering over the carpet ----------
        float h0 = feet + 0.42f;
        float h1 = feet + 1.02f;
        pitchedCube6(px, pz, feet, 0f, h0, h1, 0.30f, 0.28f, 0f, 0f, sin, cos,
              HZ_SKIN, darken(HZ_SKIN, 0.60f),
              darken(HZ_SKIN, 0.95f), darken(HZ_SKIN, 0.80f),
              darken(HZ_SKIN, 0.80f), darken(HZ_SKIN, 0.80f));
        // Snout: proud flat box on the +Z face.
        box(px, pz, h0 + 0.22f, h0 + 0.42f, 0.13f, 0.045f, 0f, 0.315f, sin, cos, HZ_SNOUT);
        // Nostrils: two tiny dark cubes on the snout.
        box(px, pz, h0 + 0.26f, h0 + 0.36f, 0.022f, 0.012f, -0.06f, 0.362f, sin, cos, HZ_EYE);
        box(px, pz, h0 + 0.26f, h0 + 0.36f, 0.022f, 0.012f,  0.06f, 0.362f, sin, cos, HZ_EYE);
        // Eyes: two dark cubes above the snout.
        box(px, pz, h0 + 0.44f, h0 + 0.54f, 0.04f, 0.014f, -0.13f, 0.292f, sin, cos, HZ_EYE);
        box(px, pz, h0 + 0.44f, h0 + 0.54f, 0.04f, 0.014f,  0.13f, 0.292f, sin, cos, HZ_EYE);
        // The GREEN cube held in its mouth, below the snout.
        box(px, pz, h0 + 0.02f, h0 + 0.18f, 0.07f, 0.07f, 0f, 0.36f, sin, cos, HZ_GREEN);
    }

    /**
     * Slimeball drops (the slime's loot): billboards on /slimeball.bmp,'''
assert anchor in s
s = s.replace(anchor, addition, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('WorldRenderer ok')

# ---- GameCanvas: summon + tick + attack + render + resets ----
p2 = 'src/com/mojang/rubydung/GameCanvas.java'
s2 = io.open(p2, encoding='utf-8').read()

def rep(old, new):
    global s2
    assert old in s2, 'MISSING: ' + old[:60]
    s2 = s2 = s2.replace(old, new, 1)

rep('''    private final java.util.Vector slimeBalls = new java.util.Vector();
    private final java.util.Vector gunpowders = new java.util.Vector();''',
'''    private final java.util.Vector slimeBalls = new java.util.Vector();
    private final java.util.Vector gunpowders = new java.util.Vector();
    private final java.util.Vector haiZhus = new java.util.Vector();''')

# tick + summon + attack methods, placed before tickBones
rep('''    /** Bones dropped by skeletons: gravity + rest + magnet pickup. */
    private void tickBones() {''',
'''    // ---------------------------------------------------------------------
    //  HaiZhu: the dirt-cross-summoned pig head.  Never spawns naturally.
    // ---------------------------------------------------------------------
    private static final int DIRT_ID = 3;

    private void tickHaiZhus() {
        for (int i = haiZhus.size() - 1; i >= 0; --i) {
            try {
                HaiZhu m = (HaiZhu) haiZhus.elementAt(i);
                m.tick(player);
                if (m.removed) {
                    if (m.health <= 0) {
                        // It came from dirt; it puffs back to dirt specks.
                        spawnBreakParticles((int) m.x, (int) (m.y + 0.5f), (int) m.z, 3);
                    }
                    haiZhus.removeElementAt(i);
                }
            } catch (Throwable t) {
                haiZhus.removeElementAt(i);
                handleRuntimeError("haizhu", t);
            }
        }
    }

    /**
     * After placing a dirt block: if it completes a DIRT CROSS (crucifix,
     * 6 dirt blocks, vertical bar 4 tall + 2 arms, standing in the X or Z
     * plane), the whole cross is consumed and a HaiZhu appears in its place.
     */
    private void trySummonHaiZhu(int px, int py, int pz) {
        for (int ori = 0; ori < 2; ++ori) {          // 0: arms along X, 1: along Z
            for (int c = 0; c < 6; ++c) {            // which cross cell was placed
                int cx = px, cy = py, cz = pz;
                switch (c) {
                    case 0: break;                                    // centre
                    case 1: cy = py - 2; break;                       // top
                    case 2: cy = py + 1; break;                       // lower bar
                    case 3: cy = py + 2; break;                       // base
                    case 4: if (ori == 0) cx = px + 1; else cz = pz + 1; break;
                    case 5: if (ori == 0) cx = px - 1; else cz = pz - 1; break;
                }
                if (isDirtCross(cx, cy, cz, ori)) {
                    summonHaiZhuAt(cx, cy, cz, ori);
                    return;
                }
            }
        }
    }

    private boolean isDirtCross(int cx, int cy, int cz, int ori) {
        if (!isDirtAt(cx, cy + 2, cz)) return false;
        if (!isDirtAt(cx, cy, cz)) return false;
        if (!isDirtAt(cx, cy - 1, cz)) return false;
        if (!isDirtAt(cx, cy - 2, cz)) return false;
        if (ori == 0) {
            return isDirtAt(cx - 1, cy, cz) && isDirtAt(cx + 1, cy, cz);
        }
        return isDirtAt(cx, cy, cz - 1) && isDirtAt(cx, cy, cz + 1);
    }

    private boolean isDirtAt(int x, int y, int z) {
        return level.getTile(x, y, z) == DIRT_ID;
    }

    private void summonHaiZhuAt(int cx, int cy, int cz, int ori) {
        consumeDirt(cx, cy + 2, cz);
        consumeDirt(cx, cy, cz);
        consumeDirt(cx, cy - 1, cz);
        consumeDirt(cx, cy - 2, cz);
        if (ori == 0) {
            consumeDirt(cx - 1, cy, cz);
            consumeDirt(cx + 1, cy, cz);
        } else {
            consumeDirt(cx, cy, cz - 1);
            consumeDirt(cx, cy, cz + 1);
        }
        haiZhus.addElement(new HaiZhu(level, cx + 0.5f, cy - 1.0f, cz + 0.5f));
        spawnBreakParticles(cx, cy, cz, 3);
        toast = "A HaiZhu answers your call!";
        toastUntil = System.currentTimeMillis() + 1200;
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    private void consumeDirt(int x, int y, int z) {
        if (level.getTile(x, y, z) == DIRT_ID) {
            level.setTile(x, y, z, 0);
            if (net != null) net.sendSetBlock(x, y, z, 0, false);
        }
    }

    /** Punch hit-test.  Exactly ONE damage per punch: 10 HP = 10 punches. */
    private boolean attackHaiZhu() {
        if (haiZhus.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        HaiZhu best = null;
        float bestT = 4.5f;
        for (int i = 0; i < haiZhus.size(); ++i) {
            HaiZhu m = (HaiZhu) haiZhus.elementAt(i);
            if (m == null || m.removed || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.15f, m.z, 0.50f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.75f, m.z, 0.45f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = m; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(1, player);   // ONE damage per punch (user spec)
        toast = best.dying ? "HaiZhu destroyed!" : "HaiZhu hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    /** Bones dropped by skeletons: gravity + rest + magnet pickup. */
    private void tickBones() {''')

# tick call
rep('''            tickBones();
            tickSlimeBalls();''',
'''            tickHaiZhus();
            tickBones();
            tickSlimeBalls();''')

# attack dispatch
rep('''        if (attackRedMouse()) return;
        if (attackSkeleton()) return;
        if (attackFirecracker()) return;''',
'''        if (attackRedMouse()) return;
        if (attackSkeleton()) return;
        if (attackFirecracker()) return;
        if (attackHaiZhu()) return;''')

# render call
rep('''            renderer.renderGunpowder(gunpowders, timer.a);''',
'''            renderer.renderGunpowder(gunpowders, timer.a);
            renderer.renderHaiZhus(haiZhus, timer.a);''')

# summon trigger after placing dirt
rep('''            level.setTile(x, y, z, block);
            if (net != null) net.sendSetBlock(x, y, z, block, true);
            toast = "Placed: " + nameOf(block) + " x" + blockCounts[block];''',
'''            level.setTile(x, y, z, block);
            if (net != null) net.sendSetBlock(x, y, z, block, true);
            if (block == DIRT_ID) trySummonHaiZhu(x, y, z);
            toast = "Placed: " + nameOf(block) + " x" + blockCounts[block];''')

# reset sites
rep('''        gunpowders.removeAllElements();''',
'''        gunpowders.removeAllElements();
        haiZhus.removeAllElements();''')
s2 = s2.replace('''                gunpowders.removeAllElements();''',
                '''                gunpowders.removeAllElements();
                haiZhus.removeAllElements();''')
s2 = s2.replace('''            gunpowders.removeAllElements();''',
                '''            gunpowders.removeAllElements();
            haiZhus.removeAllElements();''')

io.open(p2, 'w', encoding='utf-8', newline='').write(s2)
print('GameCanvas ok')
