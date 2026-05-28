package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Client-side auto-focus tick handler. Runs while the sneak viewfinder is active.
 *
 * Focus modes:
 *   MF  – manual; focusDistance left untouched (scroll-wheel control only)
 *   AF  – snap focusDistance to the centre scene depth (PhotoCapture.lastSceneDepthBlocks)
 *   MOB – snap focusDistance to the nearest living entity in a 5° forward cone
 */
@Environment(EnvType.CLIENT)
public final class AutoFocus {
    private AutoFocus() {}

    private static final int FOCUS_MF  = 0;
    private static final int FOCUS_AF  = 1;
    private static final int FOCUS_MOB = 2;

    private static final List<Float> FOCUS_STOPS = List.of(
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f,
            5.0f, 6.0f, 7.0f, 8.0f, 10.0f, 12.0f, 15.0f, 20.0f, 25.0f, 30.0f,
            40.0f, 50.0f, 70.0f, 100.0f, 999.0f);

    // cos(5°) — entities must be within this cone of the look direction
    private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (!SnapmaticaClient.viewfinderSneakEnabled || !mc.player.isSneaking()) return;
        if (SnapmaticaClient.focusMode == FOCUS_MF) return;

        float targetDepth;
        if (SnapmaticaClient.focusMode == FOCUS_AF) {
            targetDepth = PhotoCapture.lastSceneDepthBlocks;
        } else if (SnapmaticaClient.focusMode == FOCUS_MOB) {
            Float mobDist = nearestMobInCone(mc);
            if (mobDist == null) return;
            targetDepth = mobDist;
        } else {
            return;
        }

        SnapmaticaClient.focusDistance = snapFocus(targetDepth);
    }

    private static float snapFocus(float depth) {
        depth = Math.max(0.01f, depth);
        // Snap in log space so focus can reach distant stops (up to 999 = infinity)
        // without the linear gap between 100 and 999 swallowing everything.
        float logDepth = (float) Math.log(depth);
        float best = FOCUS_STOPS.get(0);
        float bestDiff = Float.MAX_VALUE;
        for (float stop : FOCUS_STOPS) {
            float d = Math.abs(logDepth - (float) Math.log(stop));
            if (d < bestDiff) { bestDiff = d; best = stop; }
        }
        return best;
    }

    private static Float nearestMobInCone(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);

        double best = Double.MAX_VALUE;
        for (LivingEntity e : mc.world.getEntitiesByClass(LivingEntity.class,
                mc.player.getBoundingBox().expand(50.0), ent -> ent != mc.player && ent.isAlive())) {
            //? if >=1.21.11 {
            /*Vec3d toEnt = e.getEntityPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);*/
            //?} else {
            Vec3d toEnt = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
            //?}
            double dist = toEnt.length();
            if (dist < 0.1) continue;
            if (toEnt.normalize().dotProduct(look) >= MOB_CONE_COS && dist < best) best = dist;
        }
        return best < Double.MAX_VALUE ? (float) best : null;
    }
}
