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
 *   - Panel element [2,0,12]→[14,8,14] rotated -45° around X at pivot (8, 0, 12).
 *     Front face (z=12) is the photo surface; top leans toward z≈6 at the back.
 *   - Base element [1,0,2]→[15,2,14] flat on the ground.
 *
 * Photo quad: 12×8 = 3:2 landscape, drawn at z=12/16+ε then tilted -45° to match panel.
 */
@Environment(EnvType.CLIENT)
public class PhotoStandBlockEntityRenderer implements BlockEntityRenderer<PhotoStandBlockEntity> {

    // Photo quad extents (FACING=SOUTH, before panel tilt).
    private static final float X0 = 2f / 16f;
    private static final float X1 = 14f / 16f;
    private static final float Y0 = 0f;
    private static final float Y1 = 8f / 16f;
    // z of the panel front face; the BER quad is placed just in front.
    private static final float PANEL_Z  = 12f / 16f + 0.001f;
    // Panel tilt pivot (x-axis rotation of -45°, pivot at bottom of panel front face).
    private static final float PIVOT_Z  = 12f / 16f;

    public PhotoStandBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(PhotoStandBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        PhotoData photo = entity.getPhotoData();
        if (photo == null) return;

        UUID photoId = photo.id();
        Identifier texId = PhotoTextureCache.getOrLoad(photoId);
        if (texId == null) return;

        Direction facing = entity.getCachedState().get(PhotoStandBlock.FACING);

        matrices.push();

        // 1. Rotate around block centre to align with facing direction.
        matrices.translate(0.5, 0.5, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        matrices.translate(-0.5, -0.5, -0.5);

        // 2. Apply panel tilt (-45° around X at pivot bottom-front of panel).
        matrices.translate(0.5f, 0f, PIVOT_Z);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-45f));
        matrices.translate(-0.5f, 0f, -PIVOT_Z);

        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texId));

        // Quad facing +Z (south) before tilt — CCW winding from viewer looking in −Z.
        vc.vertex(entry, X0, Y0, PANEL_Z).color(255, 255, 255, 255).texture(0f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y0, PANEL_Z).color(255, 255, 255, 255).texture(1f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y1, PANEL_Z).color(255, 255, 255, 255).texture(1f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X0, Y1, PANEL_Z).color(255, 255, 255, 255).texture(0f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);

        matrices.pop();
    }
}
