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
 *   - Model occupies [0,0,0]→[16,16,2] in block units.
 *   - Photo quad drawn at z = 2/16 + ε, covering x ∈ [3.5,12.5], y ∈ [2,14]
 *     which preserves the 3:4 aspect ratio of captured photos (9 × 12 pixels).
 *   - Matrix is rotated to match the block's FACING before drawing.
 */
@Environment(EnvType.CLIENT)
public class PhotoFrameBlockEntityRenderer implements BlockEntityRenderer<PhotoFrameBlockEntity> {

    // Photo area in block units (0–16) for FACING=SOUTH (default orientation).
    // 3:4 portrait: width=9/16, height=12/16, centered in the 16×16 face.
    private static final float X0 = 3.5f / 16f;
    private static final float X1 = 12.5f / 16f;
    private static final float Y0 = 2f / 16f;   // bottom of photo (3:4 centered in frame)
    private static final float Y1 = 14f / 16f;  // top of photo
    private static final float Z  = 2f / 16f + 0.001f; // just in front of model south face

    public PhotoFrameBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(PhotoFrameBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        PhotoData photo = entity.getPhotoData();
        if (photo == null) return;

        UUID photoId = photo.id();
        Identifier texId = PhotoTextureCache.getOrLoad(photoId);
        if (texId == null) return;

        Direction facing = entity.getCachedState().get(PhotoFrameBlock.FACING);

        matrices.push();
        // Rotate around the block centre to align with the block's facing direction.
        matrices.translate(0.5, 0.5, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        matrices.translate(-0.5, -0.5, -0.5);

        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texId));

        // Quad facing +Z (south) – CCW winding from viewer looking in −Z direction.
        // UV: (0,0) = top-left of image, (1,1) = bottom-right.
        vc.vertex(entry, X0, Y0, Z).color(255, 255, 255, 255).texture(0f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y0, Z).color(255, 255, 255, 255).texture(1f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y1, Z).color(255, 255, 255, 255).texture(1f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X0, Y1, Z).color(255, 255, 255, 255).texture(0f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);

        matrices.pop();
    }
}
