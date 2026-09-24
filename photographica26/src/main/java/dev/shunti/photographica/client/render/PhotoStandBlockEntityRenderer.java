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

    // Base (wooden foot) drawn here rather than in the block model so its width can adapt
    // to the inserted photo: wide for a landscape shot, slim for a portrait shot.
    private static final Identifier BASE_TEX =
            Identifier.fromNamespaceAndPath("minecraft", "textures/block/dark_oak_planks.png");
    // Panel bezel — drawn here too so the whole panel can roll 90° for a portrait photo.
    private static final Identifier PANEL_TEX =
            Identifier.fromNamespaceAndPath("photographica", "textures/block/photo_stand_panel.png");
    // Portrait photo window on the panel — landscape window (9×6) rolled 90° about its centre (6×9).
    private static final float SPX0 = 5f / 16f, SPX1 = 11f / 16f, SPY0 = 0.5f / 16f, SPY1 = 9.5f / 16f;

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
        state.portrait = photo != null && PhotoTextureCache.isPortrait(photo.id());
        state.facing = entity.getBlockState().getValue(PhotoStandBlock.FACING);
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector nodes, CameraRenderState cameraState) {
        matrices.pushPose();

        // 1. Align to facing direction (Y-axis rotation around block centre).
        matrices.translate(0.5, 0.5, 0.5);
        matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-state.facing.toYRot()));
        matrices.translate(-0.5, -0.5, -0.5);

        final int light = state.lightCoords;
        final int overlay = OverlayTexture.NO_OVERLAY;

        // Base foot (flat, untilted). Width/depth adapt to the photo: slim for portrait, wide otherwise.
        final float bxa = state.portrait ? 5f / 16f : 1f / 16f;
        final float bxb = state.portrait ? 11f / 16f : 15f / 16f;
        final float bza = state.portrait ? 4f / 16f : 2f / 16f;
        final float bzb = 14f / 16f;
        final float by0 = 0f, by1 = 2f / 16f;
        nodes.submitCustomGeometry(matrices, RenderTypes.entityCutout(BASE_TEX), (pose, vc) -> {
            face(vc, pose, light, overlay, bxa, by1, bza, bxb, by1, bza, bxb, by1, bzb, bxa, by1, bzb, 0f, 0f, 1f, 1f, 0f, 1f, 0f);     // top
            face(vc, pose, light, overlay, bxa, by0, bzb, bxb, by0, bzb, bxb, by1, bzb, bxa, by1, bzb, 0f, 0f, 1f, 0.125f, 0f, 0f, 1f); // front
            face(vc, pose, light, overlay, bxb, by0, bza, bxa, by0, bza, bxa, by1, bza, bxb, by1, bza, 0f, 0f, 1f, 0.125f, 0f, 0f, -1f);// back
            face(vc, pose, light, overlay, bxa, by0, bza, bxa, by0, bzb, bxa, by1, bzb, bxa, by1, bza, 0f, 0f, 1f, 0.125f, -1f, 0f, 0f);// left
            face(vc, pose, light, overlay, bxb, by0, bzb, bxb, by0, bza, bxb, by1, bza, bxb, by1, bzb, 0f, 0f, 1f, 0.125f, 1f, 0f, 0f); // right
        });

        // Tilted panel: bezel box (rolled 90° about its centre for a portrait photo) + photo.
        matrices.pushPose();
        matrices.translate(0.5f, PIVOT_Y, PIVOT_Z);
        matrices.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-22.5f));
        matrices.translate(-0.5f, -PIVOT_Y, -PIVOT_Z);

        // Panel bezel as a 2px-thick box, rolled 90° about its centre when portrait.
        final float pxa = 2f / 16f, pxb = 14f / 16f, pya = 1f / 16f, pyb = 9f / 16f;
        final float pzf = 14f / 16f, pzb = 12f / 16f;
        matrices.pushPose();
        if (state.portrait) {
            matrices.translate(0.5, 5f / 16f, 0.0);
            matrices.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90f));
            matrices.translate(-0.5, -5f / 16f, 0.0);
        }
        nodes.submitCustomGeometry(matrices, RenderTypes.entityCutout(PANEL_TEX), (pose, vc) -> {
            face(vc, pose, light, overlay, pxa, pya, pzf, pxb, pya, pzf, pxb, pyb, pzf, pxa, pyb, pzf, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
            face(vc, pose, light, overlay, pxa, pya, pzb, pxb, pya, pzb, pxb, pya, pzf, pxa, pya, pzf, 0f, 0f, 0.0625f, 1f, 0f, -1f, 0f);
            face(vc, pose, light, overlay, pxa, pyb, pzf, pxb, pyb, pzf, pxb, pyb, pzb, pxa, pyb, pzb, 0f, 0f, 0.0625f, 1f, 0f, 1f, 0f);
            face(vc, pose, light, overlay, pxa, pya, pzb, pxa, pya, pzf, pxa, pyb, pzf, pxa, pyb, pzb, 0f, 0f, 0.0625f, 1f, -1f, 0f, 0f);
            face(vc, pose, light, overlay, pxb, pya, pzf, pxb, pya, pzb, pxb, pyb, pzb, pxb, pyb, pzf, 0f, 0f, 0.0625f, 1f, 1f, 0f, 0f);
        });
        matrices.popPose();

        // Photo, kept upright. Landscape window, or a 2:3 portrait window for portrait shots.
        if (state.texId != null) {
            final float pz = PANEL_Z;
            final float x0 = state.portrait ? SPX0 : X0;
            final float x1 = state.portrait ? SPX1 : X1;
            final float y0 = state.portrait ? SPY0 : Y0;
            final float y1 = state.portrait ? SPY1 : Y1;
            nodes.submitCustomGeometry(matrices, RenderTypes.entityCutout(state.texId), (pose, vc) ->
                    face(vc, pose, light, overlay, x0, y0, pz, x1, y0, pz, x1, y1, pz, x0, y1, pz, 0f, 0f, 1f, 1f, 0f, 0f, 1f));
        }
        matrices.popPose();

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
