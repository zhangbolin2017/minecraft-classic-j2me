package com.mojang.rubydung;

import com.mojang.rubydung.level.ILevel;

/**
 * Simple DDA-style voxel raycast used to pick the block the player is
 * looking at, replacing the OpenGL selection-buffer picking of the
 * original RubyDung (which has no equivalent on Mascot Capsule).
 */
public final class RayCast {
    private RayCast() {}

    public static HitResult pick(ILevel level, Player p, float reach) {
        double yaw = p.yRot * Math.PI / 180.0;
        double pitch = p.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        double dx = -Math.sin(yaw) * cosPitch;
        double dy = -Math.sin(pitch);
        double dz = Math.cos(yaw) * cosPitch;

        double ox = p.x;
        double oy = p.y;
        double oz = p.z;

        int steps = (int) (reach / 0.05f);
        int prevX = (int) Math.floor(ox);
        int prevY = (int) Math.floor(oy);
        int prevZ = (int) Math.floor(oz);

        for (int i = 0; i <= steps; ++i) {
            double t = i * 0.05;
            int bx = (int) Math.floor(ox + dx * t);
            int by = (int) Math.floor(oy + dy * t);
            int bz = (int) Math.floor(oz + dz * t);
            if (level.isSolidTile(bx, by, bz)) {
                int face = faceFromEntry(bx, by, bz, prevX, prevY, prevZ);
                return new HitResult(bx, by, bz, 0, face);
            }
            prevX = bx; prevY = by; prevZ = bz;
        }
        return null;
    }

    // Determine which face was entered, based on the previous cell.
    private static int faceFromEntry(int bx, int by, int bz, int px, int py, int pz) {
        if (py < by) return 0; // bottom
        if (py > by) return 1; // top
        if (pz < bz) return 2; // z-
        if (pz > bz) return 3; // z+
        if (px < bx) return 4; // x-
        if (px > bx) return 5; // x+
        return 1;
    }
}
