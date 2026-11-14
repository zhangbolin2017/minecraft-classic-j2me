package com.mojang.rubydung;

public class Timer {
    private static final long MS_PER_SECOND = 1000L;
    private float ticksPerSecond;
    private long lastTime;
    public int ticks;
    public float a;
    public float timeScale = 1.0f;
    public float fps = 0.0f;
    public float passedTime = 0.0f;

    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
        this.lastTime = System.currentTimeMillis();
    }

    public void advanceTime() {
        long now = System.currentTimeMillis();
        long passedMs = now - lastTime;
        lastTime = now;
        if (passedMs < 0L) passedMs = 0L;
        if (passedMs > 1000L) passedMs = 1000L;
        if (passedMs > 0L) fps = (float) (1000L / passedMs);
        passedTime += (float) passedMs * timeScale * ticksPerSecond / 1000.0f;
        ticks = (int) passedTime;
        // Cap hard: after a slow frame, catch up at most 5 ticks instead of
        // running a burst of ticks that would compound the stutter.
        if (ticks > 5) ticks = 5;
        passedTime -= (float) ticks;
        // After a capped catch-up the leftover fraction can be >= 1, and an
        // interpolation factor >= 1 renders the camera PAST the entity -- a
        // jump right after a stutter then looked like a teleport several
        // blocks up and back.  Drop the stale backlog: a stays in [0, 1).
        if (passedTime >= 1.0f) passedTime = 0.0f;
        a = passedTime;
    }
}
