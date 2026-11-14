package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;
import com.mojang.rubydung.level.InfiniteLevel;
import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.WorldRenderer;
import com.mojang.rubydung.level.tile.Tile;

import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;

/**
 * Game loop + input for the J2ME / Mascot Capsule port.
 *
 * Controls:
 *   dpad (or 2/8/4/6/5 on handsets, which ARE the dpad there)  move / turn / jump
 *   1 - 9  select hotbar slot 1-9; the item name flashes for 1s (no count)
 *   *      break block      #  place block / use bone      0  next hotbar slot
 *   E      inventory        Space  teleport to a void platform
 *   S      spawn a slime    U / D  look up / down
 *   left soft key opens the in-game menu; right soft key opens the inventory
 */
public class GameCanvas extends Canvas implements Runnable, CommandListener {
    private static final int PLAYER_SAVE_SCALE = 1000;
    private static final int INV_COLS = 9;
    private static final int MENU_RESUME = 0;
    private static final int MENU_SAVE = 1;
    private static final int MENU_LOAD = 2;
    private static final int MENU_NEW = 3;
    private static final int MENU_HELP = 4;
    private static final int MENU_MULTIPLAYER = 5;
    private static final int MENU_COUNT = 6;

    private final Timer timer = new Timer(60.0f);
    private ILevel level;
    private WorldRenderer renderer;
    private Player player;
    private Graphics3D g3d;
    private Texture texture;
    private Texture slimeTexture;
    private Texture boneTexture;
    private Texture slimeballTexture;
    private Texture gunpowderTexture;
    private Texture featherTexture;
    private Texture toothTexture;
    private Texture haizhuballTexture;
    private HitResult hitResult;

    private volatile boolean running = false;

    private int fps;
    private boolean showInventory = false;
    private boolean showMenu = false;
    private int menuIndex = 0;
    private String toast;
    private long toastUntil;
    private String hudLine;
    private long hudUntil;
    private String lastRuntimeError;
    private long lastRuntimeErrorAt;
    private int runtimeErrorCount;

    // ---- Multiplayer ----
    private com.mojang.rubydung.net.NetworkClient net;
    private boolean multiplayer = false;
    // server entry screen
    private boolean showServerEntry = false;
    private String serverHost = "";
    private int serverPort = 25565;
    private String userName = "Steve";
    private int entryField = 0;   // 0=host, 1=port, 2=name
    private MIDlet midlet;
    private TextBox joinBox;
    private Command joinOk, joinCancel;
    private int joinStep = 0;   // 0=host,1=port,2=name
    private boolean connecting = false;

    public GameCanvas(MIDlet midlet) {
        this.midlet = midlet;
    }


    // Input state, polled by Player.tick().
    public boolean kForward, kBack, kLeft, kRight, kJump, kReset;
    // Continuous look: held keys turn smoothly each tick (Symbian-port feel).
    private boolean kTurnLeft, kTurnRight, kLookUp, kLookDown;
    private float turnYaw, turnPitch;
    private static final float TURN_SPEED = 22.0f;   // raw units/tick (scaled *0.15 in Player.turn)
    private static final float PITCH_SPEED = 18.0f;
    private static final int POINTER_TAP_SLOP = 6;
    private static final int POINTER_TOP_ZONE = 18;
    private static final int POINTER_CORNER_W = 28;

    private boolean pointerDown = false;
    private boolean pointerDragged = false;
    private int pointerStartX, pointerStartY;
    private int pointerLastX, pointerLastY;

    // ---- Inventory: every placeable (non-gas) block id ----
    private int[] palette;
    private final int[] blockCounts = new int[256];
    private static final int HOTBAR_SLOTS = 9;
    /** Pseudo item id for the skeleton's bone drop (above every tile id). */
    private static final int ITEM_BONE = 200;
    /** Bone meal, crafted from bones via the Yes/No dialog. */
    private static final int ITEM_BONE_MEAL = 201;
    /** Slimeball, the slime's drop (replaces the old green-block drop). */
    private static final int ITEM_SLIMEBALL = 202;
    /** Gunpowder, the firecracker's drop when defused by punching. */
    private static final int ITEM_GUNPOWDER = 203;
    /** YouJi's Feather, the rooster king's drop when felled. */
    private static final int ITEM_YOUIJI_FEATHER = 205;
    /** The Flying Wing, crafted from 5 YouJi feathers. */
    private static final int ITEM_FLYING_WING = 206;
    /** ZiShu's wisdom teeth, the rat emperor's drop when felled. */
    private static final int ITEM_ZISHU_TOOTH = 207;
    /** ZiShu's Pickaxe, crafted from 3 wisdom teeth: digs 3x3. */
    private static final int ITEM_ZISHU_PICKAXE = 208;
    /** Haizhuball, the HaiZhu's drop (a glossy cyan cube). */
    private static final int ITEM_HAIZHUBALL = 204;
    /** Durability of the held Haizhuball (100 uses); only one exists. */
    private static final int HAIZHUBALL_MAX_DUR = 100;
    private static final int WING_MAX_DUR = 100;   // 1 per MINUTE of flight
    private static final int PICK_MAX_DUR = 100;    // 1 per 3x3 dig
    private int haizhuballDurability = 0;
    private int wingDurability = 0;
    private int wingFlightTicks = 0;
    private int pickDurability = 0;
    /** MC-style hotbar: each slot holds a tile id, ITEM_BONE, or -1 (empty). */
    private final int[] hotbar = {1, 3, 5, 45, 17, 65, 20, 12, ITEM_BONE};
    private int hotbarIndex = 0;   // selected hotbar slot, 0..HOTBAR_SLOTS-1
    private int invCursor = 0;     // cursor while inventory overlay is open
    /** 2D icon sources for the HUD (null -> coloured-square fallback). */
    private Image iconAtlas;       // /terrain.png, 16px tiles
    private Image boneIcon;        // /bone.png
    private Image boneMealIcon;    // /bone_meal.png
    private Image slimeballIcon;   // /slimeball.png
    private Image gunpowderIcon;   // /gunpowder.png
    private Image featherIcon;     // /youji_feather.png
    private Image wingIcon;        // /flying_wing.png
    private Image toothIcon;       // /zishu_tooth.png
    private Image pickIcon;        // /zishu_pickaxe.png
    private Image haizhuballIcon; // /haizhuball.png

    // ---- Yes/No dialog: craft 3 bone meal out of 1 selected bone.
    // ---- Hold Yes for 5 s to convert the WHOLE bone stack (still 1:3). ----
    private boolean showBoneDialog = false;
    private boolean boneDialogYes = true;
    /** 0 = bone->meal, 1 = 5 feathers->Wing, 2 = 3 teeth->Pickaxe. */
    private int boneDialogMode = 0;
    private static final long HOLD_ALL_MS = 5000L;
    private boolean boneYesHeld = false;
    private long boneYesHoldStart = 0L;
    private int boneDlgX, boneDlgY, boneDlgW, boneDlgH;
    private int boneNoX, boneYesX, boneBtnY, boneBtnW, boneBtnH;
    private int voidPlatformSequence = 0;

    private void buildPalette() {
        int n = 0;
        int[] tmp = new int[256];
        for (int id = 1; id < 256; ++id) {
            Tile t = Tile.tiles[id];
            if (t != null && t.isRenderable()) tmp[n++] = id;
        }
        palette = new int[n + 9];
        System.arraycopy(tmp, 0, palette, 0, n);
        palette[n] = ITEM_BONE;       // the skeleton's loot, a non-block item
        palette[n + 1] = ITEM_BONE_MEAL;
        palette[n + 2] = ITEM_SLIMEBALL;   // the slime's loot
        palette[n + 3] = ITEM_GUNPOWDER;   // the firecracker's loot
        palette[n + 4] = ITEM_HAIZHUBALL;   // the HaiZhu's loot
        palette[n + 5] = ITEM_YOUIJI_FEATHER;   // the YouJi's loot
        palette[n + 6] = ITEM_FLYING_WING;        // crafted from feathers
        palette[n + 7] = ITEM_ZISHU_TOOTH;          // the ZiShu's loot
        palette[n + 8] = ITEM_ZISHU_PICKAXE;        // crafted from teeth
    }

    private String itemNameAt(int index) {
        int id = palette[index];
        if (id == ITEM_BONE) return "bone";
        if (id == ITEM_BONE_MEAL) return "bone meal";
        if (id == ITEM_SLIMEBALL) return "slimeball";
        if (id == ITEM_GUNPOWDER) return "gunpowder";
        if (id == ITEM_HAIZHUBALL) return "Haizhuball";
        if (id == ITEM_YOUIJI_FEATHER) return "YouJi Feather";
        if (id == ITEM_FLYING_WING) return "Flying Wing";
        if (id == ITEM_ZISHU_TOOTH) return "Zishu Tooth";
        if (id == ITEM_ZISHU_PICKAXE) return "Zishu Pickaxe";
        Tile t = Tile.tiles[id];
        return t != null ? t.name : "?";
    }

    private int itemColorAt(int index) {
        int id = palette[index];
        if (id == ITEM_BONE) return 0xDDDDDD;
        if (id == ITEM_BONE_MEAL) return 0xE6E6E6;
        if (id == ITEM_SLIMEBALL) return 0x77B558;
        if (id == ITEM_GUNPOWDER) return 0x9A958A;
        if (id == ITEM_HAIZHUBALL) return 0x40C6B8;
        if (id == ITEM_YOUIJI_FEATHER) return 0xE8A0B0;
        if (id == ITEM_FLYING_WING) return 0xE8A0B0;
        if (id == ITEM_ZISHU_TOOTH) return 0xF7D13A;
        if (id == ITEM_ZISHU_PICKAXE) return 0x8B2E55;
        return blockColor(id);
    }

    private int itemCountAt(int index) {
        int blockId = palette[index];
        return (blockId >= 0 && blockId < blockCounts.length) ? blockCounts[blockId] : 0;
    }

    /** A fresh world gifts ONE random ore (coal/iron/gold) so the YouJi
     *  summon ritual is reachable without mining first. */
    private void giveStarterOre() {
        int[] ores = {14, 15, 16};
        addBlockCount(ores[rng.nextInt(3)], 1);
    }

    private void clearBlockCounts() {
        for (int i = 0; i < blockCounts.length; ++i) blockCounts[i] = 0;
    }

    private int addBlockCount(int blockId, int amount) {
        if (blockId < 0 || blockId >= blockCounts.length || amount == 0) return 0;
        int next = blockCounts[blockId] + amount;
        if (next < 0) next = 0;
        blockCounts[blockId] = next;
        return next;
    }

    private boolean hasPlaceableCount(int blockId) {
        return blockId >= 0 && blockId < blockCounts.length && blockCounts[blockId] > 0;
    }

    private int dimColor(int rgb) {
        int r = ((rgb >> 16) & 0xFF) >> 1;
        int g = ((rgb >> 8) & 0xFF) >> 1;
        int b = (rgb & 0xFF) >> 1;
        return (r << 16) | (g << 8) | b;
    }

    public void start() {
        try {
            buildPalette();
            g3d = new Graphics3D();
            byte[] texBytes = readResource("/terrain.bmp");
            texture = new Texture(texBytes, true);

            level = new InfiniteLevel(64, (int) System.currentTimeMillis());
            player = new Player(level);
            renderer = new WorldRenderer(level, g3d, texture);
            // Neutral all-white texture: freej2me multiplies even untextured
            // (TEXURE_COORD_NONE) faces by the last bound texture and treats
            // a null texture as "keep the previous one", so the only way to
            // get exact flat colours is to bind pure white.
            byte[] whiteBytes = readResource("/white.bmp");
            renderer.setWhiteTexture(new Texture(whiteBytes, true));
            byte[] slimeBytes = readResource("/slime.bmp");
            slimeTexture = new Texture(slimeBytes, true);
            renderer.setSlimeTexture(slimeTexture);
            byte[] boneBytes = readResource("/bone.bmp");
            boneTexture = new Texture(boneBytes, true);
            renderer.setBoneTexture(boneTexture);
            byte[] ballBytes = readResource("/slimeball.bmp");
            slimeballTexture = new Texture(ballBytes, true);
            renderer.setSlimeballTexture(slimeballTexture);
            byte[] hzBytes = readResource("/haizhuball.bmp");
            haizhuballTexture = new Texture(hzBytes, true);
            renderer.setHaizhuballTexture(haizhuballTexture);
            renderer.setGunpowderTexture(gunpowderTexture);
            renderer.setFeatherTexture(featherTexture);
            renderer.setToothTexture(toothTexture);
            renderer.setHaizhuballTexture(haizhuballTexture);
            byte[] powderBytes = readResource("/gunpowder.bmp");
            gunpowderTexture = new Texture(powderBytes, true);
            renderer.setGunpowderTexture(gunpowderTexture);
            renderer.setFeatherTexture(featherTexture);
            byte[] featherBytes = readResource("/youji_feather.bmp");
            featherTexture = new Texture(featherBytes, true);
            renderer.setFeatherTexture(featherTexture);
            byte[] toothBytes = readResource("/zishu_tooth.bmp");
            toothTexture = new Texture(toothBytes, true);
            renderer.setToothTexture(toothTexture);
            renderer.setHaizhuballTexture(haizhuballTexture);
            try { iconAtlas = Image.createImage("/terrain.png"); } catch (Throwable t) {}
            try { boneIcon = Image.createImage("/bone.png"); } catch (Throwable t) {}
            try { boneMealIcon = Image.createImage("/bone_meal.png"); } catch (Throwable t) {}
            try { slimeballIcon = Image.createImage("/slimeball.png"); } catch (Throwable t) {}
            try { gunpowderIcon = Image.createImage("/gunpowder.png"); } catch (Throwable t) {}
            try { featherIcon = Image.createImage("/youji_feather.png"); } catch (Throwable t) {}
            try { wingIcon = Image.createImage("/flying_wing.png"); } catch (Throwable t) {}
            try { toothIcon = Image.createImage("/zishu_tooth.png"); } catch (Throwable t) {}
            try { pickIcon = Image.createImage("/zishu_pickaxe.png"); } catch (Throwable t) {}
            try { haizhuballIcon = Image.createImage("/haizhuball.png"); } catch (Throwable t) {}
            clearBlockCounts();
            giveStarterOre();   // fresh boot gift; a save load overwrites it
            loadPlayerState();
            // No auto slimes any more -- press 9 to spawn them.
        } catch (Throwable t) {
            t.printStackTrace();
        }
        running = true;
        new Thread(this).start();
    }

    public void stop() {
        running = false;
        saveGame();
    }

    public void run() {
        long lastTime = System.currentTimeMillis();
        int frames = 0;
        while (running) {
            try {
                timer.advanceTime();
                for (int i = 0; i < timer.ticks; ++i) {
                    tick();
                }
                repaint();
                serviceRepaints();
                ++frames;
                if (System.currentTimeMillis() >= lastTime + 1000L) {
                    lastTime += 1000L;
                    fps = frames;
                    frames = 0;
                }
            } catch (Throwable t) {
                handleRuntimeError("main loop", t);
            }
            try { Thread.sleep(15); } catch (InterruptedException e) {}
        }
    }

    private void tick() {
        tickNetwork();
        if (showMenu || showInventory || connecting) return;
        // Bone-meal dialog: holding Yes for 5 s converts the WHOLE stack.
        if (showBoneDialog && boneDialogMode == 0 && boneYesHeld
                && System.currentTimeMillis() - boneYesHoldStart >= HOLD_ALL_MS) {
            craftAllBoneMeal();
        }
        if (kTurnLeft)  turnYaw -= TURN_SPEED;
        if (kTurnRight) turnYaw += TURN_SPEED;
        if (kLookUp)    turnPitch -= PITCH_SPEED;
        if (kLookDown)  turnPitch += PITCH_SPEED;
        if (turnYaw != 0 || turnPitch != 0) {
            player.turn(turnYaw, turnPitch);
            turnYaw = 0;
            turnPitch = 0;
        }
        player.kForward = kForward;
        player.kBack = kBack;
        player.kLeft = kLeft;
        player.kRight = kRight;
        player.kJump = kJump;
        player.kReset = kReset;
        // Flying Wing: selected + charged + jump held = steady climb;
        // one durability per MINUTE of flight, breaks at zero.
        player.wingFly = !player.isDead()
                && hotbar[hotbarIndex] == ITEM_FLYING_WING
                && blockCounts[ITEM_FLYING_WING] > 0
                && wingDurability > 0 && kJump;
        if (player.wingFly) {
            if (++wingFlightTicks >= 20 * 60) {
                wingFlightTicks = 0;
                --wingDurability;
                if (wingDurability <= 0) {
                    wingDurability = 0;
                    addBlockCount(ITEM_FLYING_WING, -1);
                    player.wingFly = false;
                    toast = "The Flying Wing wore out!";
                    toastUntil = System.currentTimeMillis() + 1000;
                }
            }
        } else {
            wingFlightTicks = 0;
        }
        if (level instanceof InfiniteLevel) {
            ((InfiniteLevel) level).setFocus((int) player.x, (int) player.z);
        }
        try {
            level.tick();
            player.tick();
            tickParticles();
            tickDrops();
            tickSlimes();
            tickRedMice();
            tickSkeletons();
            tickFirecrackers();
            tickHaiZhus();
            tickHaizhuballs();
            tickYouJis();
            tickZiShus();
            tickForwards();
            tickBones();
            tickSlimeBalls();
            tickGunpowder();
            tickFeathers();
            tickTeeth();
            tickArrows();
            tickBowDrops();
            hitResult = RayCast.pick(level, player, 4.0f);
        } catch (Throwable t) {
            handleRuntimeError("tick", t);
        }
    }

    private final java.util.Random rng = new java.util.Random();
    private final java.util.Vector particles = new java.util.Vector();
    private final java.util.Vector drops = new java.util.Vector();
    private final java.util.Vector slimes = new java.util.Vector();
    private final java.util.Vector redMice = new java.util.Vector();
    private final java.util.Vector arrows  = new java.util.Vector();
    private final java.util.Vector bows    = new java.util.Vector();
    private final java.util.Vector skeletons = new java.util.Vector();
    private final java.util.Vector firecrackers = new java.util.Vector();
    private final java.util.Vector bones = new java.util.Vector();
    private final java.util.Vector slimeBalls = new java.util.Vector();
    private final java.util.Vector gunpowders = new java.util.Vector();
    private final java.util.Vector feathers = new java.util.Vector();
    private final java.util.Vector teeth = new java.util.Vector();
    private final java.util.Vector haiZhus = new java.util.Vector();
    private final java.util.Vector youJis = new java.util.Vector();
    private final java.util.Vector ziShus = new java.util.Vector();
    private final java.util.Vector forwards = new java.util.Vector();
    private final java.util.Vector haizhuballs = new java.util.Vector();

    private void tickParticles() {
        for (int i = particles.size() - 1; i >= 0; --i) {
            try {
                Particle pa = (Particle) particles.elementAt(i);
                pa.tick();
                if (pa.removed) particles.removeElementAt(i);
            } catch (Throwable t) {
                particles.removeElementAt(i);
                handleRuntimeError("particle", t);
            }
        }
    }

    private void tickDrops() {
        for (int i = drops.size() - 1; i >= 0; --i) {
            try {
                DroppedBlock drop = (DroppedBlock) drops.elementAt(i);
                boolean pickedUp = drop.tick(player);
                if (pickedUp) collectDrop(drop);
                if (drop.removed) drops.removeElementAt(i);
            } catch (Throwable t) {
                drops.removeElementAt(i);
                handleRuntimeError("drop", t);
            }
        }
    }


    private void tickSlimes() {
        // Slimes are NIGHT-spawners now (used to be the key-9/menu spawner):
        // every ~20-40s of darkness 1-2 hop out on dry land near the player.
        boolean dead = player.isDead();
        if (!dead && isNight() && --slimeSpawnTimer <= 0) {
            slimeSpawnTimer = 20 * (20 + rng.nextInt(21));
            if (slimes.size() < SLIME_MAX) {
                int spawned = spawnMobWild(slimes, 1 + rng.nextInt(2), 0);
                if (spawned > 0) {
                    toast = "Slimes hop out of the dark...";
                    toastUntil = System.currentTimeMillis() + 900;
                }
            }
        }

        for (int i = slimes.size() - 1; i >= 0; --i) {
            try {
                Slime s = (Slime) slimes.elementAt(i);
                s.tick(player);
                if (s.removed) {
                    // Kill loot: 1-2 slimeballs pop out (used to be a
                    // plain green block drop).
                    if (s.health <= 0) {
                        int n = 1 + rng.nextInt(2);
                        for (int k = 0; k < n; ++k) {
                            slimeBalls.addElement(new Skeleton.Bone(level,
                                    s.x + (rng.nextFloat() - 0.5f) * 0.4f,
                                    s.y + 0.3f + rng.nextFloat() * 0.3f,
                                    s.z + (rng.nextFloat() - 0.5f) * 0.4f,
                                    (rng.nextFloat() - 0.5f) * 0.10f,
                                    0.12f + rng.nextFloat() * 0.06f,
                                    (rng.nextFloat() - 0.5f) * 0.10f));
                        }
                    }
                    slimes.removeElementAt(i);
                }
            } catch (Throwable t) {
                slimes.removeElementAt(i);
                handleRuntimeError("slime", t);
            }
        }
    }

    /** Night natural-spawn timer + cap for slimes. */
    private int slimeSpawnTimer = 20 * 15;
    private static final int SLIME_MAX = 3;

    /**
     * Shared wild spawner: places {@code count} mobs on dry land in a ring
     * 6..12 blocks around the player.  kind: 0 = Slime, 1 = Skeleton,
     * 2 = Firecracker (red mice keep their own verbatim copy).
     * @return how many mobs were placed.
     */
    private int spawnMobWild(java.util.Vector mobs, int count, int kind) {
        if (level == null || player == null) return 0;
        int placed = 0;
        for (int i = 0; i < count; ++i) {
            for (int attempt = 0; attempt < 30; ++attempt) {
                double ang = rng.nextDouble() * 2.0 * Math.PI;
                int dist = 6 + rng.nextInt(7);
                int bx = (int) Math.floor(player.x + Math.cos(ang) * dist);
                int bz = (int) Math.floor(player.z + Math.sin(ang) * dist);
                int sy = findSurfaceY(bx, bz);
                if (sy < 0) continue;
                if (level.getTile(bx, sy + 1, bz) != 0 || level.getTile(bx, sy + 2, bz) != 0) continue;
                if (kind == 0) {
                    mobs.addElement(new Slime(level, bx + 0.5f, sy + 1.0f, bz + 0.5f));
                } else if (kind == 1) {
                    mobs.addElement(new Skeleton(level, bx + 0.5f, sy + 1.0f, bz + 0.5f));
                } else {
                    mobs.addElement(new Firecracker(level, bx + 0.5f, sy + 1.0f, bz + 0.5f));
                }
                ++placed;
                break;
            }
        }
        return placed;
    }

    // ---------------------------------------------------------------------
    //  RED-mouse mob: tick, hit-test against the crosshair, and spawner.
    // ---------------------------------------------------------------------
    /** Natural-spawn timer: every ~25-45s of NIGHT wild mice wander in. */
    private int redMouseSpawnTimer = 20 * 20;
    private static final int RED_MOUSE_MAX = 3;

    private void tickRedMice() {
        boolean dead = player.isDead();
        // Natural spawning, NIGHT only now: every 25-45 seconds of darkness
        // one or two mice appear on dry land in a ring AROUND the player.
        if (redMice.size() == 0 && redMouseSpawnTimer > 100) redMouseSpawnTimer = 100;
        if (!dead && isNight() && --redMouseSpawnTimer <= 0) {
            redMouseSpawnTimer = 20 * (25 + rng.nextInt(21));
            if (redMice.size() < RED_MOUSE_MAX) {
                int spawned = spawnRedMouseWild(1 + rng.nextInt(2));
                if (spawned > 0) {
                    toast = "A red mouse takes position...";
                    toastUntil = System.currentTimeMillis() + 900;
                }
            }
        }

        for (int i = redMice.size() - 1; i >= 0; --i) {
            try {
                RedMouse m = (RedMouse) redMice.elementAt(i);
                m.tick(player, arrows);
                if (m.removed) {
                    if (m.health <= 0) {
                        // Kill reward: the corpse turns into exactly two
                        // wooden planks (block id 5), side by side.
                        addDrop(new DroppedBlock(level, m.x - 0.12f, m.y + 0.3f, m.z, 5));
                        addDrop(new DroppedBlock(level, m.x + 0.12f, m.y + 0.3f, m.z, 5));
                    }
                    redMice.removeElementAt(i);
                }
            } catch (Throwable t) {
                redMice.removeElementAt(i);
                handleRuntimeError("red_mouse", t);
            }
        }
    }

    /**
     * Wild natural spawn: dry land in a ring 5..11 blocks around the player.
     * The ring stays inside the 12-block crossbow range, so a stationary
     * sentry spawned here will always start shooting at the player instead
     * of standing unseen far away.  @return how many mice were placed.
     */
    private int spawnRedMouseWild(int count) {
        if (level == null || player == null) return 0;
        int placed = 0;
        for (int i = 0; i < count; ++i) {
            for (int attempt = 0; attempt < 30; ++attempt) {
                double ang = rng.nextDouble() * 2.0 * Math.PI;
                int dist = 5 + rng.nextInt(7);          // 5..11 blocks out
                int bx = (int) Math.floor(player.x + Math.cos(ang) * dist);
                int bz = (int) Math.floor(player.z + Math.sin(ang) * dist);
                int sy = findSurfaceY(bx, bz);
                if (sy < 0) continue;
                if (level.getTile(bx, sy + 1, bz) != 0 || level.getTile(bx, sy + 2, bz) != 0) continue;
                redMice.addElement(new RedMouse(level, bx + 0.5f, sy + 1.0f, bz + 0.5f));
                ++placed;
                break;
            }
        }
        return placed;
    }

    /** Update arrows shot by RedMouse.  Arrows currently do 0 damage so the
     *  mob is safe to run near while we tweak the rest of the behaviour; the
     *  hit-damage line is still there for later (just re-set Arrow damage>0). */
    private void tickArrows() {
        for (int i = arrows.size() - 1; i >= 0; --i) {
            try {
                RedMouse.Arrow a = (RedMouse.Arrow) arrows.elementAt(i);
                boolean hit = a.tick(player);
                if (hit && a.damage > 0) {
                    player.hurt(a.damage);
                    spawnBreakParticles((int) player.x, (int) (player.y + 0.5f), (int) player.z, 0);
                }
                if (a.removed) arrows.removeElementAt(i);
            } catch (Throwable t) {
                arrows.removeElementAt(i);
                handleRuntimeError("arrow", t);
            }
        }
    }

    /** Update crossbows dropped by dying mice.  They only fall and rest;
     *  they never despawn -- punch one to turn it into wooden planks. */
    private void tickBowDrops() {
        for (int i = bows.size() - 1; i >= 0; --i) {
            try {
                RedMouse.BowDrop b = (RedMouse.BowDrop) bows.elementAt(i);
                b.tick();
                if (b.removed) bows.removeElementAt(i);
            } catch (Throwable t) {
                bows.removeElementAt(i);
                handleRuntimeError("bow_drop", t);
            }
        }
    }

    /**
     * Punch a dropped crossbow -> it turns into a wooden plank drop.
     * VERY forgiving hit test, because the bow is small and lies flat on
     * the floor: the look ray hits if it passes near ANY part of the bow
     * (three spheres along its length) OR if the ray's floor-landing
     * point is right next to the bow.  Aiming at the ground beside the
     * bow is enough.
     */
    private boolean attackBowDrop() {
        if (bows.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        // player.y is already the eye height (see Player.tick) -- same ray
        // convention as RayCast.pick.
        float ex = player.x, ey = player.y, ez = player.z;

        RedMouse.BowDrop best = null;
        float bestT = 5.0f;
        for (int i = 0; i < bows.size(); ++i) {
            RedMouse.BowDrop b = (RedMouse.BowDrop) bows.elementAt(i);
            if (b == null || b.removed) continue;
            float tm = -1f;
            boolean hit = false;
            // Three spheres along the bow: grip end, middle, muzzle end.
            for (int k = 0; k <= 2 && !hit; ++k) {
                float off = -0.15f + 0.25f * k;
                tm = raySphereT(ex, ey, ez, dx, dy, dz,
                                b.x + b.headingX * off, b.y + 0.10f,
                                b.z + b.headingZ * off, 0.45f, bestT);
                hit = tm >= 0f;
            }
            // Fallback: where does the look ray land on the floor?  If it
            // lands right next to the bow, that counts as a hit too.
            if (!hit && dy < -0.05f) {
                float tg = (ey - (b.y + 0.05f)) / -dy;
                if (tg > 0f && tg < 5.5f) {
                    float gx = ex + dx * tg - b.x;
                    float gz = ez + dz * tg - b.z;
                    if (gx * gx + gz * gz < 0.65f * 0.65f) {
                        hit = true;
                        tm = tg;
                    }
                }
            }
            if (hit && tm >= 0f && tm < bestT) { best = b; bestT = tm; }
        }
        if (best == null) return false;
        bows.removeElement(best);
        addDrop(new DroppedBlock(level, best.x, best.y + 0.2f, best.z, 5));
        toast = "Crossbow -> planks!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    /**
     * Distance along the look ray where it passes closest to the sphere at
     * (sx,sy,sz) with radius r, or -1 when it misses / is behind / too far.
     * Ray origin (ex,ey,ez) and unit direction (dx,dy,dz) -- identical
     * convention to RayCast.pick (player.y is already eye height!).
     */
    private float raySphereT(float ex, float ey, float ez,
                             float dx, float dy, float dz,
                             float sx, float sy, float sz, float r, float maxT) {
        float ox = sx - ex, oy = sy - ey, oz = sz - ez;
        float t = ox * dx + oy * dy + oz * dz;
        if (t < 0.0f || t > maxT) return -1.0f;
        float cx = ox - dx * t, cy = oy - dy * t, cz = oz - dz * t;
        if (cx * cx + cy * cy + cz * cz > r * r) return -1.0f;
        return t;
    }

    /** Same ray-punch test as attackSlime(), just for the bigger RedMouse hitbox. */
    private boolean attackRedMouse() {
        if (redMice.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        // NOTE: player.y IS the eye height already (bb.y0 + 1.62, see
        // Player.tick) -- the ray origin must be exactly (x, y, z), same
        // as RayCast.pick().  Adding +1.62 here made every punch sail
        // over the mob's head.
        float ex = player.x, ey = player.y, ez = player.z;

        RedMouse best = null;
        float bestT = 5.0f;
        for (int i = 0; i < redMice.size(); ++i) {
            RedMouse m = (RedMouse) redMice.elementAt(i);
            if (m == null || m.removed || m.dying) continue;
            // Two forgiving spheres cover the whole body: lower (legs/body)
            // and upper (head/ears).  Aiming anywhere at the mouse hits.
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.40f, m.z, 0.45f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 1.00f, m.z, 0.45f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = m; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(2, player);
        if (best.dying) {
            // The crossbow slips out of the dead mouse's hands: it takes a
            // tiny toss, lands somewhere nearby and stays there until the
            // player punches it (turning it into wooden planks).
            bows.addElement(new RedMouse.BowDrop(level,
                    best.x + best.headingX * 0.30f,
                    best.y + 0.78f,
                    best.z + best.headingZ * 0.30f,
                    best.headingX, best.headingZ,
                    (rng.nextFloat() - 0.5f) * 0.14f,
                    0.14f,
                    (rng.nextFloat() - 0.5f) * 0.14f));
        }
        toast = best.dying ? "Mouse killed!" : "Mouse hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    // ---------------------------------------------------------------------
    //  Skeleton mob: melee chaser with a one-cube iron sword.  Swings deal
    //  0 damage for now (Skeleton.SWING_DAMAGE); it never burns in daylight.
    // ---------------------------------------------------------------------
    /** Natural-spawn timer: every ~30-50s a skeleton walks in near the player. */
    private int skeletonSpawnTimer = 20 * 30;
    private static final int SKELETON_MAX = 2;

    private void tickSkeletons() {
        boolean dead = player.isDead();
        // Natural spawning, NIGHT only now: like the red mice, a ring of dry
        // land around the player -- close enough to reach melee range.
        if (skeletons.size() == 0 && skeletonSpawnTimer > 100) skeletonSpawnTimer = 100;
        if (!dead && isNight() && --skeletonSpawnTimer <= 0) {
            skeletonSpawnTimer = 20 * (30 + rng.nextInt(21));
            if (skeletons.size() < SKELETON_MAX) {
                int spawned = spawnSkeletonWild(1);
                if (spawned > 0) {
                    toast = "A skeleton lurches toward you...";
                    toastUntil = System.currentTimeMillis() + 900;
                }
            }
        }

        for (int i = skeletons.size() - 1; i >= 0; --i) {
            try {
                Skeleton s = (Skeleton) skeletons.elementAt(i);
                s.tick(player);
                // Slash moment: the one tick the blade would connect.  Damage
                // is 0 by design -- the hook stays so it can be enabled later
                // (just set Skeleton.SWING_DAMAGE > 0).
                if (s.isSlashTick() && Skeleton.SWING_DAMAGE > 0 && !player.isDead()) {
                    float dx = player.x - s.x;
                    float dz = player.z - s.z;
                    if (dx * dx + dz * dz < 1.6f * 1.6f) {
                        player.hurt(Skeleton.SWING_DAMAGE);
                        spawnBreakParticles((int) player.x, (int) (player.y + 0.5f), (int) player.z, 0);
                    }
                }
                if (s.removed) {
                    // Loot: the collapsed skeleton scatters a few bones.
                    int n = 2 + rng.nextInt(2);
                    for (int k = 0; k < n; ++k) {
                        bones.addElement(new Skeleton.Bone(level,
                                s.x + (rng.nextFloat() - 0.5f) * 0.4f,
                                s.y + 0.6f + rng.nextFloat() * 0.4f,
                                s.z + (rng.nextFloat() - 0.5f) * 0.4f,
                                (rng.nextFloat() - 0.5f) * 0.10f,
                                0.12f + rng.nextFloat() * 0.06f,
                                (rng.nextFloat() - 0.5f) * 0.10f));
                    }
                    skeletons.removeElementAt(i);
                }
            } catch (Throwable t) {
                skeletons.removeElementAt(i);
                handleRuntimeError("skeleton", t);
            }
        }
    }

    // ---------------------------------------------------------------------
    //  HaiZhu: the dirt-cross-summoned pig head.  Never spawns naturally.
    // ---------------------------------------------------------------------
    private static final int DIRT_ID = 3;
    /** The ritual cap: mined "stone" in the inventory is COBBLESTONE, so
     *  both count -- otherwise nobody could ever summon it. */
    private static boolean isCapStone(int id) {
        return id == 1 || id == 4;   // Stone or Cobblestone
    }

    private void tickHaiZhus() {
        for (int i = haiZhus.size() - 1; i >= 0; --i) {
            try {
                HaiZhu m = (HaiZhu) haiZhus.elementAt(i);
                m.tick(player);
                if (m.removed) {
                    if (m.health <= 0) {
                        // Shrank to nothing: just the Haizhuball drop
                        // (no lava block any more -- removed by request).
                        spawnBreakParticles((int) m.x, (int) (m.y + 0.5f), (int) m.z, 3);
                        haizhuballs.addElement(new Skeleton.Bone(level,
                                m.x, m.y + 0.4f, m.z,
                                (rng.nextFloat() - 0.5f) * 0.08f,
                                0.12f,
                                (rng.nextFloat() - 0.5f) * 0.08f));
                    }
                    haiZhus.removeElementAt(i);
                    maybeSpawnForward(m.x, m.y, m.z);
                }
            } catch (Throwable t) {
                haiZhus.removeElementAt(i);
                handleRuntimeError("haizhu", t);
            }
        }
    }

    /**
     * After placing dirt/stone/cobble: probe for the summon ritual.  A
     * stone/cobble cap sitting directly on a 3-5 tall dirt bar that has a
     * pair of dirt arms somewhere along it (T, crucifix, plus, tall -- all
     * accepted), or a flat dirt plus with a cap on its centre.
     */
    private void trySummonHaiZhu(int px, int py, int pz) {
        // Anchor on the CAP: a stone/cobble sitting DIRECTLY on top of a
        // vertical dirt bar (3-5) that has a pair of arms somewhere along
        // it.  Anchoring on the cap makes accidental mass-summons in dirt
        // buildings effectively impossible while accepting ANY cross the
        // player might build (T, crucifix, plus, tall...).
        for (int dy = 0; dy <= 5; ++dy) {
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    if (ritualAt(level, px + dx, py + dy, pz + dz,
                            DIRT_ID, 1, 4, -1) >= 0) {
                        summonHaiZhuUnderCap(px + dx, py + dy, pz + dz);
                        return;
                    }
                }
            }
        }
        // Also accept a FLAT plus of dirt on the ground, capped on its centre.
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                for (int dy = -2; dy <= 2; ++dy) {
                    if (!isFlatDirtPlus(level, px + dx, py + dy, pz + dz)) continue;
                    if (!isCapStone(level.getTile(px + dx, py + dy + 1, pz + dz))) continue;
                    summonFlatHaiZhuAt(px + dx, py + dy, pz + dz);
                    return;
                }
            }
        }
    }

    /**
     * Cap-anchored ritual probe, static + package-private for unit tests.
     * A valid ritual at (capX,capY,capZ): stone/cobble cap, a contiguous
     * dirt bar of 3..5 right below it, and a pair of dirt arms at some bar
     * level along X or Z (T, crucifix, plus and tall crosses all match).
     * @return -1 if not a ritual, else (armY << 8) | (barLen << 4) | ori.
     */
    static int ritualAt(ILevel lv, int capX, int capY, int capZ,
                        int barId, int capA, int capB, int capC) {
        return ritualAt(lv, capX, capY, capZ, barId, capA, capB, capC, 3);
    }

    /** minBar lets the YouJi ritual accept a shorter 2-block bar: cobble
     *  only exists where the player mined and placed it, so unlike dirt
     *  there is no natural-terrain mass-summon risk with a loose shape. */
    static int ritualAt(ILevel lv, int capX, int capY, int capZ,
                        int barId, int capA, int capB, int capC, int minBar) {
        int cap = lv.getTile(capX, capY, capZ);
        if (cap != capA && cap != capB && cap != capC) return -1;
        if (lv.getTile(capX, capY - 1, capZ) != barId) return -1;
        int barLen = 0;
        while (barLen < 5 && lv.getTile(capX, capY - 1 - barLen, capZ) == barId) ++barLen;
        if (barLen < minBar) return -1;
        for (int i = 0; i < barLen; ++i) {
            int y = capY - 1 - i;
            if (lv.getTile(capX - 1, y, capZ) == barId
                    && lv.getTile(capX + 1, y, capZ) == barId) {
                return (y << 8) | (barLen << 4) | 0;
            }
            if (lv.getTile(capX, y, capZ - 1) == barId
                    && lv.getTile(capX, y, capZ + 1) == barId) {
                return (y << 8) | (barLen << 4) | 1;
            }
        }
        return -1;
    }

    /** Consume the cap, the whole bar and the arm pair, then summon. */
    private void summonHaiZhuUnderCap(int capX, int capY, int capZ) {
        int info = ritualAt(level, capX, capY, capZ, DIRT_ID, 1, 4, -1);
        int armY = info >> 8;
        int barLen = (info >> 4) & 0x0F;
        int ori = info & 0x0F;
        level.setTile(capX, capY, capZ, 0);
        if (net != null) net.sendSetBlock(capX, capY, capZ, 0, false);
        for (int i = 0; i < barLen; ++i) consumeDirt(capX, capY - 1 - i, capZ);
        if (ori == 0) {
            consumeDirt(capX - 1, armY, capZ);
            consumeDirt(capX + 1, armY, capZ);
        } else {
            consumeDirt(capX, armY, capZ - 1);
            consumeDirt(capX, armY, capZ + 1);
        }
        haiZhus.addElement(new HaiZhu(level, capX + 0.5f, armY, capZ + 0.5f));
        spawnBreakParticles(capX, capY - 1, capZ, 3);
        toast = "A HaiZhu answers your call!";
        toastUntil = System.currentTimeMillis() + 1200;
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    // ---------------------------------------------------------------------
    //  YouJi Wang (the Rooster King): a COBBLESTONE cross topped with ANY
    //  ore (coal / iron / gold).  Same cap-anchored detection as HaiZhu.
    // ---------------------------------------------------------------------
    private static boolean isOreCap(int id) {
        return id == 14 || id == 15 || id == 16;   // gold / iron / coal ore
    }

    private void trySummonYouJi(int px, int py, int pz) {
        for (int dy = 0; dy <= 5; ++dy) {
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    if (ritualAt(level, px + dx, py + dy, pz + dz,
                            4, 14, 15, 16, 2) >= 0) {   // short bar OK
                        summonYouJiUnderCap(px + dx, py + dy, pz + dz);
                        return;
                    }
                }
            }
        }
        // Also accept a FLAT cobble plus on the ground, ore on its centre.
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                for (int dy = -2; dy <= 2; ++dy) {
                    int cx = px + dx, cy = py + dy, cz = pz + dz;
                    if (!isFlatPlusOf(level, cx, cy, cz, 4)) continue;
                    if (!isOreCap(level.getTile(cx, cy + 1, cz))) continue;
                    summonFlatYouJiAt(cx, cy, cz);
                    return;
                }
            }
        }
    }

    /** Consume the ore cap, the cobble bar and the arms, then summon. */
    private void summonYouJiUnderCap(int capX, int capY, int capZ) {
        int info = ritualAt(level, capX, capY, capZ, 4, 14, 15, 16, 2);
        int armY = info >> 8;
        int barLen = (info >> 4) & 0x0F;
        int ori = info & 0x0F;
        level.setTile(capX, capY, capZ, 0);
        if (net != null) net.sendSetBlock(capX, capY, capZ, 0, false);
        for (int i = 0; i < barLen; ++i) consumeBlock(capX, capY - 1 - i, capZ, 4);
        if (ori == 0) {
            consumeBlock(capX - 1, armY, capZ, 4);
            consumeBlock(capX + 1, armY, capZ, 4);
        } else {
            consumeBlock(capX, armY, capZ - 1, 4);
            consumeBlock(capX, armY, capZ + 1, 4);
        }
        YouJi yj = new YouJi(level, capX + 0.5f, armY, capZ + 0.5f);
        // The beast faces its summoner when it appears.
        float fdx = player.x - yj.x, fdz = player.z - yj.z;
        float flen = (float) Math.sqrt(fdx * fdx + fdz * fdz);
        if (flen > 0.001f) { yj.headingX = fdx / flen; yj.headingZ = fdz / flen; }
        youJis.addElement(yj);
        spawnBreakParticles(capX, capY - 1, capZ, 4);
        toast = "The Rooster King answers your call!";
        toastUntil = System.currentTimeMillis() + 1200;
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    private void consumeBlock(int x, int y, int z, int id) {
        if (level.getTile(x, y, z) == id) {
            level.setTile(x, y, z, 0);
            if (net != null) net.sendSetBlock(x, y, z, 0, false);
        }
    }

    private void tickYouJis() {
        for (int i = youJis.size() - 1; i >= 0; --i) {
            try {
                YouJi m = (YouJi) youJis.elementAt(i);
                m.tick(player);
                if (m.removed) {
                    youJis.removeElementAt(i);
                    maybeSpawnForward(m.x, m.y, m.z);
                    // Loot: the felled king sheds 1-2 feathers.
                    int n = 1 + rng.nextInt(2);
                    for (int k = 0; k < n; ++k) {
                        feathers.addElement(new Skeleton.Bone(level,
                                m.x + (rng.nextFloat() - 0.5f) * 0.4f,
                                m.y + 0.4f + rng.nextFloat() * 0.3f,
                                m.z + (rng.nextFloat() - 0.5f) * 0.4f,
                                (rng.nextFloat() - 0.5f) * 0.10f,
                                0.12f + rng.nextFloat() * 0.06f,
                                (rng.nextFloat() - 0.5f) * 0.10f));
                    }
                }
            } catch (Throwable t) {
                youJis.removeElementAt(i);
                handleRuntimeError("youji", t);
            }
        }
    }

    /** Punch hit-test: ONE damage per punch -- 10 HP = 10 punches. */
    private boolean attackYouJi() {
        if (youJis.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        YouJi best = null;
        float bestT = 4.5f;
        for (int i = 0; i < youJis.size(); ++i) {
            YouJi m = (YouJi) youJis.elementAt(i);
            if (m == null || m.removed || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.48f, m.z, 0.45f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.75f, m.z, 0.35f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = m; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(1, player);
        toast = best.dying ? "Rooster King felled!" : "Rooster King hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    /** Flat plus of one material on one Y level: centre + 4 arms (5 blocks). */
    static boolean isFlatPlusOf(ILevel lv, int cx, int cy, int cz, int id) {
        return lv.getTile(cx, cy, cz) == id
                && lv.getTile(cx - 1, cy, cz) == id
                && lv.getTile(cx + 1, cy, cz) == id
                && lv.getTile(cx, cy, cz - 1) == id
                && lv.getTile(cx, cy, cz + 1) == id;
    }

    /** Flat plus of dirt on one Y level: centre + 4 arms (5 blocks). */
    static boolean isFlatDirtPlus(ILevel lv, int cx, int cy, int cz) {
        return isFlatPlusOf(lv, cx, cy, cz, DIRT_ID);
    }

    /** Flat-plus ritual: consume the 5 dirt + the cap, spawn on top. */
    private void summonFlatHaiZhuAt(int cx, int cy, int cz) {
        consumeDirt(cx, cy, cz);
        consumeDirt(cx - 1, cy, cz);
        consumeDirt(cx + 1, cy, cz);
        consumeDirt(cx, cy, cz - 1);
        consumeDirt(cx, cy, cz + 1);
        if (isCapStone(level.getTile(cx, cy + 1, cz))) {
            level.setTile(cx, cy + 1, cz, 0);
            if (net != null) net.sendSetBlock(cx, cy + 1, cz, 0, false);
        }
        haiZhus.addElement(new HaiZhu(level, cx + 0.5f, cy + 1.0f, cz + 0.5f));
        spawnBreakParticles(cx, cy + 1, cz, 3);
        toast = "A HaiZhu answers your call!";
        toastUntil = System.currentTimeMillis() + 1200;
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    /** Flat-plus YouJi ritual: consume the 5 cobble + the ore cap, spawn on top. */
    private void summonFlatYouJiAt(int cx, int cy, int cz) {
        consumeBlock(cx, cy, cz, 4);
        consumeBlock(cx - 1, cy, cz, 4);
        consumeBlock(cx + 1, cy, cz, 4);
        consumeBlock(cx, cy, cz - 1, 4);
        consumeBlock(cx, cy, cz + 1, 4);
        if (isOreCap(level.getTile(cx, cy + 1, cz))) {
            level.setTile(cx, cy + 1, cz, 0);
            if (net != null) net.sendSetBlock(cx, cy + 1, cz, 0, false);
        }
        YouJi yj = new YouJi(level, cx + 0.5f, cy + 1.0f, cz + 0.5f);
        float fdx = player.x - yj.x, fdz = player.z - yj.z;
        float flen = (float) Math.sqrt(fdx * fdx + fdz * fdz);
        if (flen > 0.001f) { yj.headingX = fdx / flen; yj.headingZ = fdz / flen; }
        youJis.addElement(yj);
        spawnBreakParticles(cx, cy + 1, cz, 4);
        toast = "The Rooster King answers your call!";
        toastUntil = System.currentTimeMillis() + 1200;
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    // ---------------------------------------------------------------------
    //  ZiShu Huang (the Rat Emperor): an IRON-ORE cross topped with a
    //  GOLD ORE.  Same cap-anchored detection family; NOT on the
    //  Haizhuball kill list (like HaiZhu / YouJi).
    // ---------------------------------------------------------------------
    private void trySummonZiShu(int px, int py, int pz) {
        for (int dy = 0; dy <= 5; ++dy) {
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    if (ritualAt(level, px + dx, py + dy, pz + dz,
                            15, 14, -1, -1, 2) >= 0) {  // iron bar, gold cap
                        summonZiShuUnderCap(px + dx, py + dy, pz + dz);
                        return;
                    }
                }
            }
        }
        // Also accept a FLAT iron-ore plus on the ground, gold on its centre.
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                for (int dy = -2; dy <= 2; ++dy) {
                    int cx = px + dx, cy = py + dy, cz = pz + dz;
                    if (!isFlatPlusOf(level, cx, cy, cz, 15)) continue;
                    if (level.getTile(cx, cy + 1, cz) != 14) continue;
                    summonFlatZiShuAt(cx, cy, cz);
                    return;
                }
            }
        }
    }

    /** Consume the gold cap, the iron bar and the arms, then summon. */
    private void summonZiShuUnderCap(int capX, int capY, int capZ) {
        int info = ritualAt(level, capX, capY, capZ, 15, 14, -1, -1, 2);
        int armY = info >> 8;
        int barLen = (info >> 4) & 0x0F;
        int ori = info & 0x0F;
        level.setTile(capX, capY, capZ, 0);
        if (net != null) net.sendSetBlock(capX, capY, capZ, 0, false);
        for (int i = 0; i < barLen; ++i) consumeBlock(capX, capY - 1 - i, capZ, 15);
        if (ori == 0) {
            consumeBlock(capX - 1, armY, capZ, 15);
            consumeBlock(capX + 1, armY, capZ, 15);
        } else {
            consumeBlock(capX, armY, capZ - 1, 15);
            consumeBlock(capX, armY, capZ + 1, 15);
        }
        spawnZiShu(capX + 0.5f, armY, capZ + 0.5f);
        spawnBreakParticles(capX, capY - 1, capZ, 15);
    }

    /** Flat-plus ritual: consume the 5 iron ores + the gold cap, spawn on top. */
    private void summonFlatZiShuAt(int cx, int cy, int cz) {
        consumeBlock(cx, cy, cz, 15);
        consumeBlock(cx - 1, cy, cz, 15);
        consumeBlock(cx + 1, cy, cz, 15);
        consumeBlock(cx, cy, cz - 1, 15);
        consumeBlock(cx, cy, cz + 1, 15);
        if (level.getTile(cx, cy + 1, cz) == 14) {
            level.setTile(cx, cy + 1, cz, 0);
            if (net != null) net.sendSetBlock(cx, cy + 1, cz, 0, false);
        }
        spawnZiShu(cx + 0.5f, cy + 1.0f, cz + 0.5f);
        spawnBreakParticles(cx, cy + 1, cz, 15);
    }

    private void spawnZiShu(float x, float y, float z) {
        ZiShu zs = new ZiShu(level, x, y, z);
        // The emperor faces his summoner when he appears.
        float fdx = player.x - zs.x, fdz = player.z - zs.z;
        float flen = (float) Math.sqrt(fdx * fdx + fdz * fdz);
        if (flen > 0.001f) { zs.headingX = fdx / flen; zs.headingZ = fdz / flen; }
        ziShus.addElement(zs);
        toast = "The Rat Emperor answers your call!";
        toastUntil = System.currentTimeMillis() + 1200;
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    private void tickZiShus() {
        for (int i = ziShus.size() - 1; i >= 0; --i) {
            try {
                ZiShu m = (ZiShu) ziShus.elementAt(i);
                m.tick(player);
                if (m.removed) {
                    ziShus.removeElementAt(i);
                    maybeSpawnForward(m.x, m.y, m.z);
                    // Loot: the felled emperor spills 3-5 wisdom teeth.
                    int n = 3 + rng.nextInt(3);
                    for (int k = 0; k < n; ++k) {
                        teeth.addElement(new Skeleton.Bone(level,
                                m.x + (rng.nextFloat() - 0.5f) * 0.4f,
                                m.y + 0.4f + rng.nextFloat() * 0.3f,
                                m.z + (rng.nextFloat() - 0.5f) * 0.4f,
                                (rng.nextFloat() - 0.5f) * 0.10f,
                                0.12f + rng.nextFloat() * 0.06f,
                                (rng.nextFloat() - 0.5f) * 0.10f));
                    }
                }
            } catch (Throwable t) {
                ziShus.removeElementAt(i);
                handleRuntimeError("zishu", t);
            }
        }
    }

    /**
     * Felling a summoned idol (HaiZhu / YouJi / ZiShu) has a 60% chance to
     * summon a FORWARD: it crawls out of the ground at the idol's death
     * spot and hunts the player.  50 HP, 5 damage per lunge, no drops.
     */
    private static final float FORWARD_SPAWN_CHANCE = 0.6f;

    private void maybeSpawnForward(float x, float y, float z) {
        if (forwards.size() >= 2) return;
        if (rng.nextFloat() >= FORWARD_SPAWN_CHANCE) return;
        // Find the surface at the death spot: first solid column with two
        // air blocks above it, scanned downward from a bit above the idol.
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        float targetY = y;   // fall back to where the idol stood
        for (int by = (int) Math.floor(y) + 3; by > 0; --by) {
            if (level.isSolidTile(bx, by, bz)
                    && !level.isSolidTile(bx, by + 1, bz)
                    && !level.isSolidTile(bx, by + 2, bz)) {
                targetY = by + 1.0f;
                break;
            }
        }
        Forward f = new Forward(level, x, targetY - 2.0f, z, targetY);
        forwards.addElement(f);
        toast = "A Forward crawls out of the ground!";
        toastUntil = System.currentTimeMillis() + 1400;
    }

    private void tickForwards() {
        boolean playerDead = player.isDead();
        for (int i = forwards.size() - 1; i >= 0; --i) {
            try {
                Forward f = (Forward) forwards.elementAt(i);
                f.tick(player);
                // Dirt sprinkles while it claws its way up.
                if (f.crawling && (f.deathTime++ % 5) == 0) {
                    spawnBreakParticles((int) f.x, (int) f.crawlTargetY,
                            (int) f.z, 3);
                }
                // Lunge connects: 5 damage to the player.
                if (f.isSlashTick() && !playerDead) {
                    float dx = player.x - f.x, dz = player.z - f.z;
                    if (dx * dx + dz * dz < 2.6f * 2.6f) player.hurt(5);
                }
                if (f.removed) forwards.removeElementAt(i);   // no drops
            } catch (Throwable t) {
                forwards.removeElementAt(i);
                handleRuntimeError("forward", t);
            }
        }
    }

    private boolean attackForward() {
        if (forwards.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        Forward best = null;
        float bestT = 4.5f;
        for (int i = 0; i < forwards.size(); ++i) {
            Forward m = (Forward) forwards.elementAt(i);
            if (m == null || m.removed || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.90f, m.z, 0.50f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 1.75f, m.z, 0.42f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = m; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(1, player);
        toast = best.dying ? "Forward felled!" : "Forward: " + best.health + " HP";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    private boolean attackZiShu() {
        if (ziShus.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        ZiShu best = null;
        float bestT = 4.5f;
        for (int i = 0; i < ziShus.size(); ++i) {
            ZiShu m = (ZiShu) ziShus.elementAt(i);
            if (m == null || m.removed || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.50f, m.z, 0.45f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.95f, m.z, 0.42f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = m; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(1, player);
        toast = best.dying ? "Rat Emperor felled!" : "Rat Emperor hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
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
    private void tickBones() {
        tickItemEntities(bones, ITEM_BONE, "bone");
    }

    /**
     * Haizhuball ground drops.  Only ONE is ever held: picking up a fresh
     * ball REPLACES the worn one, resetting durability to full (100).
     */
    private void tickHaizhuballs() {
        int before = blockCounts[ITEM_HAIZHUBALL];
        tickItemEntities(haizhuballs, ITEM_HAIZHUBALL, "Haizhuball");
        if (blockCounts[ITEM_HAIZHUBALL] > 1) {
            blockCounts[ITEM_HAIZHUBALL] = 1;
        }
        if (blockCounts[ITEM_HAIZHUBALL] > before) {
            // A new ball was just collected: it replaces the old one.
            haizhuballDurability = HAIZHUBALL_MAX_DUR;
            toast = "Haizhuball: " + HAIZHUBALL_MAX_DUR + " uses";
            toastUntil = System.currentTimeMillis() + 900;
        }
    }

    /** Slimeballs dropped by slimes: same pickup pipeline as the bones. */
    private void tickSlimeBalls() {
        tickItemEntities(slimeBalls, ITEM_SLIMEBALL, "slimeball");
    }

    /** Gunpowder dropped by defused firecrackers: same pickup pipeline. */
    private void tickGunpowder() {
        tickItemEntities(gunpowders, ITEM_GUNPOWDER, "gunpowder");
    }

    /** YouJi feathers shed by the felled rooster king: same pipeline. */
    private void tickFeathers() {
        tickItemEntities(feathers, ITEM_YOUIJI_FEATHER, "YouJi Feather");
    }

    /** ZiShu's wisdom teeth dropped by the felled rat emperor. */
    private void tickTeeth() {
        tickItemEntities(teeth, ITEM_ZISHU_TOOTH, "Zishu Tooth");
    }

    /** Shared ground-item tick: physics + magnet + inventory pickup. */
    private void tickItemEntities(java.util.Vector items, int itemId, String name) {
        for (int i = items.size() - 1; i >= 0; --i) {
            try {
                Skeleton.Bone b = (Skeleton.Bone) items.elementAt(i);
                if (b.tick(player)) {
                    int count = addBlockCount(itemId, 1);
                    toast = name + " x" + count;
                    toastUntil = System.currentTimeMillis() + 700;
                }
                if (b.removed) items.removeElementAt(i);
            } catch (Throwable t) {
                items.removeElementAt(i);
                handleRuntimeError("item_drop", t);
            }
        }
    }

    /** Wild natural spawn: dry land in a ring 6..12 blocks around the player. */
    private int spawnSkeletonWild(int count) {
        if (level == null || player == null) return 0;
        int placed = 0;
        for (int i = 0; i < count; ++i) {
            for (int attempt = 0; attempt < 30; ++attempt) {
                double ang = rng.nextDouble() * 2.0 * Math.PI;
                int dist = 6 + rng.nextInt(7);
                int bx = (int) Math.floor(player.x + Math.cos(ang) * dist);
                int bz = (int) Math.floor(player.z + Math.sin(ang) * dist);
                int sy = findSurfaceY(bx, bz);
                if (sy < 0) continue;
                if (level.getTile(bx, sy + 1, bz) != 0 || level.getTile(bx, sy + 2, bz) != 0) continue;
                skeletons.addElement(new Skeleton(level, bx + 0.5f, sy + 1.0f, bz + 0.5f));
                ++placed;
                break;
            }
        }
        return placed;
    }

    /** Same ray-punch test as attackRedMouse(), for the skeleton hitbox. */
    private boolean attackSkeleton() {
        if (skeletons.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        Skeleton best = null;
        float bestT = 4.5f;
        for (int i = 0; i < skeletons.size(); ++i) {
            Skeleton s = (Skeleton) skeletons.elementAt(i);
            if (s == null || s.removed || s.dying) continue;
            // Two forgiving spheres cover the whole body: lower (legs/body)
            // and upper (head).  Aiming anywhere at the skeleton hits.
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, s.x, s.y + 0.70f, s.z, 0.45f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, s.x, s.y + 1.20f, s.z, 0.42f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = s; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(2, player);
        toast = best.dying ? "Skeleton destroyed!" : "Skeleton hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    // ---------------------------------------------------------------------
    //  Firecracker mob: night spawner, punch hit-test, and the
    //  blast.  The explosion destroys terrain and hurts ONLY the player --
    //  every other mob is immune to explosion damage (user spec).
    // ---------------------------------------------------------------------
    private int firecrackerSpawnTimer = 20 * 35;
    private static final int FIRECRACKER_MAX = 2;
    /** Blast damage: 0.15 x the 20 HP health bar = 3 HP (1.5 hearts). */
    private static final int FIRECRACKER_DAMAGE = 3;
    private static final float FIRECRACKER_BLAST_R = 2.0f;    // terrain
    private static final float FIRECRACKER_HURT_R = 3.5f;     // player

    private void tickFirecrackers() {
        boolean dead = player.isDead();
        if (firecrackers.size() == 0 && firecrackerSpawnTimer > 100) firecrackerSpawnTimer = 100;
        if (!dead && isNight() && --firecrackerSpawnTimer <= 0) {
            firecrackerSpawnTimer = 20 * (35 + rng.nextInt(21));
            if (firecrackers.size() < FIRECRACKER_MAX) {
                int spawned = spawnMobWild(firecrackers, 1, 2);
                if (spawned > 0) {
                    toast = "A firecracker stalks you...";
                    toastUntil = System.currentTimeMillis() + 900;
                }
            }
        }

        for (int i = firecrackers.size() - 1; i >= 0; --i) {
            try {
                Firecracker f = (Firecracker) firecrackers.elementAt(i);
                f.tick(player);
                if (f.explodeNow) {
                    doFirecrackerBlast(f);
                    firecrackers.removeElementAt(i);
                    continue;
                }
                if (f.removed) {
                    // Loot: the defused corpse spills 1-2 gunpowder piles.
                    if (f.health <= 0) {
                        int n = 1 + rng.nextInt(2);
                        for (int k = 0; k < n; ++k) {
                            gunpowders.addElement(new Skeleton.Bone(level,
                                    f.x + (rng.nextFloat() - 0.5f) * 0.4f,
                                    f.y + 0.4f + rng.nextFloat() * 0.3f,
                                    f.z + (rng.nextFloat() - 0.5f) * 0.4f,
                                    (rng.nextFloat() - 0.5f) * 0.10f,
                                    0.12f + rng.nextFloat() * 0.06f,
                                    (rng.nextFloat() - 0.5f) * 0.10f));
                        }
                    }
                    firecrackers.removeElementAt(i);
                }
            } catch (Throwable t) {
                firecrackers.removeElementAt(i);
                handleRuntimeError("firecracker", t);
            }
        }
    }

    /**
     * The explosion: clear terrain in a small sphere (bedrock survives),
     * spray TNT particles, and hurt the PLAYER when in range.  Other mobs
     * are deliberately untouched -- they are immune to blast damage.
     */
    private void doFirecrackerBlast(Firecracker f) {
        int cx = (int) Math.floor(f.x);
        int cy = (int) Math.floor(f.y + 0.6f);
        int cz = (int) Math.floor(f.z);
        int r = (int) Math.ceil(FIRECRACKER_BLAST_R);
        for (int dx = -r; dx <= r; ++dx) {
            for (int dy = -r; dy <= r; ++dy) {
                for (int dz = -r; dz <= r; ++dz) {
                    float dSq = dx * dx + dy * dy + dz * dz;
                    if (dSq > FIRECRACKER_BLAST_R * FIRECRACKER_BLAST_R) continue;
                    int bx = cx + dx, by = cy + dy, bz = cz + dz;
                    int id = level.getTile(bx, by, bz);
                    if (id == 0 || id == 7) continue;   // air / bedrock survive
                    level.setTile(bx, by, bz, 0);
                    if (net != null) net.sendSetBlock(bx, by, bz, 0, false);
                }
            }
        }
        spawnBreakParticles(cx, cy, cz, 46);            // TNT-textured debris
        spawnBreakParticles(cx + 1, cy, cz - 1, 46);
        spawnBreakParticles(cx - 1, cy, cz + 1, 46);

        if (player != null && !player.isDead()) {
            float dx = player.x - f.x;
            float dy = (player.y - 0.8f) - (f.y + 0.6f);
            float dz = player.z - f.z;
            if (dx * dx + dy * dy + dz * dz
                    < FIRECRACKER_HURT_R * FIRECRACKER_HURT_R) {
                player.hurt(FIRECRACKER_DAMAGE);
                toast = "Boom! Firecracker hit you";
                toastUntil = System.currentTimeMillis() + 1000;
            } else {
                toast = "Boom!";
                toastUntil = System.currentTimeMillis() + 800;
            }
        }
    }

    /** Same ray-punch test as the skeleton, for the firecracker hitbox. */
    private boolean attackFirecracker() {
        if (firecrackers.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        Firecracker best = null;
        float bestT = 4.5f;
        for (int i = 0; i < firecrackers.size(); ++i) {
            Firecracker f = (Firecracker) firecrackers.elementAt(i);
            if (f == null || f.removed || f.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, f.x, f.y + 0.50f, f.z, 0.42f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, f.x, f.y + 1.08f, f.z, 0.40f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { best = f; bestT = tm; }
        }
        if (best == null) return false;
        best.hurt(2, player);
        toast = best.dying ? "Firecracker defused!" : "Firecracker hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    private boolean attackSlime() {
        if (slimes.size() == 0) return false;
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        Slime best = null;
        float bestT = 4.0f;
        for (int i = 0; i < slimes.size(); ++i) {
            Slime s = (Slime) slimes.elementAt(i);
            if (s == null || s.removed || s.dying) continue;
            float ox = s.x - ex;
            float oy = s.y + Slime.HALF - ey;
            float oz = s.z - ez;
            float t = ox * dx + oy * dy + oz * dz; // distance along the look ray
            if (t < 0.0f || t > bestT) continue;
            float cx = ox - dx * t, cy = oy - dy * t, cz = oz - dz * t;
            if (cx * cx + cy * cy + cz * cz > 0.49f) continue; // within 0.7 of the ray
            best = s;
            bestT = t;
        }
        if (best == null) return false;
        best.hurt(2, player);
        // No auto-refill any more: slimes come ONLY from the key-9 spawner.
        toast = best.dying ? "Slime killed!" : "Slime hit!";
        toastUntil = System.currentTimeMillis() + 700;
        return true;
    }

    /** Topmost solid block below the sky at this column, or -1. */
    private int findSurfaceY(int bx, int bz) {
        int start, floorY;
        if (level instanceof InfiniteLevel) {
            int surface = ((InfiniteLevel) level).estimateSurfaceY(bx, bz);
            start = surface + 4;
            floorY = surface - 4;
        } else {
            start = level.getHeight() - 2;
            floorY = 1;
        }
        for (int y = start; y > floorY; --y) {
            if (level.isSolidTile(bx, y, bz)) return y;
        }
        return -1;
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(skyColor());
        g.fillRect(0, 0, w, h);

        if (renderer == null) {
            g.setColor(0xFFFFFF);
            g.drawString("Loading...", w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
            return;
        }
        if (connecting) { drawConnecting(g, w, h); return; }

        boolean bound = false;
        try {
            g3d.bind(g);
            bound = true;
            renderer.render(player, timer.a, w / 2, h / 2);
            if (multiplayer && net != null) renderer.renderNetPlayers(net.getPlayers(), timer.a);
            renderer.renderParticles(particles, player, timer.a);
            renderer.renderDroppedBlocks(drops, timer.a);
            renderer.renderSlimes(slimes, timer.a);
            renderer.renderRedMice(redMice, timer.a);
            renderer.renderSkeletons(skeletons, timer.a);
            renderer.renderFirecrackers(firecrackers, timer.a);
            renderer.renderBones(bones, timer.a);
            renderer.renderSlimeBalls(slimeBalls, timer.a);
            renderer.renderGunpowder(gunpowders, timer.a);
            renderer.renderFeathers(feathers, timer.a);
            renderer.renderTeeth(teeth, timer.a);
            renderer.renderHaiZhus(haiZhus, timer.a);
            renderer.renderYouJis(youJis, timer.a);
            renderer.renderZiShus(ziShus, timer.a);
            // Forward boss readout: "forward:50" while one is alive.
            for (int i = 0; i < forwards.size(); ++i) {
                Forward f = (Forward) forwards.elementAt(i);
                if (f == null || f.removed || f.dying) continue;
                String fs = "forward:" + f.health;
                g.setFont(Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL));
                g.setColor(0x000000);
                g.drawString(fs, w / 2 + 1, 21, Graphics.HCENTER | Graphics.TOP);
                g.setColor(0xE02020);
                g.drawString(fs, w / 2, 20, Graphics.HCENTER | Graphics.TOP);
                break;   // one label is enough
            }
            renderer.renderHaizhuballs(haizhuballs, timer.a);
            renderer.renderArrows(arrows, timer.a);
            renderer.renderBowDrops(bows);
            if (hitResult != null) renderer.renderHit(hitResult);
            // The Forward draws AFTER a depth-buffer reset: freej2me's
            // flush() presents the queued batches and clears the z-buffer,
            // so terrain can never hide the boss -- it stays visible
            // through hills and walls, always in your face.
            try { g3d.flush(); } catch (Throwable ignore) {}
            renderer.renderForwards(forwards, timer.a);
        } catch (Throwable t) {
        } finally {
            if (bound) {
                try { g3d.flush(); } catch (Throwable ignore) {}
                try { g3d.release(g); } catch (Throwable ignore) {}
            }
        }

        g.setColor(0xFFFFFF);
        g.drawLine(w / 2 - 4, h / 2, w / 2 + 4, h / 2);
        g.drawLine(w / 2, h / 2 - 4, w / 2, h / 2 + 4);

        drawHud(g, w, h);
        drawHealth(g, w, h);
        if (multiplayer && net != null) drawChat(g, w, h);
        if (showInventory) drawInventory(g, w, h);
        if (showBoneDialog) drawBoneDialog(g, w, h);
        if (showMenu) drawMenu(g, w, h);
        if (player != null && player.isDead()) drawDeath(g, w, h);
    }

    // ---- Day/night cycle: only the SKY colour fades to a darker blue at
    // ---- night and back.  World/block brightness NEVER changes (user
    // ---- spec: no dimming of the terrain, no negative brightness). ----
    private static final int SKY_DAY   = 0x88B0FF;
    private static final int SKY_NIGHT = 0x3E4E85;    // darker blue, still blue
    private static final long DAY_CYCLE_MS = 240000L; // full day = 4 minutes
    private long dayCycleStart = 0L;

    /** Sky colour right now: day -> dusk fade -> night -> dawn fade. */
    private int skyColor() {
        // 0..0.45 day, 0.45..0.55 dusk, 0.55..0.90 night, 0.90..1.00 dawn.
        float f = dayPhase();
        float k;
        if (f < 0.45f) k = 0f;
        else if (f < 0.55f) k = (f - 0.45f) / 0.10f;
        else if (f < 0.90f) k = 1f;
        else k = 1f - (f - 0.90f) / 0.10f;
        if (k < 0f) k = 0f;
        if (k > 1f) k = 1f;
        int ar = (SKY_DAY >> 16) & 0xFF, ag = (SKY_DAY >> 8) & 0xFF, ab = SKY_DAY & 0xFF;
        int br = (SKY_NIGHT >> 16) & 0xFF, bg = (SKY_NIGHT >> 8) & 0xFF, bb = SKY_NIGHT & 0xFF;
        int r = ar + (int) ((br - ar) * k);
        int g = ag + (int) ((bg - ag) * k);
        int b = ab + (int) ((bb - ab) * k);
        return (r << 16) | (g << 8) | b;
    }

    /** Position in the day cycle, 0..1 (0 = fresh dawn, 0.5 = mid-dusk). */
    private float dayPhase() {
        if (dayCycleStart == 0L) dayCycleStart = System.currentTimeMillis();
        long t = (System.currentTimeMillis() - dayCycleStart) % DAY_CYCLE_MS;
        return (float) ((double) t / (double) DAY_CYCLE_MS);
    }

    /** Night band used by ALL natural mob spawning: mid-dusk .. mid-dawn. */
    private boolean isNight() {
        float f = dayPhase();
        return f >= 0.50f && f < 0.95f;
    }

    private void drawHud(Graphics g, int w, int h) {
        drawHotbar(g, w, h);

        g.setColor(0xFFFFFF);
        g.drawString(fps + " fps", w - 2, 1, Graphics.RIGHT | Graphics.TOP);

        long now = System.currentTimeMillis();
        if (toast != null && now < toastUntil) {
            g.setColor(0x000000);
            g.drawString(toast, w / 2 + 1, 3, Graphics.HCENTER | Graphics.TOP);
            g.setColor(0xFFFFFF);
            g.drawString(toast, w / 2, 2, Graphics.HCENTER | Graphics.TOP);
        }
        if (hudLine != null && now < hudUntil) {
            g.setColor(0xFFFFFF);
            g.drawString(hudLine, w / 2, 14, Graphics.HCENTER | Graphics.TOP);
        }
    }

    /** Slot size of the bottom MC-style hotbar (16 px when the screen is wide). */
    private int hotbarCell(int w) {
        return Math.min(16, (w - 8) / HOTBAR_SLOTS - 1);
    }

    /** MC-style bottom hotbar: 9 bordered slots with icons, counts, selection. */
    private void drawHotbar(Graphics g, int w, int h) {
        int cell = hotbarCell(w);
        int total = HOTBAR_SLOTS * (cell + 1) + 1;
        int x0 = (w - total) / 2;
        int y0 = h - cell - 4;
        for (int i = 0; i < HOTBAR_SLOTS; ++i) {
            drawSlot(g, x0 + 1 + i * (cell + 1), y0, cell, hotbar[i],
                     i == hotbarIndex, 0xFFFFFF);
        }
    }

    /** One MC-style item slot: dark rim, gray well, icon, count, highlight. */
    private void drawSlot(Graphics g, int x, int y, int cell, int id,
                          boolean selected, int hiColor) {
        g.setColor(0x2A2A2A);
        g.fillRect(x - 1, y - 1, cell + 2, cell + 2);
        g.setColor(0x8B8B8B);
        g.fillRect(x, y, cell, cell);
        if (id != -1) {
            drawItemIcon(g, id, x, y, cell);
            int count = (id >= 0 && id < blockCounts.length) ? blockCounts[id] : 0;
            if ((id == ITEM_HAIZHUBALL || id == ITEM_FLYING_WING
                    || id == ITEM_ZISHU_PICKAXE) && count >= 1) {
                // Durability-type items show DURABILITY as the number.
                String ds = String.valueOf(id == ITEM_HAIZHUBALL
                        ? haizhuballDurability
                        : (id == ITEM_FLYING_WING ? wingDurability : pickDurability));
                g.setColor(0x000000);
                g.drawString(ds, x + cell + 1, y + cell + 1, Graphics.RIGHT | Graphics.BOTTOM);
                g.setColor(0xFFFFFF);
                g.drawString(ds, x + cell, y + cell, Graphics.RIGHT | Graphics.BOTTOM);
            } else if (count < 1) {
                // Hatch emptied slots instead of an alpha overlay -- some
                // MIDP implementations ignore alpha and would paint solid.
                g.setColor(0x5A5A5A);
                g.drawLine(x, y, x + cell - 1, y + cell - 1);
                g.drawLine(x + cell - 1, y, x, y + cell - 1);
            } else if (count > 1) {
                String cs = String.valueOf(count);
                g.setColor(0x000000);
                g.drawString(cs, x + cell + 1, y + cell + 1, Graphics.RIGHT | Graphics.BOTTOM);
                g.setColor(0xFFFFFF);
                g.drawString(cs, x + cell, y + cell, Graphics.RIGHT | Graphics.BOTTOM);
            }
        }
        if (selected) {
            g.setColor(hiColor);
            g.drawRect(x - 2, y - 2, cell + 3, cell + 3);
            g.drawRect(x - 1, y - 1, cell + 1, cell + 1);
        }
    }

    /** Draw a 16x16 item icon: the block's atlas tile or the bone sprite.
     *  Falls back to a colour swatch on narrow screens / missing images. */
    private void drawItemIcon(Graphics g, int id, int x, int y, int cell) {
        if (id == ITEM_BONE) {
            if (boneIcon != null && cell >= 16) {
                g.drawImage(boneIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0xDDDDDD);
                g.fillRect(x + 2, y + 2, cell - 4, cell - 4);
            }
            return;
        }
        if (id == ITEM_BONE_MEAL) {
            if (boneMealIcon != null && cell >= 16) {
                g.drawImage(boneMealIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0xE6E6E6);
                g.fillRect(x + 3, y + 4, cell - 6, cell - 8);
            }
            return;
        }
        if (id == ITEM_SLIMEBALL) {
            if (slimeballIcon != null && cell >= 16) {
                g.drawImage(slimeballIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0x77B558);
                g.fillArc(x + 2, y + 2, cell - 4, cell - 4, 0, 360);
            }
            return;
        }
        if (id == ITEM_GUNPOWDER) {
            if (gunpowderIcon != null && cell >= 16) {
                g.drawImage(gunpowderIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0x9A958A);
                g.fillArc(x + 3, y + 5, cell - 6, cell - 9, 0, 180);
            }
            return;
        }
        if (id == ITEM_HAIZHUBALL) {
            if (haizhuballIcon != null && cell >= 16) {
                g.drawImage(haizhuballIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0xF2A0B0);
                g.fillRect(x + 3, y + 3, cell - 6, cell - 6);
            }
            return;
        }
        if (id == ITEM_YOUIJI_FEATHER) {
            if (featherIcon != null && cell >= 16) {
                g.drawImage(featherIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0xE8A0B0);
                g.fillRect(x + cell / 2 - 1, y + 3, 3, cell - 6);
            }
            return;
        }
        if (id == ITEM_FLYING_WING) {
            if (wingIcon != null && cell >= 16) {
                g.drawImage(wingIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0xE8A0B0);
                g.fillRect(x + 3, y + 2, cell - 6, cell - 4);
            }
            return;
        }
        if (id == ITEM_ZISHU_TOOTH) {
            if (toothIcon != null && cell >= 16) {
                g.drawImage(toothIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0xF7D13A);
                g.fillRect(x + 4, y + 3, cell - 8, cell - 8);
            }
            return;
        }
        if (id == ITEM_ZISHU_PICKAXE) {
            if (pickIcon != null && cell >= 16) {
                g.drawImage(pickIcon, x, y, Graphics.LEFT | Graphics.TOP);
            } else {
                g.setColor(0x8B2E55);
                g.fillRect(x + 2, y + 3, cell - 4, 3);
                g.setColor(0xA07845);
                g.fillRect(x + cell / 2 - 1, y + 6, 3, cell - 8);
            }
            return;
        }
        Tile t = Tile.tiles[id];
        if (cell >= 16 && iconAtlas != null && t != null) {
            int tex = t.getTexture(1);
            int sx = (tex % 16) * 16;
            int sy = (tex / 16) * 16;
            int cx = g.getClipX(), cy = g.getClipY();
            int cw = g.getClipWidth(), ch = g.getClipHeight();
            g.clipRect(x, y, 16, 16);
            g.drawImage(iconAtlas, x - sx, y - sy, Graphics.LEFT | Graphics.TOP);
            g.setClip(cx, cy, cw, ch);
            return;
        }
        g.setColor(t != null ? blockColor(id) : 0xFFFFFF);
        g.fillRect(x + 2, y + 2, cell - 4, cell - 4);
    }

    /** Survival HUD: 10 hearts (2 HP each) above the hotbar's right end,
     *  air bubbles in a row above the hearts while underwater. */
    private void drawHealth(Graphics g, int w, int h) {
        if (player == null) return;
        int hp = player.health;
        int sz = 6;        // heart cell size
        int gap = 1;
        int rowW = 10 * (sz + gap);
        int cell = hotbarCell(w);
        int hbTotal = HOTBAR_SLOTS * (cell + 1) + 1;
        int x0 = (w - hbTotal) / 2 + hbTotal - rowW;   // over the hotbar's right end
        int y0 = h - cell - 4 - sz - 3;

        for (int i = 0; i < 10; ++i) {
            int hx = x0 + i * (sz + gap);
            int hpForHeart = hp - i * 2;
            // empty heart shadow
            g.setColor(0x401010);
            g.fillRect(hx, y0, sz, sz);
            if (hpForHeart >= 2) {
                g.setColor(0xFF2020);
                g.fillRect(hx, y0, sz, sz);
            } else if (hpForHeart == 1) {
                g.setColor(0xFF2020);
                g.fillRect(hx, y0, sz / 2, sz);
            }
            g.setColor(0x000000);
            g.drawRect(hx, y0, sz, sz);
        }

        // Air bubbles row above hearts while underwater.
        if (player.inWater && player.air < Player.MAX_AIR) {
            int bubbles = (player.air * 10 + Player.MAX_AIR - 1) / Player.MAX_AIR;
            int by0 = y0 - sz - 2;
            for (int i = 0; i < bubbles; ++i) {
                int bx = x0 + i * (sz + gap);
                g.setColor(0x40A0FF);
                g.fillRect(bx, by0, sz, sz);
                g.setColor(0x000000);
                g.drawRect(bx, by0, sz, sz);
            }
        }
    }

    /** Death overlay with an auto-respawn countdown. */
    private void drawDeath(Graphics g, int w, int h) {
        g.setColor(0x700000);
        for (int y = 0; y < h; y += 2) g.drawLine(0, y, w, y);
        g.setColor(0xFFFFFF);
        g.drawString("You died!", w / 2, h / 2 - 12, Graphics.HCENTER | Graphics.TOP);
        int left = (40 - player.deathTime + 19) / 20;
        if (left < 0) left = 0;
        g.drawString("Respawning...", w / 2, h / 2 + 2, Graphics.HCENTER | Graphics.TOP);
    }

    // ---- Shared inventory-overlay layout (draw + pointer hit-test). ----
    private int invCellW, invOX, invOY, invRows, invHX0, invHY;
    /** Scrolling grid: first visible row, visible rows, scrollbar x. */
    private int invScroll = 0, invVisRows = 1, invSBX = 0;
    /** Scrollbar tap direction set by the last inventoryIndexAt(). */
    private int invScrollTap = 0;
    /** Hotbar slot tapped by the last inventoryIndexAt() call, or -1. */
    private int invPointerHotbar = -1;

    private void layoutInventory() {
        int w = getWidth();
        int h = getHeight();
        invRows = (palette.length + INV_COLS - 1) / INV_COLS;
        invCellW = Math.min((w - 26) / INV_COLS, 16);
        // The grid never grows past what fits on screen: extra rows
        // SCROLL (scrollbar strip on the right edge of the grid).
        int fit = (h - 92) / invCellW;
        if (fit < 2) fit = 2;
        invVisRows = invRows < fit ? invRows : fit;
        if (invScroll > invRows - invVisRows) invScroll = invRows - invVisRows;
        if (invScroll < 0) invScroll = 0;
        int gridW = invCellW * INV_COLS;
        int gridH = invCellW * invVisRows;
        int hotH = invCellW + 12;
        invOX = (w - gridW) / 2 - 2;
        invOY = (h - gridH - hotH) / 2 - 8;
        invHY = invOY + gridH + 8;
        invHX0 = invOX + (gridW - (HOTBAR_SLOTS * (invCellW + 1) + 1)) / 2 + 1;
        invSBX = invOX + gridW + 2;
    }

    private void drawInventory(Graphics g, int w, int h) {
        layoutInventory();
        int cell = invCellW;
        int gridW = cell * INV_COLS;
        int gridH = cell * invVisRows;

        // MC-gray panel with a black rim (wide enough for the scrollbar).
        g.setColor(0x000000);
        g.fillRect(invOX - 8, invOY - 20, gridW + 24, gridH + cell + 36);
        g.setColor(0xC6C6C6);
        g.fillRect(invOX - 7, invOY - 19, gridW + 22, gridH + cell + 34);
        g.setColor(0xFFFFFF);
        g.drawRect(invOX - 7, invOY - 19, gridW + 22, gridH + cell + 34);
        g.setColor(0x404040);
        g.drawString("Inventory", w / 2, invOY - 17, Graphics.HCENTER | Graphics.TOP);

        // Item grid: only the VISIBLE (scrolled) window of palette entries.
        int lim = (invScroll + invVisRows) * INV_COLS;
        if (lim > palette.length) lim = palette.length;
        for (int i = invScroll * INV_COLS; i < lim; ++i) {
            int cx = invOX + (i % INV_COLS) * cell;
            int cy = invOY + ((i / INV_COLS) - invScroll) * cell;
            drawSlot(g, cx, cy, cell, palette[i], i == invCursor, 0xFFFFFF);
        }

        // The scrollbar strip: track + thumb, only when rows overflow.
        if (invRows > invVisRows) {
            g.setColor(0x4A4A4A);
            g.fillRect(invSBX, invOY, 3, gridH);
            int thumbH = gridH * invVisRows / invRows;
            if (thumbH < 6) thumbH = 6;
            int thumbY = invOY + (gridH - thumbH) * invScroll / (invRows - invVisRows);
            g.setColor(0xEEEEEE);
            g.fillRect(invSBX, thumbY, 3, thumbH);
            g.setColor(0x000000);
            g.drawRect(invSBX, thumbY, 3, thumbH);
        }

        // The hotbar row: shows where the picked item will land (yellow).
        for (int i = 0; i < HOTBAR_SLOTS; ++i) {
            drawSlot(g, invHX0 + 1 + i * (cell + 1), invHY, cell, hotbar[i],
                     i == hotbarIndex, 0xFFD800);
        }

        g.setColor(0x404040);
        g.drawString(itemNameAt(invCursor) + " x" + itemCountAt(invCursor),
                     w / 2, invHY + cell + 3, Graphics.HCENTER | Graphics.TOP);
    }

    private void drawMenu(Graphics g, int w, int h) {
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        g.drawString("Minecraft Classic v1.0", w / 2, 8, Graphics.HCENTER | Graphics.TOP);
        drawMenuLine(g, w / 2, 30, menuIndex == MENU_RESUME, "Resume game");
        drawMenuLine(g, w / 2, 44, menuIndex == MENU_SAVE, "Save world");
        drawMenuLine(g, w / 2, 58, menuIndex == MENU_LOAD, "Load world");
        drawMenuLine(g, w / 2, 72, menuIndex == MENU_NEW, "New world");
        drawMenuLine(g, w / 2, 86, menuIndex == MENU_HELP, "Controls help");
        drawMenuLine(g, w / 2, 100, menuIndex == MENU_MULTIPLAYER, "Multiplayer");
        g.drawString("2/8 nav, 5/FIRE select, softkey closes", w / 2, h - 10, Graphics.HCENTER | Graphics.BOTTOM);
    }

    private void drawMenuLine(Graphics g, int x, int y, boolean selected, String text) {
        if (selected) {
            g.setColor(0xFFFF00);
            g.fillRect(8, y - 1, getWidth() - 16, 12);
            g.setColor(0x000000);
            g.drawString(text, x, y, Graphics.HCENTER | Graphics.TOP);
        } else {
            g.setColor(0xFFFFFF);
            g.drawString(text, x, y, Graphics.HCENTER | Graphics.TOP);
        }
    }

    /** A rough representative colour per block for inventory swatches. */
    private int blockColor(int id) {
        switch (id) {
            case 1: return 0x7E7E7E;
            case 2: return 0x6CB143;
            case 3: return 0x9B6B3F;
            case 4: return 0x6E6E6E;
            case 5: return 0xB08A4F;
            case 7: return 0x4A4A4A;
            case 8: case 9: return 0x3A5BD6;
            case 10: case 11: return 0xE5631E;
            case 12: return 0xE0D8A0;
            case 13: return 0x9A938C;
            case 17: return 0x6E5634;
            case 18: return 0x3E7A28;
            case 20: return 0xC8E0F0;
            case 37: return 0xE8E000;
            case 38: return 0xD02020;
            case 44: case 43: case 50: return 0x8A8A8A;
            case 45: return 0x9A4B36;
            case 49: return 0x2A2540;
            case 60: return 0x9FD8F0;
        }
        if (id >= 21 && id <= 36) {
            int[] cloth = {0xCC4444,0xCC8844,0xCCCC44,0x88CC44,0x44CC44,0x44CC88,
                           0x44CCCC,0x4488CC,0x4444CC,0x6644CC,0x8844CC,0xCC44CC,
                           0xCC4488,0x222222,0x888888,0xEEEEEE};
            return cloth[id - 21];
        }
        return 0xAAAAAA;
    }

    private boolean isTeleportKey(int kc) {
        return kc == ' ';
    }

    private void teleportToVoidPlatform() {
        if (player == null || level == null) return;

        int depth = level.getDepth();
        int cy = depth / 2;
        if (cy < 8) cy = 8;
        if (cy > depth - 8) cy = depth - 8;

        int cx;
        int cz;
        if (level instanceof InfiniteLevel) {
            cx = 2048 + voidPlatformSequence * 48;
            cz = 2048;
        } else {
            cx = level.getWidth() / 2;
            cz = level.getHeight() / 2;
        }
        voidPlatformSequence++;

        int radius = 4;
        for (int x = cx - radius; x <= cx + radius; ++x) {
            for (int z = cz - radius; z <= cz + radius; ++z) {
                for (int y = 0; y < depth; ++y) {
                    level.setTile(x, y, z, 0);
                }
            }
        }
        level.setTile(cx, cy, cz, 3);
        player.teleportTo(cx + 0.5f, cy + 2.0f, cz + 0.5f);
        player.setRotation(0.0f, 0.0f);
        hitResult = RayCast.pick(level, player, 4.0f);
        toast = "Teleported to 1x1 void block";
        toastUntil = System.currentTimeMillis() + 1400;
    }

    private void addDrop(DroppedBlock drop) {
        if (drop == null) return;
        if (drops.size() > 48) drops.removeElementAt(0);
        drops.addElement(drop);
    }

    private void collectDrop(DroppedBlock drop) {
        if (drop == null) return;
        if (drop.blockId > 0) {
            int count = addBlockCount(drop.blockId, 1);
            toast = "Picked up: " + nameOf(drop.blockId) + " x" + count;
        } else {
            toast = "Picked up: " + (drop.name != null ? drop.name : nameOf(drop.blockId));
        }
        toastUntil = System.currentTimeMillis() + 900;
    }

    private byte[] readResource(String name) throws Exception {
        java.io.InputStream in = getClass().getResourceAsStream(name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ---- Input ----
    public void keyPressed(int kc) { handleKey(kc, true); }
    public void keyReleased(int kc) { handleKey(kc, false); }
    public void pointerPressed(int x, int y) {
        pointerDown = true;
        pointerDragged = false;
        pointerStartX = pointerLastX = x;
        pointerStartY = pointerLastY = y;

        if (connecting) return;

        if (showBoneDialog) {
            int b = boneDialogButtonAt(x, y);
            if (b == 1) {
                boneDialogYes = true;
                boneYesHeld = true;                          // hold starts here
                boneYesHoldStart = System.currentTimeMillis();
            } else if (b == 0) {
                boneDialogYes = false;
                boneYesHeld = false;
                boneYesHoldStart = 0L;
            }
            return;
        }

        if (showMenu) {
            int idx = menuIndexAt(x, y);
            if (idx >= 0) menuIndex = idx;
            return;
        }

        if (showInventory) {
            int idx = inventoryIndexAt(x, y);
            if (idx >= 0) invCursor = idx;
            else if (invPointerHotbar >= 0) hotbarIndex = invPointerHotbar;
            return;
        }

        if (isMenuPointerZone(x, y)) {
            toggleMenu();
            pointerDown = false;
            return;
        }

        if (isInventoryPointerZone(x, y)) {
            toggleInventory();
            pointerDown = false;
        }
    }

    public void pointerDragged(int x, int y) {
        if (!pointerDown || connecting || showBoneDialog || showMenu || showInventory || player == null) return;

        int dx = x - pointerLastX;
        int dy = y - pointerLastY;
        if (dx != 0 || dy != 0) {
            if (Math.abs(x - pointerStartX) > POINTER_TAP_SLOP ||
                Math.abs(y - pointerStartY) > POINTER_TAP_SLOP) {
                pointerDragged = true;
            }
            player.turn(dx * 2.0f, dy * 2.0f);
            hitResult = RayCast.pick(level, player, 4.0f);
        }
        pointerLastX = x;
        pointerLastY = y;
    }

    public void pointerReleased(int x, int y) {
        boolean wasDown = pointerDown;
        boolean wasTap = wasDown && !pointerDragged &&
            Math.abs(x - pointerStartX) <= POINTER_TAP_SLOP &&
            Math.abs(y - pointerStartY) <= POINTER_TAP_SLOP;

        pointerDown = false;
        pointerDragged = false;

        if (connecting || !wasDown) return;

        if (showBoneDialog) {
            int b = boneDialogButtonAt(x, y);
            if (b == 0) {
                craftBoneMeal(false);                        // No: just close
            } else if (b == 1) {
                // Short tap on Yes = one bone; a 5 s hold already converted
                // the whole stack in tick() and closed the dialog.
                long held = boneYesHeld ? System.currentTimeMillis() - boneYesHoldStart : 0L;
                boneYesHeld = false;
                boneYesHoldStart = 0L;
                if (held < HOLD_ALL_MS) craftBoneMeal(true);
            } else {
                boneYesHeld = false;
                boneYesHoldStart = 0L;
            }
            return;
        }

        if (showMenu) {
            int idx = menuIndexAt(x, y);
            if (idx >= 0) {
                menuIndex = idx;
                activateMenuItem();
            }
            return;
        }

        if (showInventory) {
            int idx = inventoryIndexAt(x, y);
            if (idx >= 0) {
                invCursor = idx;
                hotbar[hotbarIndex] = palette[idx];
                toggleInventory();
            } else if (invScrollTap != 0) {
                invScroll += invScrollTap;
                invEnsureVisible();
            } else if (invPointerHotbar >= 0) {
                hotbarIndex = invPointerHotbar;
            }
            return;
        }

        if (!wasTap) return;

        if (isMenuPointerZone(x, y)) {
            toggleMenu();
            return;
        }

        if (isInventoryPointerZone(x, y)) {
            toggleInventory();
            return;
        }

        if (x <= getWidth() / 3) {
            breakBlock();
        } else if (x >= (getWidth() * 2) / 3) {
            placeBlock();
        } else if (y >= (getHeight() * 3) / 4) {
            nextBlock();
        } else {
            breakBlock();
        }
    }

    private void handleKey(int kc, boolean down) {
        // The craft dialog is modal: softkeys close it (= No), 4/6 pick the
        // button, 5/FIRE/# presses the selected one.
        if (showBoneDialog) {
            int ga = getGameAction(kc);
            boolean confirmKey = kc == KEY_NUM5 || ga == FIRE || kc == KEY_POUND
                    || kc == 'y' || kc == 'Y';
            if (!down) {
                // Releasing a held Yes: a short press crafts ONE bone;
                // holding past 5 s already crafted the whole stack in tick().
                if (boneYesHeld && confirmKey) {
                    long held = System.currentTimeMillis() - boneYesHoldStart;
                    boneYesHeld = false;
                    boneYesHoldStart = 0L;
                    if (showBoneDialog && (boneDialogMode != 0 || held < HOLD_ALL_MS))
                        craftBoneMeal(true);
                }
                return;
            }
            if (kc == -6 || kc == -7 || kc == KEY_NUM0 || kc == 'n' || kc == 'N') {
                craftBoneMeal(false);
                return;
            }
            if (kc == KEY_NUM4 || ga == LEFT) {
                boneDialogYes = false;
                boneYesHeld = false;
                boneYesHoldStart = 0L;
                return;
            }
            if (kc == KEY_NUM6 || ga == RIGHT) {
                boneDialogYes = true;
                boneYesHeld = false;
                boneYesHoldStart = 0L;
                return;
            }
            if (confirmKey) {
                if (boneDialogYes) {
                    // Start the hold: the craft happens on release (short
                    // press = one bone) or at 5 s (whole stack).  Auto-repeat
                    // just re-taps the held key, so don't reset the timer.
                    if (!boneYesHeld) {
                        boneYesHeld = true;
                        boneYesHoldStart = System.currentTimeMillis();
                    }
                } else {
                    craftBoneMeal(false);
                }
            }
            return;
        }
        if (down && kc == -6) {
            toggleMenu();
            return;
        }
        if (down && kc == -7) {
            toggleInventory();
            return;
        }
        if (down && (kc == 'e' || kc == 'E')) {
            toggleInventory();
            return;
        }

        if (showMenu) {
            if (!down) return;
            if (kc == KEY_NUM2 || getGameAction(kc) == UP) {
                menuIndex = (menuIndex + MENU_COUNT - 1) % MENU_COUNT;
                return;
            }
            if (kc == KEY_NUM8 || getGameAction(kc) == DOWN) {
                menuIndex = (menuIndex + 1) % MENU_COUNT;
                return;
            }
            if (kc == KEY_NUM5 || getGameAction(kc) == FIRE || kc == KEY_NUM0) {
                activateMenuItem();
                return;
            }
            return;
        }

        if (showInventory) {
            if (!down) return;
            handleInventoryKey(kc);
            return;
        }

        // freej2me's Z key arrives as keycode -2.  With a bone selected it
        // is the dedicated craft key (opens the bone-meal dialog); any other
        // selection it falls through and stays walk-backward, like DOWN.
        if (kc == -2 && down && player != null && !player.isDead()
                && hotbar[hotbarIndex] == ITEM_BONE && blockCounts[ITEM_BONE] > 0) {
            showBoneDialog = true;
            boneDialogMode = 0;
            boneDialogYes = true;
            boneYesHeld = false;
            boneYesHoldStart = 0L;
            releaseControls();
            return;
        }

        // Z with TEETH selected: craft a Zishu Pickaxe (3 teeth).
        if (kc == -2 && down && player != null && !player.isDead()
                && hotbar[hotbarIndex] == ITEM_ZISHU_TOOTH
                && blockCounts[ITEM_ZISHU_TOOTH] > 0) {
            showBoneDialog = true;
            boneDialogMode = 2;
            boneDialogYes = true;
            boneYesHeld = false;
            boneYesHoldStart = 0L;
            releaseControls();
            return;
        }

        // Z with FEATHERS selected: craft a Flying Wing (5 feathers).
        if (kc == -2 && down && player != null && !player.isDead()
                && hotbar[hotbarIndex] == ITEM_YOUIJI_FEATHER
                && blockCounts[ITEM_YOUIJI_FEATHER] > 0) {
            showBoneDialog = true;
            boneDialogMode = 1;
            boneDialogYes = true;
            boneYesHeld = false;
            boneYesHoldStart = 0L;
            releaseControls();
            return;
        }

        if (down && isTeleportKey(kc)) {
            teleportToVoidPlatform();
            return;
        }

        if (kc == KEY_STAR) { if (down) breakBlock(); return; }
        if (kc == KEY_POUND) { if (down) placeBlock(); return; }
        if (kc == KEY_NUM0) { if (down) nextBlock(); return; }

        // Emulator keyboard extras: U/D look up/down (the tools that used
        // to live on 7/9 before 1-9 became hotbar selection).
        if (kc == 'u' || kc == 'U') { kLookUp = down; return; }
        if (kc == 'd' || kc == 'D') { kLookDown = down; return; }

        int ga = 0;
        try { ga = getGameAction(kc); } catch (Throwable t) {}

        // Number keys pick hotbar slots and flash the item name for one
        // second (name only, no count).  Platform reality: on handsets and
        // in freej2me the digits 2/4/6/8/5 ARE the dpad (they carry a
        // movement game action) -- those keep moving the player; freej2me
        // delivers 1/3/7/9 as GAME_A/B/C/D, which we map back to slots
        // 1/3/7/9.  Plain digits with no game action select directly.
        int slot = -1;
        if (kc >= KEY_NUM1 && kc <= KEY_NUM9 && ga == 0) {
            slot = kc - KEY_NUM1;
        } else if (ga == GAME_A) {
            slot = 0;
        } else if (ga == GAME_B) {
            slot = 2;
        } else if (ga == GAME_C) {
            slot = 6;
        } else if (ga == GAME_D) {
            slot = 8;
        }
        if (slot != -1) {
            if (down) {
                hotbarIndex = slot;
                int id = hotbar[hotbarIndex];
                if (id != -1) {
                    toast = nameOfItem(id);
                    toastUntil = System.currentTimeMillis() + 1000;
                }
            }
            return;
        }
        // Real-handset 5 is the dpad FIRE -- keep it as jump there.
        if (kc == KEY_NUM5 && ga == FIRE) { kJump = down; return; }

        if (ga == UP) kForward = down;
        else if (ga == DOWN) kBack = down;
        else if (ga == LEFT) kTurnLeft = down;
        else if (ga == RIGHT) kTurnRight = down;
        else if (ga == FIRE) { if (down) breakBlock(); }
    }

    private void handleInventoryKey(int kc) {
        int ga = getGameAction(kc);
        if (kc == KEY_NUM4 || ga == LEFT) { if (invCursor > 0) invCursor--; }
        else if (kc == KEY_NUM6 || ga == RIGHT) { if (invCursor < palette.length - 1) invCursor++; }
        else if (kc == KEY_NUM2 || ga == UP) { if (invCursor >= INV_COLS) invCursor -= INV_COLS; }
        else if (kc == KEY_NUM8 || ga == DOWN) {
            if (invCursor + INV_COLS < palette.length) invCursor += INV_COLS;
        } else if (kc == KEY_NUM5 || ga == FIRE) {
            hotbar[hotbarIndex] = palette[invCursor];
            toggleInventory();
        } else if (kc == KEY_NUM0) {
            toggleInventory();
        }
        invEnsureVisible();
    }

    /** Keep the cursor's row inside the visible (scrolled) window. */
    private void invEnsureVisible() {
        int row = invCursor / INV_COLS;
        if (row < invScroll) invScroll = row;
        else if (row >= invScroll + invVisRows) invScroll = row - invVisRows + 1;
        if (invScroll < 0) invScroll = 0;
        if (invScroll > invRows - invVisRows) invScroll = invRows - invVisRows;
    }

    private int inventoryIndexAt(int x, int y) {
        invPointerHotbar = -1;
        invScrollTap = 0;
        layoutInventory();
        int gridW = invCellW * INV_COLS;
        int gridH = invCellW * invVisRows;
        // Tapping the scrollbar scrolls one row towards the tap.
        if (invRows > invVisRows && x >= invSBX && x < invSBX + 5
                && y >= invOY && y < invOY + gridH) {
            int thumbH = gridH * invVisRows / invRows;
            if (thumbH < 6) thumbH = 6;
            int thumbY = invOY + (gridH - thumbH) * invScroll / (invRows - invVisRows);
            invScrollTap = y < thumbY + thumbH / 2 ? -1 : 1;
            return -1;
        }
        // Tapping the hotbar row just moves the target slot.
        if (x >= invHX0 && x < invHX0 + HOTBAR_SLOTS * (invCellW + 1) &&
            y >= invHY && y < invHY + invCellW) {
            int hb = (x - invHX0) / (invCellW + 1);
            if (hb >= 0 && hb < HOTBAR_SLOTS) invPointerHotbar = hb;
            return -1;
        }
        if (x < invOX || y < invOY || x >= invOX + gridW || y >= invOY + gridH) return -1;
        int col = (x - invOX) / invCellW;
        int row = (y - invOY) / invCellW + invScroll;
        int idx = row * INV_COLS + col;
        return idx < palette.length ? idx : -1;
    }

    private int menuIndexAt(int x, int y) {
        if (x < 8 || x >= getWidth() - 8) return -1;
        int[] ys = {30, 44, 58, 72, 86, 100};
        for (int i = 0; i < ys.length; ++i) {
            if (y >= ys[i] - 1 && y < ys[i] + 11) return i;
        }
        return -1;
    }

    private boolean isMenuPointerZone(int x, int y) {
        return x >= 0 && x < POINTER_CORNER_W && y >= 0 && y < POINTER_TOP_ZONE;
    }

    private boolean isInventoryPointerZone(int x, int y) {
        int w = getWidth();
        return x >= w - POINTER_CORNER_W && x < w && y >= 0 && y < POINTER_TOP_ZONE;
    }

    private void toggleInventory() {
        showInventory = !showInventory;
        if (showInventory) {
            showMenu = false;
            // Start the cursor on the palette entry the hotbar slot holds.
            int id = hotbar[hotbarIndex];
            invCursor = 0;
            for (int i = 0; i < palette.length; ++i) {
                if (palette[i] == id) { invCursor = i; break; }
            }
            releaseControls();
        }
    }

    private void toggleMenu() {
        showMenu = !showMenu;
        if (showMenu) {
            showInventory = false;
            menuIndex = MENU_RESUME;
            releaseControls();
        }
    }

    private void activateMenuItem() {
        if (menuIndex == MENU_RESUME) {
            showMenu = false;
        } else if (menuIndex == MENU_SAVE) {
            saveGame();
        } else if (menuIndex == MENU_LOAD) {
            loadGame();
        } else if (menuIndex == MENU_NEW) {
            newWorld();
        } else if (menuIndex == MENU_HELP) {
            hudLine = "Dpad moves; 1-9 pick hotbar item; * breaks; # places; drag looks";
            hudUntil = System.currentTimeMillis() + 4000;
        } else if (menuIndex == MENU_MULTIPLAYER) {
            showMenu = false;
            startJoin();
        }
    }


    // ================= Multiplayer =================

    private void startJoin() {
        joinStep = 0;
        promptNext();
    }

    private void promptNext() {
        Display d = Display.getDisplay(midlet);
        if (joinStep == 0) {
            joinBox = new TextBox("Server host", serverHost, 64, TextField.ANY);
        } else if (joinStep == 1) {
            joinBox = new TextBox("Server port", String.valueOf(serverPort), 6, TextField.NUMERIC);
        } else {
            joinBox = new TextBox("Username", userName, 16, TextField.ANY);
        }
        joinOk = new Command("OK", Command.OK, 1);
        joinCancel = new Command("Cancel", Command.CANCEL, 1);
        joinBox.addCommand(joinOk);
        joinBox.addCommand(joinCancel);
        joinBox.setCommandListener(this);
        d.setCurrent(joinBox);
    }

    public void commandAction(Command c, Displayable disp) {
        if (c == joinCancel) {
            Display.getDisplay(midlet).setCurrent(this);
            joinBox = null;
            return;
        }
        if (c == joinOk) {
            String v = joinBox.getString();
            if (joinStep == 0) {
                serverHost = v.trim();
                joinStep = 1;
                promptNext();
            } else if (joinStep == 1) {
                try { serverPort = Integer.parseInt(v.trim()); } catch (Throwable t) { serverPort = 25565; }
                joinStep = 2;
                promptNext();
            } else {
                userName = v.trim();
                if (userName.length() == 0) userName = "Steve";
                joinBox = null;
                Display.getDisplay(midlet).setCurrent(this);
                connectToServer();
            }
        }
    }

    private void connectToServer() {
        if (serverHost.length() == 0) {
            toast = "No host given";
            toastUntil = System.currentTimeMillis() + 1500;
            return;
        }
        if (net != null) net.disconnect();
        net = new com.mojang.rubydung.net.NetworkClient(serverHost, serverPort, userName);
        connecting = true;
        multiplayer = true;
        net.start();
    }

    private void tickNetwork() {
        if (net == null) return;
        int st = net.getState();
        if (st == com.mojang.rubydung.net.NetworkClient.ST_ERROR) {
            toast = "Net: " + net.getError();
            toastUntil = System.currentTimeMillis() + 3000;
            net = null;
            multiplayer = false;
            connecting = false;
            return;
        }
        if (connecting && net.isLevelReady()) {
            // swap to the server's world
            level = net.getLevel();
            player = new Player(level);
            renderer = new WorldRenderer(level, g3d, texture);
            renderer.setSlimeTexture(slimeTexture);
            renderer.setBoneTexture(boneTexture);
            renderer.setSlimeballTexture(slimeballTexture);
            renderer.setGunpowderTexture(gunpowderTexture);
            renderer.setFeatherTexture(featherTexture);
            renderer.setHaizhuballTexture(haizhuballTexture);
            hitResult = null;
            drops.removeAllElements();
            particles.removeAllElements();
            slimes.removeAllElements();
            redMice.removeAllElements();
            arrows.removeAllElements();
            connecting = false;
        }
        if (!connecting && st == com.mojang.rubydung.net.NetworkClient.ST_PLAYING) {
            net.sendPosition(player);
        }
    }

    private void drawConnecting(Graphics g, int w, int h) {
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        int pct = net != null ? net.getLoadPercent() : 0;
        String line = net == null ? "..." :
            (net.getState() == com.mojang.rubydung.net.NetworkClient.ST_PLAYING || net.isLevelReady()
                ? "Loading map " + pct + "%"
                : "Connecting to " + serverHost + "...");
        g.drawString(line, w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
        if (net != null && net.getServerName().length() > 0) {
            g.drawString(net.getServerName(), w / 2, h / 2 + 14, Graphics.HCENTER | Graphics.BASELINE);
        }
    }

    private void drawChat(Graphics g, int w, int h) {
        String[] lines = net.getChat();
        int y = h - 1;
        for (int i = lines.length - 1; i >= 0; --i) {
            String line = lines[i];
            if (line == null) continue;
            g.setColor(0xFFFFFF);
            g.drawString(line, 2, y, Graphics.LEFT | Graphics.TOP);
            y -= 10;
        }
    }

    private void nextBlock() {
        hotbarIndex = (hotbarIndex + 1) % HOTBAR_SLOTS;
        toast = nameOfItem(hotbar[hotbarIndex]);
        toastUntil = System.currentTimeMillis() + 1200;
    }

    /**
     * Using the Haizhuball: kills the SINGLE mob in the crosshair (any of
     * slime / red mouse / skeleton / firecracker -- never a HaiZhu) with
     * one shot, at one durability per kill.  No target = no cost.
     */
    private void useHaizhuball() {
        if (blockCounts[ITEM_HAIZHUBALL] < 1 || haizhuballDurability <= 0) {
            toast = "No Haizhuball";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        double yaw = player.yRot * Math.PI / 180.0;
        double pitch = player.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        float dx = (float) (-Math.sin(yaw) * cosPitch);
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (Math.cos(yaw) * cosPitch);
        float ex = player.x, ey = player.y, ez = player.z;

        Slime bs = null;
        RedMouse bm = null;
        Skeleton bk = null;
        Firecracker bf = null;
        Forward bfo = null;
        float bestT = 5.0f;
        for (int i = 0; i < slimes.size(); ++i) {
            Slime m = (Slime) slimes.elementAt(i);
            if (m == null || m.dying) continue;
            float t = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + Slime.HALF, m.z, 0.5f, bestT);
            if (t >= 0f && t < bestT) { bestT = t; bs = m; bm = null; bk = null; bf = null; bfo = null; }
        }
        for (int i = 0; i < redMice.size(); ++i) {
            RedMouse m = (RedMouse) redMice.elementAt(i);
            if (m == null || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.40f, m.z, 0.45f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 1.00f, m.z, 0.45f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { bestT = tm; bm = m; bs = null; bk = null; bf = null; bfo = null; }
        }
        for (int i = 0; i < skeletons.size(); ++i) {
            Skeleton m = (Skeleton) skeletons.elementAt(i);
            if (m == null || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.70f, m.z, 0.45f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 1.20f, m.z, 0.42f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { bestT = tm; bk = m; bs = null; bm = null; bf = null; bfo = null; }
        }
        for (int i = 0; i < firecrackers.size(); ++i) {
            Firecracker m = (Firecracker) firecrackers.elementAt(i);
            if (m == null || m.dying) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.50f, m.z, 0.42f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 1.08f, m.z, 0.40f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { bestT = tm; bf = m; bs = null; bm = null; bk = null; bfo = null; }
        }
        for (int i = 0; i < forwards.size(); ++i) {
            Forward m = (Forward) forwards.elementAt(i);
            if (m == null || m.dying || m.crawling) continue;
            float t0 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 0.90f, m.z, 0.50f, bestT);
            float t1 = raySphereT(ex, ey, ez, dx, dy, dz, m.x, m.y + 1.75f, m.z, 0.42f, bestT);
            float tm = t0 < 0f ? t1 : (t1 < 0f ? t0 : Math.min(t0, t1));
            if (tm >= 0f && tm < bestT) { bestT = tm; bfo = m; bs = null; bm = null; bk = null; bf = null; }
        }
        if (bs == null && bm == null && bk == null && bf == null && bfo == null) {
            toast = "No target";
            toastUntil = System.currentTimeMillis() + 700;
            return;
        }
        String name;
        if (bs != null) {
            bs.hurt(999, player);
            name = "Slime";
        } else if (bm != null) {
            bm.hurt(999, player);
            name = "Red Mouse";
        } else if (bk != null) {
            bk.hurt(999, player);
            name = "Skeleton";
        } else if (bf != null) {
            bf.hurt(999, player);
            name = "Firecracker";
        } else {
            bfo.hurt(999, player);
            name = "Forward";
        }
        --haizhuballDurability;
        if (haizhuballDurability <= 0) {
            addBlockCount(ITEM_HAIZHUBALL, -1);
            haizhuballDurability = 0;
            toast = "Haizhuball broke! (" + name + " killed)";
        } else {
            toast = name + " killed! Haizhuball: " + haizhuballDurability + " left";
        }
        toastUntil = System.currentTimeMillis() + 1000;
    }

    /** Item name for a hotbar slot id (tile id, ITEM_BONE, or -1). */
    private String nameOfItem(int id) {
        if (id == ITEM_BONE) return "bone";
        if (id == ITEM_BONE_MEAL) return "bone meal";
        if (id == ITEM_SLIMEBALL) return "slimeball";
        if (id == ITEM_GUNPOWDER) return "gunpowder";
        if (id == ITEM_HAIZHUBALL) return "Haizhuball";
        if (id == -1) return "-";
        return nameOf(id);
    }

    // ---- Yes/No craft dialog: 1 bone -> 3 bone meal. ----

    private void layoutBoneDialog(int w, int h) {
        boneDlgW = Math.min(w - 16, 150);
        boneDlgH = 74;
        boneDlgX = (w - boneDlgW) / 2;
        boneDlgY = (h - boneDlgH) / 2;
        boneBtnW = Math.min(48, (boneDlgW - 30) / 2);
        boneBtnH = 18;
        boneBtnY = boneDlgY + boneDlgH - 26;
        int gap = 10;
        int tw = boneBtnW * 2 + gap;
        boneNoX = boneDlgX + (boneDlgW - tw) / 2;
        boneYesX = boneNoX + boneBtnW + gap;
    }

    private void drawBoneDialog(Graphics g, int w, int h) {
        layoutBoneDialog(w, h);
        g.setColor(0x000000);
        g.fillRect(boneDlgX - 2, boneDlgY - 2, boneDlgW + 4, boneDlgH + 4);
        g.setColor(0xC6C6C6);
        g.fillRect(boneDlgX, boneDlgY, boneDlgW, boneDlgH);
        g.setColor(0xFFFFFF);
        g.drawRect(boneDlgX, boneDlgY, boneDlgW, boneDlgH);
        g.setColor(0x404040);
        if (boneDialogMode == 1) {
            g.drawString("Craft a Flying Wing", w / 2, boneDlgY + 6, Graphics.HCENTER | Graphics.TOP);
            g.drawString("from 5 feathers?", w / 2, boneDlgY + 18, Graphics.HCENTER | Graphics.TOP);
            g.setColor(0x707070);
            g.drawString("hold 5 to fly!", w / 2, boneDlgY + 32, Graphics.HCENTER | Graphics.TOP);
        } else if (boneDialogMode == 2) {
            g.drawString("Craft a Zishu Pickaxe", w / 2, boneDlgY + 6, Graphics.HCENTER | Graphics.TOP);
            g.drawString("from 3 teeth?", w / 2, boneDlgY + 18, Graphics.HCENTER | Graphics.TOP);
            g.setColor(0x707070);
            g.drawString("select + dig = 3x3!", w / 2, boneDlgY + 32, Graphics.HCENTER | Graphics.TOP);
        } else {
            g.drawString("Craft 3 bone meal", w / 2, boneDlgY + 6, Graphics.HCENTER | Graphics.TOP);
            g.drawString("from 1 bone?", w / 2, boneDlgY + 18, Graphics.HCENTER | Graphics.TOP);
            g.setColor(0x707070);
            g.drawString("hold Yes 5s = all bones", w / 2, boneDlgY + 32, Graphics.HCENTER | Graphics.TOP);
        }
        drawDialogButton(g, boneNoX, "No", !boneDialogYes);
        drawDialogButton(g, boneYesX, "Yes", boneDialogYes);
        // Hold-to-convert-all progress crawling across the Yes button.
        if (boneDialogMode == 0 && boneYesHeld && boneDialogYes) {
            long el = System.currentTimeMillis() - boneYesHoldStart;
            int frac = (int) (el * (boneBtnW - 2) / HOLD_ALL_MS);
            if (frac > boneBtnW - 2) frac = boneBtnW - 2;
            if (frac < 0) frac = 0;
            g.setColor(0x5FAF5F);
            g.fillRect(boneYesX + 1, boneBtnY + boneBtnH - 4, frac, 3);
        }
    }

    private void drawDialogButton(Graphics g, int x, String label, boolean sel) {
        g.setColor(0x2A2A2A);
        g.fillRect(x - 1, boneBtnY - 1, boneBtnW + 2, boneBtnH + 2);
        g.setColor(0x8B8B8B);
        g.fillRect(x, boneBtnY, boneBtnW, boneBtnH);
        g.setColor(sel ? 0xFFFFFF : 0x404040);
        g.drawRect(x, boneBtnY, boneBtnW, boneBtnH);
        if (sel) g.drawRect(x - 1, boneBtnY - 1, boneBtnW + 2, boneBtnH + 2);
        g.setColor(0xFFFFFF);
        g.drawString(label, x + boneBtnW / 2, boneBtnY + boneBtnH / 2,
                     Graphics.HCENTER | Graphics.BASELINE);
    }

    /** @return 0 = No button, 1 = Yes button, -1 = outside the dialog. */
    private int boneDialogButtonAt(int x, int y) {
        layoutBoneDialog(getWidth(), getHeight());
        if (y >= boneBtnY && y < boneBtnY + boneBtnH) {
            if (x >= boneNoX && x < boneNoX + boneBtnW) return 0;
            if (x >= boneYesX && x < boneYesX + boneBtnW) return 1;
        }
        return -1;
    }

    /** Yes: consume 1 bone, gain 3 bone meal.  No: just close. */
    private void craftBoneMeal(boolean yes) {
        if (boneDialogMode == 1) { craftWing(yes); return; }
        if (boneDialogMode == 2) { craftPickaxe(yes); return; }
        showBoneDialog = false;
        boneYesHeld = false;
        boneYesHoldStart = 0L;
        if (!yes) return;
        if (blockCounts[ITEM_BONE] < 1) {
            toast = "No bone";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        addBlockCount(ITEM_BONE, -1);
        int n = addBlockCount(ITEM_BONE_MEAL, 3);
        toast = "bone meal x" + n;
        toastUntil = System.currentTimeMillis() + 900;
    }

    /** Pickaxe dialog: Yes crafts a Zishu Pickaxe from 3 teeth (a fresh
     *  one replaces a worn pickaxe at full durability).  No: just close. */
    private void craftPickaxe(boolean yes) {
        showBoneDialog = false;
        boneYesHeld = false;
        boneYesHoldStart = 0L;
        if (!yes) return;
        if (blockCounts[ITEM_ZISHU_TOOTH] < 3) {
            toast = "Need 3 teeth";
            toastUntil = System.currentTimeMillis() + 900;
            return;
        }
        addBlockCount(ITEM_ZISHU_TOOTH, -3);
        if (blockCounts[ITEM_ZISHU_PICKAXE] < 1) addBlockCount(ITEM_ZISHU_PICKAXE, 1);
        pickDurability = PICK_MAX_DUR;
        toast = "Zishu Pickaxe! Digs 3x3";
        toastUntil = System.currentTimeMillis() + 1200;
    }

    /** Wing dialog: Yes crafts a Flying Wing from 5 feathers (a fresh
     *  wing replaces a worn one at full durability).  No: just close. */
    private void craftWing(boolean yes) {
        showBoneDialog = false;
        boneYesHeld = false;
        boneYesHoldStart = 0L;
        if (!yes) return;
        if (blockCounts[ITEM_YOUIJI_FEATHER] < 5) {
            toast = "Need 5 feathers";
            toastUntil = System.currentTimeMillis() + 900;
            return;
        }
        addBlockCount(ITEM_YOUIJI_FEATHER, -5);
        if (blockCounts[ITEM_FLYING_WING] < 1) addBlockCount(ITEM_FLYING_WING, 1);
        wingDurability = WING_MAX_DUR;
        toast = "Flying Wing! Hold 5 to fly";
        toastUntil = System.currentTimeMillis() + 1200;
    }

    /** Held Yes for 5 s: the WHOLE bone stack becomes bone meal (still 1:3). */
    private void craftAllBoneMeal() {
        boneYesHeld = false;
        boneYesHoldStart = 0L;
        showBoneDialog = false;
        int bones = blockCounts[ITEM_BONE];
        if (bones < 1) {
            toast = "No bone";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        addBlockCount(ITEM_BONE, -bones);
        int n = addBlockCount(ITEM_BONE_MEAL, bones * 3);
        toast = "all " + bones + " bones -> bone meal x" + n;
        toastUntil = System.currentTimeMillis() + 1400;
    }

    private void breakBlock() {
        if (attackRedMouse()) return;
        if (attackSkeleton()) return;
        if (attackFirecracker()) return;
        if (attackHaiZhu()) return;
        if (attackYouJi()) return;
        if (attackZiShu()) return;
        if (attackForward()) return;
        if (attackBowDrop()) return;
        if (attackSlime()) return;
        HitResult hit = refreshHitResult();
        if (hit == null) return;
        int id = level.getTile(hit.x, hit.y, hit.z);
        if (id == 0) return;
        breakOneBlock(hit.x, hit.y, hit.z);

        // ZiShu's Pickaxe: one dig clears a 3x3 SHEET in the plane of the
        // broken face (hammer style) and costs one durability.
        if (hotbar[hotbarIndex] == ITEM_ZISHU_PICKAXE
                && blockCounts[ITEM_ZISHU_PICKAXE] > 0 && pickDurability > 0) {
            for (int a = -1; a <= 1; ++a) {
                for (int b = -1; b <= 1; ++b) {
                    if (a == 0 && b == 0) continue;
                    int nx = hit.x, ny = hit.y, nz = hit.z;
                    if (hit.f <= 1) { nx += a; nz += b; }        // +-Y face: XZ sheet
                    else if (hit.f <= 3) { nx += a; ny += b; }   // +-Z face: XY sheet
                    else { ny += a; nz += b; }                   // +-X face: YZ sheet
                    if (level.getTile(nx, ny, nz) != 0) breakOneBlock(nx, ny, nz);
                }
            }
            --pickDurability;
            if (pickDurability <= 0) {
                pickDurability = 0;
                addBlockCount(ITEM_ZISHU_PICKAXE, -1);
                toast = "The pickaxe broke!";
            } else {
                toast = "3x3! Pickaxe: " + pickDurability + " left";
            }
            toastUntil = System.currentTimeMillis() + 1000;
        }
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    /** Remove one block with particles, drop and network sync. */
    private void breakOneBlock(int x, int y, int z) {
        int id = level.getTile(x, y, z);
        if (id == 0) return;
        level.setTile(x, y, z, 0);
        if (net != null) net.sendSetBlock(x, y, z, id, false);
        spawnBreakParticles(x, y, z, id);
        spawnDroppedBlock(x, y, z, id);
        toast = "Removed: " + nameOf(id);
        toastUntil = System.currentTimeMillis() + 1000;
    }

    private void spawnBreakParticles(int bx, int by, int bz, int id) {
        if (particles.size() > 80) return;
        Tile t = Tile.tiles[id];
        int tex = (t != null) ? t.getTexture(1) : 0;
        int n = 20;
        for (int i = 0; i < n; ++i) {
            float px = bx + rng.nextFloat();
            float py = by + rng.nextFloat();
            float pz = bz + rng.nextFloat();
            particles.addElement(new Particle(level, px, py, pz, 0f, 0f, 0f, tex));
        }
    }

    private void spawnDroppedBlock(int bx, int by, int bz, int brokenId) {
        int dropId = getDropId(brokenId);
        if (dropId == 0) return;
        addDrop(new DroppedBlock(level, bx + 0.5f, by + 0.35f, bz + 0.5f, dropId));
    }

    private int getDropId(int brokenId) {
        switch (brokenId) {
            case 1: return 4;
            case 2: return 3;
            case 7: return 7;    // bedrock is harvestable (drops itself)
            case 8: case 9: return 0;
            case 10: case 11: return 0;
            case 18: return 0;   // leaves drop NOTHING (user spec)
            case 43: return 44;
            case 51: return 51; // rope is harvestable (drops itself)
            case 54: return 0;
        }
        Tile t = Tile.tiles[brokenId];
        return (t != null && t.isRenderable()) ? brokenId : 0;
    }

    private void placeBlock() {
        // "Use item" needs no aim target: the empty-slot / bone / bone meal
        // checks run BEFORE the ray pick, so the craft dialog opens even
        // while staring at the sky (aiming is only needed to place blocks).
        int block = hotbar[hotbarIndex];
        if (block == -1) {
            toast = "Empty hotbar slot";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        if (block == ITEM_BONE) {
            // "Use" on a bone offers the bone-meal craft dialog.
            if (blockCounts[ITEM_BONE] < 1) {
                toast = "No bone";
                toastUntil = System.currentTimeMillis() + 800;
            } else {
                showBoneDialog = true;
                boneDialogYes = true;
                releaseControls();
            }
            return;
        }
        if (block == ITEM_BONE_MEAL) {
            toast = "bone meal can't be placed";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        if (block == ITEM_HAIZHUBALL) {
            useHaizhuball();
            return;
        }
        if (block == ITEM_GUNPOWDER) {
            toast = "gunpowder can't be placed";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        if (block == ITEM_SLIMEBALL) {
            toast = "slimeball can't be placed";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        if (block == ITEM_YOUIJI_FEATHER) {
            toast = "feather can't be placed";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        if (block == ITEM_FLYING_WING) {
            toast = "select it + hold 5 to fly!";
            toastUntil = System.currentTimeMillis() + 1000;
            return;
        }
        if (block == ITEM_ZISHU_TOOTH) {
            toast = "tooth can't be placed";
            toastUntil = System.currentTimeMillis() + 800;
            return;
        }
        if (block == ITEM_ZISHU_PICKAXE) {
            toast = "select it + dig: 3x3!";
            toastUntil = System.currentTimeMillis() + 1000;
            return;
        }

        HitResult hit = refreshHitResult();
        if (hit == null) return;
        int x = hit.x, y = hit.y, z = hit.z;
        switch (hit.f) {
            case 0: --y; break;
            case 1: ++y; break;
            case 2: --z; break;
            case 3: ++z; break;
            case 4: --x; break;
            case 5: ++x; break;
        }
        if (level.getTile(x, y, z) == 0) {
            if (!hasPlaceableCount(block)) {
                toast = "Out of: " + nameOf(block);
                toastUntil = System.currentTimeMillis() + 1000;
                return;
            }
            level.setTile(x, y, z, block);
            addBlockCount(block, -1);
            if (net != null) net.sendSetBlock(x, y, z, block, true);
            if (block == DIRT_ID || isCapStone(block)) trySummonHaiZhu(x, y, z);
            if (block == 4 || isOreCap(block)) trySummonYouJi(x, y, z);
            if (block == 14 || block == 15) trySummonZiShu(x, y, z);
            toast = "Placed: " + nameOf(block) + " x" + blockCounts[block];
            toastUntil = System.currentTimeMillis() + 1000;
            hitResult = RayCast.pick(level, player, 4.0f);
        }
    }

    private HitResult refreshHitResult() {
        hitResult = RayCast.pick(level, player, 4.0f);
        return hitResult;
    }

    private void newWorld() {
        level = new InfiniteLevel(64, (int) System.currentTimeMillis());
        dayCycleStart = System.currentTimeMillis();   // every world starts at dawn
        player = new Player(level);
        renderer = new WorldRenderer(level, g3d, texture);
        clearBlockCounts();
        giveStarterOre();   // +1 random ore for the YouJi ritual
        particles.removeAllElements();
        drops.removeAllElements();
        slimes.removeAllElements();
        redMice.removeAllElements();
        arrows.removeAllElements();
        bows.removeAllElements();
        skeletons.removeAllElements();
        firecrackers.removeAllElements();
        bones.removeAllElements();
        slimeBalls.removeAllElements();
        gunpowders.removeAllElements();
        feathers.removeAllElements();
        teeth.removeAllElements();
        haiZhus.removeAllElements();
        haizhuballs.removeAllElements();
        youJis.removeAllElements();
        ziShus.removeAllElements();
        forwards.removeAllElements();
        skeletonSpawnTimer = 20 * 15;
        firecrackerSpawnTimer = 20 * 15;
        redMouseSpawnTimer = 20 * 15;
        slimeSpawnTimer = 20 * 10;
        showMenu = false;
        toast = "New world";
        toastUntil = System.currentTimeMillis() + 1000;
    }

    private void saveGame() {
        if (level instanceof InfiniteLevel) {
            toast = "Infinite world: save disabled";
            toastUntil = System.currentTimeMillis() + 1200;
            if (player != null) savePlayerState();
            showMenu = false;
            return;
        }
        if (level != null) level.save();
        if (player != null) savePlayerState();
        toast = "World saved";
        toastUntil = System.currentTimeMillis() + 1200;
    }

    private void loadGame() {
        if (level instanceof InfiniteLevel) {
            Level classic = new Level(128, 128, 64);
            if (classic.load()) {
                level = classic;
                player = new Player(level);
                loadPlayerState();
                renderer = new WorldRenderer(level, g3d, texture);
                renderer.setSlimeTexture(slimeTexture);
                renderer.setBoneTexture(boneTexture);
                renderer.setSlimeballTexture(slimeballTexture);
                renderer.setGunpowderTexture(gunpowderTexture);
            renderer.setFeatherTexture(featherTexture);
                renderer.setHaizhuballTexture(haizhuballTexture);
            renderer.setHaizhuballTexture(haizhuballTexture);
                renderer.setGunpowderTexture(gunpowderTexture);
            renderer.setFeatherTexture(featherTexture);
                renderer.setHaizhuballTexture(haizhuballTexture);
            renderer.setHaizhuballTexture(haizhuballTexture);
                hitResult = null;
                particles.removeAllElements();
                drops.removeAllElements();
                slimes.removeAllElements();
                redMice.removeAllElements();
                arrows.removeAllElements();
                bows.removeAllElements();
                skeletons.removeAllElements();
                firecrackers.removeAllElements();
                bones.removeAllElements();
                slimeBalls.removeAllElements();
            gunpowders.removeAllElements();
        feathers.removeAllElements();
            haiZhus.removeAllElements();
            haizhuballs.removeAllElements();
            youJis.removeAllElements();
        ziShus.removeAllElements();
        haizhuballs.removeAllElements();
                gunpowders.removeAllElements();
        feathers.removeAllElements();
            haiZhus.removeAllElements();
            haizhuballs.removeAllElements();
            youJis.removeAllElements();
        ziShus.removeAllElements();
        haizhuballs.removeAllElements();
                haiZhus.removeAllElements();
            haizhuballs.removeAllElements();
            youJis.removeAllElements();
        ziShus.removeAllElements();
                haizhuballs.removeAllElements();
        haizhuballs.removeAllElements();
        gunpowders.removeAllElements();
        feathers.removeAllElements();
                skeletonSpawnTimer = 20 * 15;
                firecrackerSpawnTimer = 20 * 15;
                redMouseSpawnTimer = 20 * 15;
                slimeSpawnTimer = 20 * 10;
                toast = "World loaded";
            } else {
                toast = "No saved world";
            }
            toastUntil = System.currentTimeMillis() + 1200;
            showMenu = false;
            return;
        }
        if (level != null && level.load()) {
            loadPlayerState();
            renderer = new WorldRenderer(level, g3d, texture);
            renderer.setSlimeTexture(slimeTexture);
            renderer.setBoneTexture(boneTexture);
            renderer.setSlimeballTexture(slimeballTexture);
            renderer.setGunpowderTexture(gunpowderTexture);
            renderer.setFeatherTexture(featherTexture);
            renderer.setHaizhuballTexture(haizhuballTexture);
            hitResult = null;
            particles.removeAllElements();
            drops.removeAllElements();
            slimes.removeAllElements();
            redMice.removeAllElements();
            arrows.removeAllElements();
            bows.removeAllElements();
            skeletons.removeAllElements();
            firecrackers.removeAllElements();
            bones.removeAllElements();
            slimeBalls.removeAllElements();
            gunpowders.removeAllElements();
        feathers.removeAllElements();
            haiZhus.removeAllElements();
            haizhuballs.removeAllElements();
            youJis.removeAllElements();
        ziShus.removeAllElements();
        haizhuballs.removeAllElements();
        gunpowders.removeAllElements();
        feathers.removeAllElements();
            skeletonSpawnTimer = 20 * 15;
            firecrackerSpawnTimer = 20 * 15;
            redMouseSpawnTimer = 20 * 15;
            slimeSpawnTimer = 20 * 10;
            toast = "World loaded";
        } else {
            toast = "No saved world";
        }
        toastUntil = System.currentTimeMillis() + 1200;
        showMenu = false;
    }

    private void loadPlayerState() {
        try {
            javax.microedition.rms.RecordStore rs = javax.microedition.rms.RecordStore.openRecordStore("mc_classic_player", false);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
                player.x = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.y = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.z = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.yRot = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.xRot = in.readInt() / (float) PLAYER_SAVE_SCALE;
                try {
                    int hp = in.readInt();
                    int air = in.readInt();
                    int deathCount = 0;
                    try { deathCount = in.readInt(); } catch (Throwable ignore2) {}
                    if (hp < 1) hp = Player.MAX_HEALTH;
                    if (hp > Player.MAX_HEALTH) hp = Player.MAX_HEALTH;
                    if (air < 0) air = 0;
                    if (air > Player.MAX_AIR) air = Player.MAX_AIR;
                    player.health = hp;
                    player.air = air;
                    if (deathCount < 0) deathCount = 0;
                    player.deathCount = deathCount;
                    try {
                        int countLen = in.readInt();
                        clearBlockCounts();
                        if (countLen > blockCounts.length) countLen = blockCounts.length;
                        if (countLen < 0) countLen = 0;
                        for (int i = 0; i < countLen; ++i) {
                            int count = in.readInt();
                            blockCounts[i] = count < 0 ? 0 : count;
                        }
                        try {
                            int dur = in.readInt();   // new in this version
                            haizhuballDurability = dur < 0 ? 0
                                    : (dur > HAIZHUBALL_MAX_DUR ? HAIZHUBALL_MAX_DUR : dur);
                        } catch (Throwable ignore6) {
                            haizhuballDurability =
                                    blockCounts[ITEM_HAIZHUBALL] > 0 ? HAIZHUBALL_MAX_DUR : 0;
                        }
                        try {
                            int wdur = in.readInt();   // new in this version
                            wingDurability = wdur < 0 ? 0
                                    : (wdur > WING_MAX_DUR ? WING_MAX_DUR : wdur);
                        } catch (Throwable ignore7) {
                            wingDurability =
                                    blockCounts[ITEM_FLYING_WING] > 0 ? WING_MAX_DUR : 0;
                        }
                        try {
                            int pdur = in.readInt();   // new in this version
                            pickDurability = pdur < 0 ? 0
                                    : (pdur > PICK_MAX_DUR ? PICK_MAX_DUR : pdur);
                        } catch (Throwable ignore8) {
                            pickDurability =
                                    blockCounts[ITEM_ZISHU_PICKAXE] > 0 ? PICK_MAX_DUR : 0;
                        }
                    } catch (Throwable ignore5) {
                        clearBlockCounts();
                    }
                } catch (Throwable ignore) {}
            }
            rs.closeRecordStore();
        } catch (Throwable t) {}
    }

    private void savePlayerState() {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
            out.writeInt((int) (player.x * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.y * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.z * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.yRot * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.xRot * PLAYER_SAVE_SCALE));
            out.writeInt(player.health);
            out.writeInt(player.air);
            out.writeInt(player.deathCount);
            out.writeInt(blockCounts.length);
            for (int i = 0; i < blockCounts.length; ++i) out.writeInt(blockCounts[i]);
            out.writeInt(haizhuballDurability);
            out.writeInt(wingDurability);
            out.writeInt(pickDurability);
            out.close();
            byte[] data = baos.toByteArray();
            try { javax.microedition.rms.RecordStore.deleteRecordStore("mc_classic_player"); } catch (Throwable t) {}
            javax.microedition.rms.RecordStore rs = javax.microedition.rms.RecordStore.openRecordStore("mc_classic_player", true);
            rs.addRecord(data, 0, data.length);
            rs.closeRecordStore();
        } catch (Throwable t) {}
    }

    private void releaseControls() {
        kForward = kBack = kLeft = kRight = kJump = kReset = false;
        kTurnLeft = kTurnRight = kLookUp = kLookDown = false;
        turnYaw = 0;
        turnPitch = 0;
        pointerDown = false;
        pointerDragged = false;
    }

    private void handleRuntimeError(String where, Throwable t) {
        releaseControls();
        if (t == null) return;
        t.printStackTrace();
        long now = System.currentTimeMillis();
        String name = t.getClass().getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) name = name.substring(dot + 1);
        if (where == null) where = "game";
        if (name.equals(lastRuntimeError) && now - lastRuntimeErrorAt < 1500L) {
            runtimeErrorCount++;
        } else {
            lastRuntimeError = name;
            lastRuntimeErrorAt = now;
            runtimeErrorCount = 1;
        }
        toast = "Recovered from " + where + " " + name;
        toastUntil = now + 1600L;
        if (runtimeErrorCount >= 3) {
            hudLine = "Runtime errors kept happening; state was reset";
            hudUntil = now + 2500L;
            hitResult = null;
            showMenu = false;
            showInventory = false;
            pointerDown = false;
            pointerDragged = false;
            runtimeErrorCount = 0;
        }
    }

    private String nameOf(int id) {
        Tile t = Tile.tiles[id];
        return t == null ? "?" : t.name;
    }

}
