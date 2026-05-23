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
public class PhotoFrameBlockEntityRenderer implements BlockEntityRenderer<PhotoFrameBlockEntity> {

    // Photo area inset 2px from the 12×8 model face edges.
    private static final float X0 = 4f / 16f;
    private static final float X1 = 12f / 16f;
    private static final float Y0 = 6f / 16f;
    private static final float Y1 = 10f / 16f;
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
