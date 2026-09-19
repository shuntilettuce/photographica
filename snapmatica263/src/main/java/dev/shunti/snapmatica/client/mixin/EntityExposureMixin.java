package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.EntityExposure;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Record every entity's appearance across the exposure, then replay it into the pupil samples.
 *
 * <p>26 calls the door {@code createRenderState(T, float)} — the 1.21.x name was
 * {@code getAndUpdateRenderState}. It builds a COMPLETE state from scratch on every call for
 * any phase inside the current tick, which is why nothing here enumerates fields: a mob's legs,
 * its swinging arm, its turning head and its position all came out of one vanilla call and
 * cannot disagree with each other.
 *
 * <p>The descriptor is spelled out because {@code createRenderState} is OVERLOADED here — there
 * is also a no-argument abstract factory — and a bare method name would be ambiguous.
 */
@Mixin(EntityRenderer.class)
public class EntityExposureMixin {

    @Inject(
        method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
        at = @At("HEAD"), cancellable = true)
    private void snapmatica$exposureState(Entity entity, float tickProgress,
                                          CallbackInfoReturnable<EntityRenderState> cir) {
        if (entity == null || EntityExposure.isReentrant()) return;

        if (EntityExposure.isReplaying()) {
            Object s = EntityExposure.stateFor(entity.getId());
            if (s instanceof EntityRenderState) cir.setReturnValue((EntityRenderState) s);
            return;
        }

        if (!EntityExposure.snapshotFrameActive()) return;
        float[][] pending = EntityExposure.pendingSlots();
        if (pending.length == 0) return;

        @SuppressWarnings("rawtypes")
        EntityRenderer self = (EntityRenderer) (Object) this;
        EntityExposure.beginReentrant();
        try {
            // Slots asking for the same instant share one object: a fast shutter puts its whole
            // exposure inside a single tick, and building a state for each would be dozens of
            // identical objects per entity in one frame.
            Object last = null;
            float lastPhi = 0f;
            for (float[] p : pending) {
                if (last == null || Math.abs(p[1] - lastPhi) >= 1.0f / 256.0f) {
                    @SuppressWarnings("unchecked")
                    Object built = self.createRenderState(entity, p[1]);
                    last = built;
                    lastPhi = p[1];
                }
                EntityExposure.store((int) p[0], entity.getId(), last);
                EntityRenderState st = (EntityRenderState) last;
                EntityExposure.storeBody((int) p[0], entity.getId(),
                        st.x, st.y, st.z, st.boundingBoxWidth, st.boundingBoxHeight);
            }
        } finally {
            EntityExposure.endReentrant();
        }
    }
}
