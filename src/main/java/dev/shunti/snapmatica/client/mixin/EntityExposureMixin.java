package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.EntityExposure;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
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
 * <p><b>Recording.</b> On the first render frame after each minecraft tick, this asks the renderer
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

}
