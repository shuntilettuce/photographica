package dev.hitom.photographica.client.render;

import dev.hitom.photographica.entity.DroneEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Draws the drone's real 3D geometry (see {@link DroneEntityModel}) — the billboard-sprite
 * placeholder this replaced is gone entirely now that a proper model exists. Bank (roll) is
 * applied straight to the root {@link ModelPart} from {@link DroneEntity#getBank()}, which is
 * synced from the pilot's own client-side lean (see {@code DronePilot}/{@code CameraMixin}) —
 * so every viewer sees the airframe tilt into a turn, not just the pilot's own camera.
 *
 * <p>Three top-level implementations: the {@code EntityRenderer} class itself gained a second
 * type parameter (a render-state object) at 1.21.4, and 1.21.11 additionally replaced the
 * {@code VertexConsumerProvider}-based {@code render()} with an
 * {@code OrderedRenderCommandQueue}-based one whose {@code submitModelPart} takes a
 * {@code Sprite} for atlas remapping — {@code null} there means "use this texture directly,
 * no atlas", which is the correct choice for a standalone (non-atlas) PNG like this one, but is
 * the single least-tested line in this file.
 */
@Environment(EnvType.CLIENT)
//? if >=1.21.11 {
/*public class DroneEntityRenderer extends EntityRenderer<DroneEntity, DroneEntityModel.State> {
    private static final Identifier TEXTURE = Identifier.of("photographica", "textures/entity/drone.png");
    private final ModelPart root;

    public DroneEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.root = DroneEntityModel.create(ctx);
    }

    @Override
    public DroneEntityModel.State createRenderState() {
        return new DroneEntityModel.State();
    }

    // The pilot is using this drone AS their camera right now — seeing its own body/mounted
    // camera floating in front of the shot would be exactly as wrong as the player's own head
    // rendering in first person. Only the piloting client hides it; every other player still
    // sees it fly normally, since shouldRender() is evaluated locally per viewer.
    @Override
    public boolean shouldRender(DroneEntity entity, net.minecraft.client.render.Frustum frustum,
                                double x, double y, double z) {
        if (dev.hitom.photographica.client.DronePilot.isActive()
                && dev.hitom.photographica.client.DronePilot.droneEntityId() == entity.getId()) {
            return false;
        }
        return super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public void updateRenderState(DroneEntity entity, DroneEntityModel.State state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.bank = entity.getBank();
    }

    @Override
    public void render(DroneEntityModel.State state, MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                       net.minecraft.client.render.state.CameraRenderState cameraState) {
        matrices.push();
        root.roll = (float) Math.toRadians(state.bank);
        queue.submitModelPart(root, matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(TEXTURE),
                state.light, OverlayTexture.DEFAULT_UV, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
*///?} else if >=1.21.4 {
/*public class DroneEntityRenderer extends EntityRenderer<DroneEntity, DroneEntityModel.State> {
    private static final Identifier TEXTURE = Identifier.of("photographica", "textures/entity/drone.png");
    private final ModelPart root;

    public DroneEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.root = DroneEntityModel.create(ctx);
    }

    @Override
    public DroneEntityModel.State createRenderState() {
        return new DroneEntityModel.State();
    }

    @Override
    public boolean shouldRender(DroneEntity entity, net.minecraft.client.render.Frustum frustum,
                                double x, double y, double z) {
        if (dev.hitom.photographica.client.DronePilot.isActive()
                && dev.hitom.photographica.client.DronePilot.droneEntityId() == entity.getId()) {
            return false;
        }
        return super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public void updateRenderState(DroneEntity entity, DroneEntityModel.State state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.bank = entity.getBank();
    }

    @Override
    public void render(DroneEntityModel.State state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        root.roll = (float) Math.toRadians(state.bank);
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        root.render(matrices, vc, light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
        super.render(state, matrices, vertexConsumers, light);
    }
}
*///?} else {
public class DroneEntityRenderer extends EntityRenderer<DroneEntity> {
    private static final Identifier TEXTURE = Identifier.of("photographica", "textures/entity/drone.png");
    private final ModelPart root;

    public DroneEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.root = DroneEntityModel.create(ctx);
        this.shadowRadius = 0.4f;
    }

    @Override
    public Identifier getTexture(DroneEntity entity) {
        return TEXTURE;
    }

    @Override
    public boolean shouldRender(DroneEntity entity, net.minecraft.client.render.Frustum frustum,
                                double x, double y, double z) {
        if (dev.hitom.photographica.client.DronePilot.isActive()
                && dev.hitom.photographica.client.DronePilot.droneEntityId() == entity.getId()) {
            return false;
        }
        return super.shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public void render(DroneEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        root.roll = (float) Math.toRadians(entity.getBank());
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        root.render(matrices, vc, light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }
}
//?}
