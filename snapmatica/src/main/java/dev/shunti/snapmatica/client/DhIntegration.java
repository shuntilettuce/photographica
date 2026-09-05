package dev.shunti.snapmatica.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiRaycastResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Optional Distant Horizons integration for AF depth detection.
 * Returns -1f when DH is not installed or no LOD terrain is hit along the look ray.
 * A successful hit returns the real distance in blocks, which may be km-scale; AF now
 * focuses on it finitely rather than collapsing it to the infinity sentinel.
 */
public final class DhIntegration {
    private DhIntegration() {}

    private static final boolean DH_PRESENT = checkPresent();
    private static IDhApiTerrainDataCache dhCache = null;

    private static boolean checkPresent() {
        return FabricLoader.getInstance().isModLoaded("distanthorizons");
    }

    /** Returns distance in blocks to the first DH terrain along the look ray, or -1f if none. */
    public static float queryLookDistance(MinecraftClient mc) {
        if (!DH_PRESENT || mc.player == null) return -1f;
        try {
            return queryInternal(mc);
        } catch (NoClassDefFoundError | Exception ignored) {
            return -1f;
        }
    }

    private static float queryInternal(MinecraftClient mc) {
        IDhApiWorldProxy worldProxy = DhApi.Delayed.worldProxy;
        if (worldProxy == null || !worldProxy.worldLoaded()) return -1f;

        IDhApiTerrainDataRepo terrainRepo = DhApi.Delayed.terrainRepo;
        if (terrainRepo == null) return -1f;

        // Create and reuse a soft cache to satisfy the DH API requirement
        if (dhCache == null) {
            try { dhCache = terrainRepo.createSoftCache(); } catch (Exception ignored) {}
        }

        IDhApiLevelWrapper level = worldProxy.getSinglePlayerLevel();
        if (level == null) {
            Iterable<IDhApiLevelWrapper> levels = worldProxy.getAllLoadedLevelWrappers();
            if (levels == null) return -1f;
            for (IDhApiLevelWrapper l : levels) { level = l; break; }
        }
        if (level == null) return -1f;

        Vec3d eye  = SnapmaticaClient.cameraPos(mc);
        Vec3d look = SnapmaticaClient.cameraLook(mc);

        // Cap the LOD raycast distance: a miss (looking at sky just above terrain)
        // traverses the full distance, so 100k blocks could hitch badly. 8192 blocks
        // is still far beyond any practical focus subject.
        DhApiResult<DhApiRaycastResult> result = terrainRepo.raycast(
                level,
                eye.x, eye.y, eye.z,
                (float) look.x, (float) look.y, (float) look.z,
                8192,
                dhCache);

        if (result == null || !result.success || result.payload == null) return -1f;
        DhApiRaycastResult hit = result.payload;
        if (hit.pos == null) return -1f;

        double dx = hit.pos.x - eye.x;
        double dy = hit.pos.y - eye.y;
        double dz = hit.pos.z - eye.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist > 0f ? dist : -1f;
    }
}
