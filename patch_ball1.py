import io, os

p = 'src/com/mojang/rubydung/GameCanvas.java'
s = io.open(p, encoding='utf-8').read()

def rep(old, new):
    global s
    assert old in s, 'MISSING: ' + old[:70]
    s = s.replace(old, new, 1)

# 1. durability field
rep('''    /** Haizhuball, the HaiZhu's drop (a glossy cyan cube). */
    private static final int ITEM_HAIZHUBALL = 204;''',
'''    /** Haizhuball, the HaiZhu's drop (a glossy cyan cube). */
    private static final int ITEM_HAIZHUBALL = 204;
    /** Durability of the held Haizhuball (100 uses); only one exists. */
    private static final int HAIZHUBALL_MAX_DUR = 100;
    private int haizhuballDurability = 0;''')

# 2. death grants the item DIRECTLY into the inventory (no ground drop)
rep('''                if (m.removed) {
                    if (m.health <= 0) {
                        // Shrank to nothing: one glossy cyan ball remains.
                        spawnBreakParticles((int) m.x, (int) (m.y + 0.5f), (int) m.z, 3);
                        haizhuballs.addElement(new Skeleton.Bone(level,
                                m.x, m.y + 0.4f, m.z,
                                (rng.nextFloat() - 0.5f) * 0.08f,
                                0.12f,
                                (rng.nextFloat() - 0.5f) * 0.08f));
                    }
                    haZhus.removeElementAt(i);
                }''',
'''                if (m.removed) {
                    if (m.health <= 0) {
                        // Shrank to nothing: the Haizhuball appears directly
                        // in the inventory (never a ground drop, never
                        // replaces an item), fresh at full durability.
                        spawnBreakParticles((int) m.x, (int) (m.y + 0.5f), (int) m.z, 3);
                        if (blockCounts[ITEM_HAIZHUBALL] < 1) {
                            addBlockCount(ITEM_HAIZHUBALL, 1);
                        }
                        haizhuballDurability = HAIZHUBALL_MAX_DUR;
                        toast = "Haizhuball! " + HAIZHUBALL_MAX_DUR + " uses";
                        toastUntil = System.currentTimeMillis() + 1200;
                    }
                    haiZhus.removeElementAt(i);
                }''')

# 3. remove the ground-item pipeline for haizhuballs
rep('''    /** Haizhuballs dropped by defeated HaiZhus: same pickup pipeline. */
    private void tickHaizhuballs() {
        tickItemEntities(haizhuballs, ITEM_HAIZHUBALL, "Haizhuball");
    }

''', '')
rep('''            tickHaiZhus();
            tickHaizhuballs();
''', '''            tickHaiZhus();
''')
rep('''            renderer.renderHaizhuballs(haizhuballs, timer.a);
''', '')
rep('''    private final java.util.Vector haizhuballs = new java.util.Vector();
''', '')
rep('''        haiZhus.removeAllElements();
        haizhuballs.removeAllElements();''', '''        haiZhus.removeAllElements();''')
s = s.replace('''        haiZhus.removeAllElements();
        haizhuballs.removeAllElements();''', '''        haiZhus.removeAllElements();''')
s = s.replace('''                haiZhus.removeAllElements();
                haizhuballs.removeAllElements();''', '''                haiZhus.removeAllElements();''')
s = s.replace('''            haiZhus.removeAllElements();
            haizhuballs.removeAllElements();''', '''            haiZhus.removeAllElements();''')

# remove texture loading + setter call sites
rep('''            byte[] heartBytes = readResource("/haizhuball.bmp");
            haizhuballTexture = new Texture(haizhuballTexture == null ? new byte[0] : heartBytes);
            renderer.setHaizhuballTexture(haizhuballTexture);
''', '')  # adjusted below if pattern differs
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('part 1 ok')
