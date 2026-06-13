package dev.shunti.snapmatica.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Optional Distant Horizons integration for AF depth detection.
 * Returns 999f immediately when DH is not installed.
 */
public final class DhIntegration {
    private DhIntegration() {}

    private static final boolean DH_PRESENT =
            FabricLoader.getInstance().isModLoaded("distanthorizons");

    /** Returns distance in blocks to the first DH terrain along the look ray, or 999f if none. */
    public static float queryLookDistance(MinecraftClient mc) {
        if (!DH_PRESENT || mc.player == null) return 999f;
        try {
            return queryInternal(mc);
        } catch (NoClassDefFoundError | Exception ignored) {
            return 999f;
        }
    }

    private static float queryInternal(MinecraftClient mc) {
        IDhApiWorldProxy worldProxy = DhApi.Delayed.worldProxy;
        if (worldProxy == null || !worldProxy.worldLoaded()) return 999f;

        IDhApiLevelWrapper level = worldProxy.getSinglePlayerLevel();
        if (level == null) {
            Iterable<IDhApiLevelWrapper> levels = worldProxy.getAllLoadedLevelWrappers();
            if (levels == null) return 999f;
            for (IDhApiLevelWrapper l : levels) { level = l; break; }
        }
        if (level == null) return 999f;

        Vec3d eye  = mc.player.getCameraPosVec(1.0f);
        Vec3d look = mc.player.getRotationVec(1.0f);

        DhApiResult<DhApiRaycastResult> result = DhApi.Delayed.terrainRepo.raycast(
                level,
                eye.x, eye.y, eye.z,
                (float) look.x, (float) look.y, (float) look.z,
                100_000,
                null);

        if (result == null || !result.success || result.payload == null) return 999f;
        DhApiRaycastResult hit = result.payload;
        if (hit.pos == null) return 999f;

        double dx = hit.pos.x - eye.x;
        double dy = hit.pos.y - eye.y;
        double dz = hit.pos.z - eye.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist > 0f ? dist : 999f;
    }
}
