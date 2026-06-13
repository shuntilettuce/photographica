package dev.shunti.snapmatica.client;

import com.seibel.distanthorizons.api.DhApi;
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
 * Returns 999f immediately when DH is not installed.
 */
public final class DhIntegration {
    private DhIntegration() {}

    private static final boolean DH_PRESENT = checkPresent();
    private static long lastLogMs = 0L;

    private static boolean checkPresent() {
        boolean found = FabricLoader.getInstance().isModLoaded("distanthorizons");
        System.out.println("[Snapmatica/DH] distanthorizons mod present: " + found);
        return found;
    }

    /** Returns distance in blocks to the first DH terrain along the look ray, or 999f if none. */
    public static float queryLookDistance(MinecraftClient mc) {
        if (!DH_PRESENT || mc.player == null) return 999f;
        try {
            return queryInternal(mc);
        } catch (NoClassDefFoundError | Exception e) {
            logThrottled("[Snapmatica/DH] exception: " + e);
            return 999f;
        }
    }

    private static float queryInternal(MinecraftClient mc) {
        IDhApiWorldProxy worldProxy = DhApi.Delayed.worldProxy;
        if (worldProxy == null) {
            logThrottled("[Snapmatica/DH] worldProxy is null");
            return 999f;
        }
        if (!worldProxy.worldLoaded()) {
            logThrottled("[Snapmatica/DH] worldLoaded=false");
            return 999f;
        }

        IDhApiTerrainDataRepo terrainRepo = DhApi.Delayed.terrainRepo;
        if (terrainRepo == null) {
            logThrottled("[Snapmatica/DH] terrainRepo is null");
            return 999f;
        }

        IDhApiLevelWrapper level = worldProxy.getSinglePlayerLevel();
        if (level == null) {
            Iterable<IDhApiLevelWrapper> levels = worldProxy.getAllLoadedLevelWrappers();
            if (levels == null) { logThrottled("[Snapmatica/DH] no levels"); return 999f; }
            for (IDhApiLevelWrapper l : levels) { level = l; break; }
        }
        if (level == null) {
            logThrottled("[Snapmatica/DH] level is null");
            return 999f;
        }

        Vec3d eye  = mc.player.getCameraPosVec(1.0f);
        Vec3d look = mc.player.getRotationVec(1.0f);

        DhApiResult<DhApiRaycastResult> result = terrainRepo.raycast(
                level,
                eye.x, eye.y, eye.z,
                (float) look.x, (float) look.y, (float) look.z,
                100_000,
                null);

        if (result == null) { logThrottled("[Snapmatica/DH] result is null"); return 999f; }
        if (!result.success) { logThrottled("[Snapmatica/DH] raycast failed: " + result.message); return 999f; }
        if (result.payload == null) { logThrottled("[Snapmatica/DH] payload is null"); return 999f; }

        DhApiRaycastResult hit = result.payload;
        if (hit.pos == null) { logThrottled("[Snapmatica/DH] hit.pos is null"); return 999f; }

        double dx = hit.pos.x - eye.x;
        double dy = hit.pos.y - eye.y;
        double dz = hit.pos.z - eye.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist > 0f ? dist : 999f;
    }

    private static void logThrottled(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastLogMs > 3000L) {
            System.out.println(msg);
            lastLogMs = now;
        }
    }
}
