package dev.hitom.photographica.client.render;

import dev.hitom.photographica.block.PhotoStandBlock;
import dev.hitom.photographica.block.entity.PhotoStandBlockEntity;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

@Environment(EnvType.CLIENT)
public class PhotoStandBlockEntityRenderer implements BlockEntityRenderer<PhotoStandBlockEntity, PhotoStandBlockEntityRenderer.State> {

    private static final float X0 = 3.5f / 16f;
    private static final float X1 = 12.5f / 16f;
    private static final float Y0 = 2f   / 16f;
    private static final float Y1 = 8f   / 16f;

    private static final float PANEL_Z = 14f / 16f + 0.001f;
    private static final float PIVOT_Y = 1f  / 16f;
    private static final float PIVOT_Z = 12f / 16f;

    public PhotoStandBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PhotoStandBlockEntity entity, State state, float partialTick,
                                   Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(entity, state, crumbling);
        PhotoData photo = entity.getPhotoData();
        state.texId = photo != null ? PhotoTextureCache.getOrLoad(photo.id()) : null;
        state.facing = entity.getBlockState().getValue(PhotoStandBlock.FACING);
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector nodes, CameraRenderState cameraState) {
        if (state.texId == null) return;

        matrices.pushPose();

        // 1. Align to facing direction (Y-axis rotation around block centre).
        matrices.translate(0.5, 0.5, 0.5);
        matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-state.facing.toYRot()));
        matrices.translate(-0.5, -0.5, -0.5);

        // 2. Tilt panel -22.5 degrees around X matching model JSON rotation origin [8,1,12].
        matrices.translate(0.5f, PIVOT_Y, PIVOT_Z);
        matrices.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-22.5f));
        matrices.translate(-0.5f, -PIVOT_Y, -PIVOT_Z);

        int light = state.lightCoords;
        int overlay = OverlayTexture.NO_OVERLAY;
        float x0 = X0, x1 = X1, y0 = Y0, y1 = Y1, panelZ = PANEL_Z;

        nodes.submitCustomGeometry(matrices, RenderTypes.entityCutout(state.texId), (pose, vc) -> {
            vc.addVertex(pose, x0, y0, panelZ).setColor(255, 255, 255, 255).setUv(0f, 1f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
            vc.addVertex(pose, x1, y0, panelZ).setColor(255, 255, 255, 255).setUv(1f, 1f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
            vc.addVertex(pose, x1, y1, panelZ).setColor(255, 255, 255, 255).setUv(1f, 0f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
            vc.addVertex(pose, x0, y1, panelZ).setColor(255, 255, 255, 255).setUv(0f, 0f).setOverlay(overlay).setLight(light).setNormal(pose, 0f, 0f, 1f);
        });

        matrices.popPose();
    }

    public static class State extends BlockEntityRenderState {
        public Identifier texId;
        public Direction facing;
    }
}
