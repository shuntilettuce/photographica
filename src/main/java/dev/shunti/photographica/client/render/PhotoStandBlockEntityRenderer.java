package dev.hitom.photographica.client.render;

import dev.hitom.photographica.block.PhotoStandBlock;
import dev.hitom.photographica.block.entity.PhotoStandBlockEntity;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

import java.util.UUID;

/**
 * Renders the photo onto the angled panel of a photo stand block.
 *
 * Model layout (FACING=SOUTH, y=0):
 *   - Panel element [2,1,12]→[14,10,14], south face at z=14 is the photo surface.
 *   - Base element [1,0,2]→[15,2,14] flat on the ground.
 *
 * Photo is drawn with a 2px frame border, centred 3:2 in the 12×9 panel.
 * BER tilt matches model JSON: -22.5° around X, pivot at origin [8,1,12].
 */
@Environment(EnvType.CLIENT)
//? if >=1.21.11 {
/*public class PhotoStandBlockEntityRenderer
        implements BlockEntityRenderer<PhotoStandBlockEntity, PhotoStandBlockEntityRenderer.State> {

    private static final float X0 = 3.5f / 16f, X1 = 12.5f / 16f, Y0 = 2f / 16f, Y1 = 8f / 16f;
    private static final float PANEL_Z = 14f / 16f + 0.001f, PIVOT_Y = 1f / 16f, PIVOT_Z = 12f / 16f;
    private static final float SPX0 = 5f / 16f, SPX1 = 11f / 16f, SPY0 = 0.5f / 16f, SPY1 = 9.5f / 16f;
    private static final Identifier BASE_TEX = Identifier.of("minecraft", "textures/block/dark_oak_planks.png");
    private static final Identifier PANEL_TEX = Identifier.of("photographica", "textures/block/photo_stand_panel.png");

    public PhotoStandBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void updateRenderState(PhotoStandBlockEntity entity, State state, float tickDelta,
                                  net.minecraft.util.math.Vec3d cameraPos,
                                  net.minecraft.client.render.command.ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        net.minecraft.client.render.block.entity.state.BlockEntityRenderState.updateBlockEntityRenderState(entity, state, crumbling);
        PhotoData photo = entity.getPhotoData();
        state.texId = photo != null ? PhotoTextureCache.getOrLoad(photo.id()) : null;
        state.portrait = photo != null && PhotoTextureCache.isPortrait(photo.id());
        state.facing = entity.getCachedState().get(PhotoStandBlock.FACING);
    }

    @Override
    public void render(State state, MatrixStack matrices,
                       net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                       net.minecraft.client.render.state.CameraRenderState cameraState) {
        final int light = state.lightmapCoordinates;
        final int overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;
        float rotDeg = switch (state.facing) {
            case SOUTH -> 0f; case WEST -> 90f; case NORTH -> 180f; case EAST -> 270f; default -> 0f;
        };

        matrices.push();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-rotDeg));
        matrices.translate(-0.5, -0.5, -0.5);

        // Base foot (flat). Width/depth adapt to the photo: slim for portrait, wide otherwise.
        {
            final float bxa = state.portrait ? 5f / 16f : 1f / 16f;
            final float bxb = state.portrait ? 11f / 16f : 15f / 16f;
            final float bza = state.portrait ? 4f / 16f : 2f / 16f;
            final float bzb = 14f / 16f, by0 = 0f, by1 = 2f / 16f;
            queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(BASE_TEX), (e, vc) -> {
                face(vc, e, light, overlay, bxa, by1, bza, bxb, by1, bza, bxb, by1, bzb, bxa, by1, bzb, 0f, 0f, 1f, 1f, 0f, 1f, 0f);
                face(vc, e, light, overlay, bxa, by0, bzb, bxb, by0, bzb, bxb, by1, bzb, bxa, by1, bzb, 0f, 0f, 1f, 0.125f, 0f, 0f, 1f);
                face(vc, e, light, overlay, bxb, by0, bza, bxa, by0, bza, bxa, by1, bza, bxb, by1, bza, 0f, 0f, 1f, 0.125f, 0f, 0f, -1f);
                face(vc, e, light, overlay, bxa, by0, bza, bxa, by0, bzb, bxa, by1, bzb, bxa, by1, bza, 0f, 0f, 1f, 0.125f, -1f, 0f, 0f);
                face(vc, e, light, overlay, bxb, by0, bzb, bxb, by0, bza, bxb, by1, bza, bxb, by1, bzb, 0f, 0f, 1f, 0.125f, 1f, 0f, 0f);
            });
        }

        // Tilt for the panel + photo.
        matrices.translate(0.5f, PIVOT_Y, PIVOT_Z);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-22.5f));
        matrices.translate(-0.5f, -PIVOT_Y, -PIVOT_Z);

        // Panel bezel box, rolled 90 deg about its centre when portrait.
        final float pxa = 2f / 16f, pxb = 14f / 16f, pya = 1f / 16f, pyb = 9f / 16f, pzf = 14f / 16f, pzb = 12f / 16f;
        matrices.push();
        if (state.portrait) {
            matrices.translate(0.5, 5f / 16f, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
            matrices.translate(-0.5, -5f / 16f, 0.0);
        }
        queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(PANEL_TEX), (e, vc) -> {
            face(vc, e, light, overlay, pxa, pya, pzf, pxb, pya, pzf, pxb, pyb, pzf, pxa, pyb, pzf, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
            face(vc, e, light, overlay, pxa, pya, pzb, pxb, pya, pzb, pxb, pya, pzf, pxa, pya, pzf, 0f, 0f, 0.0625f, 1f, 0f, -1f, 0f);
            face(vc, e, light, overlay, pxa, pyb, pzf, pxb, pyb, pzf, pxb, pyb, pzb, pxa, pyb, pzb, 0f, 0f, 0.0625f, 1f, 0f, 1f, 0f);
            face(vc, e, light, overlay, pxa, pya, pzb, pxa, pya, pzf, pxa, pyb, pzf, pxa, pyb, pzb, 0f, 0f, 0.0625f, 1f, -1f, 0f, 0f);
            face(vc, e, light, overlay, pxb, pya, pzf, pxb, pya, pzb, pxb, pyb, pzb, pxb, pyb, pzf, 0f, 0f, 0.0625f, 1f, 1f, 0f, 0f);
        });
        matrices.pop();

        if (state.texId != null) {
            final float x0 = state.portrait ? SPX0 : X0, x1 = state.portrait ? SPX1 : X1;
            final float y0 = state.portrait ? SPY0 : Y0, y1 = state.portrait ? SPY1 : Y1;
            queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(state.texId), (e, vc) ->
                    face(vc, e, light, overlay, x0, y0, PANEL_Z, x1, y0, PANEL_Z, x1, y1, PANEL_Z, x0, y1, PANEL_Z, 0f, 0f, 1f, 1f, 0f, 0f, 1f));
        }
        matrices.pop();
    }

    private static void face(VertexConsumer vc, MatrixStack.Entry e, int light, int overlay,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1, float nx, float ny, float nz) {
        vc.vertex(e, ax, ay, az).color(255, 255, 255, 255).texture(u0, v1).overlay(overlay).light(light).normal(e, nx, ny, nz);
        vc.vertex(e, bx, by, bz).color(255, 255, 255, 255).texture(u1, v1).overlay(overlay).light(light).normal(e, nx, ny, nz);
        vc.vertex(e, cx, cy, cz).color(255, 255, 255, 255).texture(u1, v0).overlay(overlay).light(light).normal(e, nx, ny, nz);
        vc.vertex(e, dx, dy, dz).color(255, 255, 255, 255).texture(u0, v0).overlay(overlay).light(light).normal(e, nx, ny, nz);
    }

    public static class State extends net.minecraft.client.render.block.entity.state.BlockEntityRenderState {
        public Identifier texId;
        public boolean portrait;
        public Direction facing;
    }
}*/
//?} else {
public class PhotoStandBlockEntityRenderer implements BlockEntityRenderer<PhotoStandBlockEntity> {

    // Inner black area of the 16×16 panel texture mapped onto the 12×8 face:
    // X: 3.5/16..12.5/16 (9 px), Y: 2/16..8/16 (6 px)  →  3:2
    private static final float X0 = 3.5f / 16f;
    private static final float X1 = 12.5f / 16f;
    private static final float Y0 = 2f   / 16f;
    private static final float Y1 = 8f   / 16f;

    // Panel south face (z=14/16); photo rendered just in front of it.
    private static final float PANEL_Z = 14f / 16f + 0.001f;
    // Tilt pivot: must match model JSON rotation origin exactly.
    private static final float PIVOT_Y = 1f  / 16f;
    private static final float PIVOT_Z = 12f / 16f;

    // Base foot drawn here (not in the block model) so its width adapts to the photo.
    private static final Identifier BASE_TEX =
            Identifier.of("minecraft", "textures/block/dark_oak_planks.png");
    // Panel bezel — drawn here too so the whole panel can roll 90° for a portrait photo.
    private static final Identifier PANEL_TEX =
            Identifier.of("photographica", "textures/block/photo_stand_panel.png");
    private static final float SPX0 = 5f / 16f, SPX1 = 11f / 16f, SPY0 = 0.5f / 16f, SPY1 = 9.5f / 16f;

    public PhotoStandBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(PhotoStandBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        PhotoData photo = entity.getPhotoData();
        boolean portrait = photo != null && PhotoTextureCache.isPortrait(photo.id());
        Identifier texId = photo != null ? PhotoTextureCache.getOrLoad(photo.id()) : null;

        Direction facing = entity.getCachedState().get(PhotoStandBlock.FACING);

        matrices.push();

        // 1. Align to facing direction (Y-axis rotation around block centre).
        matrices.translate(0.5, 0.5, 0.5);
        float rotDeg = switch (facing) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case NORTH -> 180f;
            case EAST  -> 270f;
            default    -> 0f;
        };
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-rotDeg));
        matrices.translate(-0.5, -0.5, -0.5);

        // Base foot (flat, untilted). Width/depth adapt to the photo: slim for portrait, wide otherwise.
        {
            MatrixStack.Entry e = matrices.peek();
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(BASE_TEX));
            float bxa = portrait ? 5f / 16f : 1f / 16f;
            float bxb = portrait ? 11f / 16f : 15f / 16f;
            float bza = portrait ? 4f / 16f : 2f / 16f;
            float bzb = 14f / 16f, by0 = 0f, by1 = 2f / 16f;
            face(vc, e, light, overlay, bxa, by1, bza, bxb, by1, bza, bxb, by1, bzb, bxa, by1, bzb, 0f, 0f, 1f, 1f, 0f, 1f, 0f);
            face(vc, e, light, overlay, bxa, by0, bzb, bxb, by0, bzb, bxb, by1, bzb, bxa, by1, bzb, 0f, 0f, 1f, 0.125f, 0f, 0f, 1f);
            face(vc, e, light, overlay, bxb, by0, bza, bxa, by0, bza, bxa, by1, bza, bxb, by1, bza, 0f, 0f, 1f, 0.125f, 0f, 0f, -1f);
            face(vc, e, light, overlay, bxa, by0, bza, bxa, by0, bzb, bxa, by1, bzb, bxa, by1, bza, 0f, 0f, 1f, 0.125f, -1f, 0f, 0f);
            face(vc, e, light, overlay, bxb, by0, bzb, bxb, by0, bza, bxb, by1, bza, bxb, by1, bzb, 0f, 0f, 1f, 0.125f, 1f, 0f, 0f);
        }

        // 2. Tilt panel -22.5° around X matching model JSON rotation origin [8,1,12].
        matrices.translate(0.5f, PIVOT_Y, PIVOT_Z);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-22.5f));
        matrices.translate(-0.5f, -PIVOT_Y, -PIVOT_Z);

        // Panel bezel as a 2px-thick box, rolled 90° about its centre when portrait.
        matrices.push();
        if (portrait) {
            matrices.translate(0.5, 5f / 16f, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
            matrices.translate(-0.5, -5f / 16f, 0.0);
        }
        {
            MatrixStack.Entry e = matrices.peek();
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(PANEL_TEX));
            float pxa = 2f / 16f, pxb = 14f / 16f, pya = 1f / 16f, pyb = 9f / 16f, pzf = 14f / 16f, pzb = 12f / 16f;
            face(vc, e, light, overlay, pxa, pya, pzf, pxb, pya, pzf, pxb, pyb, pzf, pxa, pyb, pzf, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
            face(vc, e, light, overlay, pxa, pya, pzb, pxb, pya, pzb, pxb, pya, pzf, pxa, pya, pzf, 0f, 0f, 0.0625f, 1f, 0f, -1f, 0f);
            face(vc, e, light, overlay, pxa, pyb, pzf, pxb, pyb, pzf, pxb, pyb, pzb, pxa, pyb, pzb, 0f, 0f, 0.0625f, 1f, 0f, 1f, 0f);
            face(vc, e, light, overlay, pxa, pya, pzb, pxa, pya, pzf, pxa, pyb, pzf, pxa, pyb, pzb, 0f, 0f, 0.0625f, 1f, -1f, 0f, 0f);
            face(vc, e, light, overlay, pxb, pya, pzf, pxb, pya, pzb, pxb, pyb, pzb, pxb, pyb, pzf, 0f, 0f, 0.0625f, 1f, 1f, 0f, 0f);
        }
        matrices.pop();

        // Photo, kept upright. Landscape window, or a 2:3 portrait window for portrait shots.
        if (texId != null) {
            float x0 = portrait ? SPX0 : X0;
            float x1 = portrait ? SPX1 : X1;
            float y0 = portrait ? SPY0 : Y0;
            float y1 = portrait ? SPY1 : Y1;
            MatrixStack.Entry entry = matrices.peek();
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texId));
            face(vc, entry, light, overlay, x0, y0, PANEL_Z, x1, y0, PANEL_Z, x1, y1, PANEL_Z, x0, y1, PANEL_Z, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
        }

        matrices.pop();
    }

    /** Emits one quad (4 verts) with a flat normal; UVs map (a,b,c,d) → (u0,v1)(u1,v1)(u1,v0)(u0,v0). */
    private static void face(VertexConsumer vc, MatrixStack.Entry e, int light, int overlay,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1, float nx, float ny, float nz) {
        vc.vertex(e, ax, ay, az).color(255, 255, 255, 255).texture(u0, v1).overlay(overlay).light(light).normal(e, nx, ny, nz);
        vc.vertex(e, bx, by, bz).color(255, 255, 255, 255).texture(u1, v1).overlay(overlay).light(light).normal(e, nx, ny, nz);
        vc.vertex(e, cx, cy, cz).color(255, 255, 255, 255).texture(u1, v0).overlay(overlay).light(light).normal(e, nx, ny, nz);
        vc.vertex(e, dx, dy, dz).color(255, 255, 255, 255).texture(u0, v0).overlay(overlay).light(light).normal(e, nx, ny, nz);
    }
}
//?}
