package dev.hitom.photographica.client.render;

import dev.hitom.photographica.block.PhotoFrameBlock;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
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
public class PhotoFrameBlockEntityRenderer implements BlockEntityRenderer<PhotoFrameBlockEntity, PhotoFrameBlockEntityRenderer.State> {

    private static final float X0 = 3.5f / 16f;
    private static final float X1 = 12.5f / 16f;
    private static final float Y0 = 5f  / 16f;
    private static final float Y1 = 11f / 16f;
    private static final float Z  = 2f / 16f + 0.001f;
    // Portrait photo window — the landscape window (9×6) rolled 90° about the centre (6×9).
    private static final float PX0 = 5f    / 16f;
    private static final float PX1 = 11f   / 16f;
    private static final float PY0 = 3.5f  / 16f;
    private static final float PY1 = 12.5f / 16f;
    // The frame bezel itself, drawn in the BER so it can be rolled 90° for portrait shots.
    private static final Identifier FRAME_TEX =
            Identifier.fromNamespaceAndPath("photographica", "textures/block/photo_frame_front.png");

    public PhotoFrameBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PhotoFrameBlockEntity entity, State state, float partialTick,
                                   Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(entity, state, crumbling);
        PhotoData photo = entity.getPhotoData();
        state.texId = photo != null ? PhotoTextureCache.getOrLoad(photo.id()) : null;
        state.portrait = photo != null && PhotoTextureCache.isPortrait(photo.id());
        state.facing = entity.getBlockState().getValue(PhotoFrameBlock.FACING);
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector nodes, CameraRenderState cameraState) {
        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-state.facing.toYRot()));
        matrices.translate(-0.5, -0.5, -0.5);

        final int light = state.lightCoords;
        final int overlay = OverlayTexture.NO_OVERLAY;

        // Frame bezel as a 2px-thick box (front face + four edges), drawn here rather than
        // in the block model so a portrait photo can roll the WHOLE frame 90° about its centre.
        final float xa = 2f / 16f, xb = 14f / 16f, ya = 4f / 16f, yb = 12f / 16f;
        final float zf = 2f / 16f, zb = 0f;
        matrices.pushPose();
        if (state.portrait) {
            matrices.translate(0.5, 0.5, 0.0);
            matrices.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90f));
            matrices.translate(-0.5, -0.5, 0.0);
        }
        nodes.submitCustomGeometry(matrices, RenderTypes.entityCutout(FRAME_TEX), (pose, vc) -> {
            // front face (full bezel texture)
            face(vc, pose, light, overlay, xa, ya, zf, xb, ya, zf, xb, yb, zf, xa, yb, zf, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
            // four edges — sample a 1px bezel column so they read as frame, not the photo window
            face(vc, pose, light, overlay, xa, ya, zb, xb, ya, zb, xb, ya, zf, xa, ya, zf, 0f, 0f, 0.0625f, 1f, 0f, -1f, 0f); // bottom
            face(vc, pose, light, overlay, xa, yb, zf, xb, yb, zf, xb, yb, zb, xa, yb, zb, 0f, 0f, 0.0625f, 1f, 0f, 1f, 0f);  // top
            face(vc, pose, light, overlay, xa, ya, zb, xa, ya, zf, xa, yb, zf, xa, yb, zb, 0f, 0f, 0.0625f, 1f, -1f, 0f, 0f); // left
            face(vc, pose, light, overlay, xb, ya, zf, xb, ya, zb, xb, yb, zb, xb, yb, zf, 0f, 0f, 0.0625f, 1f, 1f, 0f, 0f);  // right
        });
        matrices.popPose();

        // Photo, kept upright. Landscape window normally; a 2:3 portrait window (the
        // landscape window rolled 90°) for portrait shots so it fills the rotated frame.
        if (state.texId != null) {
            final float z = Z;
            final float x0 = state.portrait ? PX0 : X0;
            final float x1 = state.portrait ? PX1 : X1;
            final float y0 = state.portrait ? PY0 : Y0;
            final float y1 = state.portrait ? PY1 : Y1;
            nodes.submitCustomGeometry(matrices, RenderTypes.entityCutout(state.texId), (pose, vc) ->
                    face(vc, pose, light, overlay, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, 0f, 0f, 1f, 1f, 0f, 0f, 1f));
        }

        matrices.popPose();
    }

    /** Emits one quad (4 verts) with a flat normal; UVs map (a,b,c,d) → (u0,v1)(u1,v1)(u1,v0)(u0,v0). */
    private static void face(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose, int light, int overlay,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1, float nx, float ny, float nz) {
        vc.addVertex(pose, ax, ay, az).setColor(255, 255, 255, 255).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, bx, by, bz).setColor(255, 255, 255, 255).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, cx, cy, cz).setColor(255, 255, 255, 255).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, dx, dy, dz).setColor(255, 255, 255, 255).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
    }

    public static class State extends BlockEntityRenderState {
        public Identifier texId;
        public boolean portrait;
        public Direction facing;
    }
}
