package dev.hitom.photographica.client.render;

import dev.hitom.photographica.block.PhotoFrameBlock;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
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
 * Renders the photo image onto the front face of a photo frame block.
 *
 * Coordinate system (FACING=SOUTH, i.e. default model orientation):
 *   - Model occupies [2,4,0]→[14,12,2] in block units (12×8 = 3:2 landscape).
 *   - Photo quad drawn at z = 2/16 + ε, covering the full model face.
 *   - Matrix is rotated to match the block's FACING before drawing.
 */
@Environment(EnvType.CLIENT)
//? if >=1.21.11 {
/*public class PhotoFrameBlockEntityRenderer
        implements BlockEntityRenderer<PhotoFrameBlockEntity, PhotoFrameBlockEntityRenderer.State> {

    private static final float X0 = 3.5f / 16f, X1 = 12.5f / 16f, Y0 = 5f / 16f, Y1 = 11f / 16f, Z = 2f / 16f + 0.001f;
    private static final float PX0 = 5f / 16f, PX1 = 11f / 16f, PY0 = 3.5f / 16f, PY1 = 12.5f / 16f;
    private static final Identifier FRAME_TEX = Identifier.of("photographica", "textures/block/photo_frame_front.png");

    public PhotoFrameBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void updateRenderState(PhotoFrameBlockEntity entity, State state, float tickDelta,
                                  net.minecraft.util.math.Vec3d cameraPos,
                                  net.minecraft.client.render.command.ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        net.minecraft.client.render.block.entity.state.BlockEntityRenderState.updateBlockEntityRenderState(entity, state, crumbling);
        PhotoData photo = entity.getPhotoData();
        state.texId = photo != null ? PhotoTextureCache.getOrLoad(photo.id()) : null;
        state.portrait = photo != null && PhotoTextureCache.isPortrait(photo.id());
        state.facing = entity.getCachedState().get(PhotoFrameBlock.FACING);
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

        // Frame bezel as a 2px-thick box, rolled 90 deg about its centre for a portrait photo.
        final float xa = 2f / 16f, xb = 14f / 16f, ya = 4f / 16f, yb = 12f / 16f, zf = 2f / 16f, zb = 0f;
        matrices.push();
        if (state.portrait) {
            matrices.translate(0.5, 0.5, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
            matrices.translate(-0.5, -0.5, 0.0);
        }
        queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(FRAME_TEX), (e, vc) -> {
            face(vc, e, light, overlay, xa, ya, zf, xb, ya, zf, xb, yb, zf, xa, yb, zf, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
            face(vc, e, light, overlay, xa, ya, zb, xb, ya, zb, xb, ya, zf, xa, ya, zf, 0f, 0f, 0.0625f, 1f, 0f, -1f, 0f);
            face(vc, e, light, overlay, xa, yb, zf, xb, yb, zf, xb, yb, zb, xa, yb, zb, 0f, 0f, 0.0625f, 1f, 0f, 1f, 0f);
            face(vc, e, light, overlay, xa, ya, zb, xa, ya, zf, xa, yb, zf, xa, yb, zb, 0f, 0f, 0.0625f, 1f, -1f, 0f, 0f);
            face(vc, e, light, overlay, xb, ya, zf, xb, ya, zb, xb, yb, zb, xb, yb, zf, 0f, 0f, 0.0625f, 1f, 1f, 0f, 0f);
        });
        matrices.pop();

        if (state.texId != null) {
            final float z = Z;
            final float x0 = state.portrait ? PX0 : X0, x1 = state.portrait ? PX1 : X1;
            final float y0 = state.portrait ? PY0 : Y0, y1 = state.portrait ? PY1 : Y1;
            queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(state.texId), (e, vc) ->
                    face(vc, e, light, overlay, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, 0f, 0f, 1f, 1f, 0f, 0f, 1f));
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
public class PhotoFrameBlockEntityRenderer implements BlockEntityRenderer<PhotoFrameBlockEntity> {

    // Inner black area of the 16×16 frame texture (px 2..13) mapped onto the 12×8 face:
    // X: 2+(2/16)*12 = 3.5, X: 2+(14/16)*12 = 12.5  →  9 px wide
    // Y: 12-(2/16)*8 = 11,  Y: 12-(14/16)*8 = 5      →  6 px tall  (3:2)
    private static final float X0 = 3.5f / 16f;
    private static final float X1 = 12.5f / 16f;
    private static final float Y0 = 5f  / 16f;
    private static final float Y1 = 11f / 16f;
    private static final float Z  = 2f / 16f + 0.001f; // just in front of model south face
    // Portrait photo window — the landscape window (9×6) rolled 90° about the centre (6×9).
    private static final float PX0 = 5f    / 16f;
    private static final float PX1 = 11f   / 16f;
    private static final float PY0 = 3.5f  / 16f;
    private static final float PY1 = 12.5f / 16f;
    // The frame bezel, drawn here (not in the block model) so a portrait photo can roll the whole frame 90°.
    private static final Identifier FRAME_TEX =
            Identifier.of("photographica", "textures/block/photo_frame_front.png");

    public PhotoFrameBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(PhotoFrameBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        PhotoData photo = entity.getPhotoData();
        boolean portrait = photo != null && PhotoTextureCache.isPortrait(photo.id());
        Identifier texId = photo != null ? PhotoTextureCache.getOrLoad(photo.id()) : null;

        Direction facing = entity.getCachedState().get(PhotoFrameBlock.FACING);

        matrices.push();
        // Rotate around the block centre to align with the block's facing direction.
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

        // Frame bezel — rolled 90° about its centre for a portrait photo.
        matrices.push();
        if (portrait) {
            matrices.translate(0.5, 0.5, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
            matrices.translate(-0.5, -0.5, 0.0);
        }
        {
            MatrixStack.Entry e = matrices.peek();
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(FRAME_TEX));
            // 2px-thick bezel box: front face (full texture) + four edges (1px bezel column).
            float xa = 2f / 16f, xb = 14f / 16f, ya = 4f / 16f, yb = 12f / 16f, zf = 2f / 16f, zb = 0f;
            face(vc, e, light, overlay, xa, ya, zf, xb, ya, zf, xb, yb, zf, xa, yb, zf, 0f, 0f, 1f, 1f, 0f, 0f, 1f);
            face(vc, e, light, overlay, xa, ya, zb, xb, ya, zb, xb, ya, zf, xa, ya, zf, 0f, 0f, 0.0625f, 1f, 0f, -1f, 0f);
            face(vc, e, light, overlay, xa, yb, zf, xb, yb, zf, xb, yb, zb, xa, yb, zb, 0f, 0f, 0.0625f, 1f, 0f, 1f, 0f);
            face(vc, e, light, overlay, xa, ya, zb, xa, ya, zf, xa, yb, zf, xa, yb, zb, 0f, 0f, 0.0625f, 1f, -1f, 0f, 0f);
            face(vc, e, light, overlay, xb, ya, zf, xb, ya, zb, xb, yb, zb, xb, yb, zf, 0f, 0f, 0.0625f, 1f, 1f, 0f, 0f);
        }
        matrices.pop();

        // Photo, kept upright. Landscape window, or a 2:3 portrait window for portrait shots.
        if (texId != null) {
            float x0 = portrait ? PX0 : X0;
            float x1 = portrait ? PX1 : X1;
            float y0 = portrait ? PY0 : Y0;
            float y1 = portrait ? PY1 : Y1;
            MatrixStack.Entry entry = matrices.peek();
            VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texId));
            vc.vertex(entry, x0, y0, Z).color(255, 255, 255, 255).texture(0f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
            vc.vertex(entry, x1, y0, Z).color(255, 255, 255, 255).texture(1f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
            vc.vertex(entry, x1, y1, Z).color(255, 255, 255, 255).texture(1f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
            vc.vertex(entry, x0, y1, Z).color(255, 255, 255, 255).texture(0f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
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
