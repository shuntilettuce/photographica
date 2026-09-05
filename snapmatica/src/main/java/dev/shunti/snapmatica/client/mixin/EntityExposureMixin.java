package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.EntityExposure;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Record every entity's appearance across the exposure, then replay it into the pupil samples.
 *
 * <p>{@code EntityRenderer.getAndUpdateRenderState(entity, φ)} is the single door every drawn
 * entity comes through, and it builds a COMPLETE state from scratch on every call — position,
 * body yaw, head yaw, limb swing, arm swing, age, pose, held item, the lot — for any phase
 * {@code φ} inside the current tick. That makes it both the place to record and the place to
 * replay, and it is why nothing here enumerates fields: a mob's legs, its swinging arm, its
 * turning head and its position all came out of one vanilla call and cannot disagree with each
 * other.
 *
 * <p><b>Recording.</b> On the first render frame after each client tick, this asks the renderer
 * for the states at whatever instants of the exposure that tick carries, and keeps them. Those
 * calls land back in this very injection, so they are bracketed by
 * {@link EntityExposure#beginReentrant()}, and the frame's own state is returned untouched so
 * the world keeps drawing normally while the shutter is open.
 *
 * <p><b>Replaying.</b> Each sub-frame is served the state recorded for its own {@code e_i}
 * instead of a fresh one. An entity that was not recorded — it came into view late — falls
 * through and draws live.
 *
 * <p>See {@link EntityExposure} for why the instant has to be chosen rather than left to whenever
 * a frame happened to arrive.
 *
 * <p>On {@code EntityRenderer} rather than the dispatcher above it, because the dispatcher was
 * renamed at 1.21.10 ({@code EntityRenderDispatcher} → {@code EntityRenderManager}) and only
 * gained a public entry point then, while this method has been on the renderer, unchanged, since
 * render states arrived at 1.21.2. One target, four versions. Below 1.21.2 there is no render
 * state to record — the appearance is computed inline while drawing — and the mixin is empty.
 */
@Mixin(EntityRenderer.class)
public class EntityExposureMixin {

    //? if >=1.21.2 {
    @Inject(method = "getAndUpdateRenderState", at = @At("HEAD"), cancellable = true)
    private void snapmatica$exposureState(
            Entity entity, float tickProgress,
            CallbackInfoReturnable<net.minecraft.client.render.entity.state.EntityRenderState> cir) {
        if (entity == null || EntityExposure.isReentrant()) return;

        if (EntityExposure.isReplaying()) {
            Object s = EntityExposure.stateFor(entity.getId());
            if (s instanceof net.minecraft.client.render.entity.state.EntityRenderState) {
                cir.setReturnValue(
                        (net.minecraft.client.render.entity.state.EntityRenderState) s);
            }
            return;
        }

        if (!EntityExposure.snapshotFrameActive()) return;
        float[][] pending = EntityExposure.pendingSlots();
        if (pending.length == 0) return;

        @SuppressWarnings("rawtypes")
        EntityRenderer self = (EntityRenderer) (Object) this;
        EntityExposure.beginReentrant();
        try {
            // Slots asking for the same instant share one object. A fast shutter puts its whole
            // exposure inside a single tick, so all of its slots land within a thousandth of a
            // tick of each other, and building a state for each would be dozens of identical
            // objects per entity in one frame.
            Object last = null;
            float lastPhi = 0f;
            for (float[] p : pending) {
                if (last == null || Math.abs(p[1] - lastPhi) >= 1.0f / 256.0f) {
                    @SuppressWarnings("unchecked")
                    Object built = self.getAndUpdateRenderState(entity, p[1]);
                    last = built;
                    lastPhi = p[1];
                }
                EntityExposure.store((int) p[0], entity.getId(), last);
                // The same instant's body, as plain numbers: where it was and how big. That is
                // all the smear needs, and keeping it out of the state object keeps
                // EntityExposure free of render-state types it would otherwise have to
                // version-gate.
                net.minecraft.client.render.entity.state.EntityRenderState st =
                        (net.minecraft.client.render.entity.state.EntityRenderState) last;
                EntityExposure.storeBody((int) p[0], entity.getId(),
                        st.x, st.y, st.z, st.width, st.height);
            }
        } finally {
            EntityExposure.endReentrant();
        }
    }
    //?}
}
