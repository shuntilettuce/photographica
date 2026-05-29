package dev.hitom.photographica.client.render;

import dev.hitom.photographica.block.PhotoFrameBlock;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;

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

    // Inner black area of the 16×16 frame texture (px 2..13) mapped onto the 12×8 face:
    // X: 2+(2/16)*12 = 3.5, X: 2+(14/16)*12 = 12.5  →  9 px wide
    // Y: 12-(2/16)*8 = 11,  Y: 12-(14/16)*8 = 5      →  6 px tall  (3:2)
    private static final float X0 = 3.5f / 16f;
    private static final float X1 = 12.5f / 16f;
    private static final float Y0 = 5f  / 16f;
    private static final float Y1 = 11f / 16f;
    private static final float Z  = 2f / 16f + 0.001f; // just in front of model south face

    public PhotoFrameBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(PhotoFrameBlockEntity entity, float tickDelta, PoseStack matrices,
                       MultiBufferSource vertexConsumers, int light, int overlay) {
        PhotoData photo = entity.getPhotoData();
        if (photo == null) return;

        UUID photoId = photo.id();
        ResourceLocation texId = PhotoTextureCache.getOrLoad(photoId);
        if (texId == null) return;

        Direction facing = entity.getBlockState().getValue(PhotoFrameBlock.FACING);

        matrices.pushPose();
        // Rotate around the block centre to align with the block's facing direction.
        matrices.translate(0.5, 0.5, 0.5);
        matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facing.toYRot()));
        matrices.translate(-0.5, -0.5, -0.5);

        PoseStack.Pose entry = matrices.last();
        VertexConsumer vc = vertexConsumers.getBuffer(RenderType.entityCutoutNoCull(texId));

        // Quad facing +Z (south) – CCW winding from viewer looking in −Z direction.
        // UV: (0,0) = top-left of image, (1,1) = bottom-right.
        vc.vertex(entry, X0, Y0, Z).setColor(255, 255, 255, 255).setUv(0f, 1f).setOverlay(overlay).setLight(light).setNormal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y0, Z).setColor(255, 255, 255, 255).setUv(1f, 1f).setOverlay(overlay).setLight(light).setNormal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y1, Z).setColor(255, 255, 255, 255).setUv(1f, 0f).setOverlay(overlay).setLight(light).setNormal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X0, Y1, Z).setColor(255, 255, 255, 255).setUv(0f, 0f).setOverlay(overlay).setLight(light).setNormal(entry, 0f, 0f, 1f);

        matrices.popPose();
    }
}
