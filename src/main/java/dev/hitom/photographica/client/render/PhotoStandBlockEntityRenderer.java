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

        // 1. Align to facing direction (Y-axis rotation around block centre).
        matrices.translate(0.5, 0.5, 0.5);
        //? if >=1.21.4 {
        /*matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.getPositiveHorizontalDegrees()));*/
        //?} else {
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
        //?}
        matrices.translate(-0.5, -0.5, -0.5);

        // 2. Tilt panel -22.5° around X matching model JSON rotation origin [8,1,12].
        matrices.translate(0.5f, PIVOT_Y, PIVOT_Z);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-22.5f));
        matrices.translate(-0.5f, -PIVOT_Y, -PIVOT_Z);

        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texId));

        // Quad facing +Z (south) — CCW winding from viewer looking in −Z.
        vc.vertex(entry, X0, Y0, PANEL_Z).color(255, 255, 255, 255).texture(0f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y0, PANEL_Z).color(255, 255, 255, 255).texture(1f, 1f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X1, Y1, PANEL_Z).color(255, 255, 255, 255).texture(1f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);
        vc.vertex(entry, X0, Y1, PANEL_Z).color(255, 255, 255, 255).texture(0f, 0f).overlay(overlay).light(light).normal(entry, 0f, 0f, 1f);

        matrices.pop();
    }
}
