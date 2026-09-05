package dev.hitom.photographica.client.render;

import dev.hitom.photographica.entity.DroneEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;

/**
 * The drone's real 3D geometry — hand-converted from the Blockbench project
 * ({@code drone.bbmodel}, "Modded Entity" format, 32-unit UV grid painted onto a 64×64
 * {@code textures/entity/drone.png}) that replaced the earlier billboard-sprite placeholder.
 * Every cuboid below is a direct transcription of that file's element list (position/size/UV
 * anchor), with box-UV auto-layout ({@code .uv(u, v)} then a single {@code .cuboid(...)} call)
 * rather than the file's raw per-face UV rectangles — Blockbench's own box-UV algorithm is what
 * produced those per-face rectangles from each cuboid's stored {@code uv_offset} anchor in the
 * first place, so reconstructing from the anchor reproduces the same layout without needing a
 * lower-level per-face UV API.
 *
 * <p>Geometry is used exactly as stored, with no Y-axis flip applied. This is the single
 * biggest guess in this conversion — if the model renders upside down in game, that is the
 * first thing to check (Blockbench's project-level "flip_y" setting, which was on for this
 * file) — but which way that flag actually needs compensating for isn't something verifiable
 * without seeing it rendered.
 *
 * <p>All parts are direct children of one "drone" part rather than mirroring the Blockbench
 * file's 足1/足2/本体/はね grouping — nothing here is independently animated yet (the whole
 * airframe just banks together as a single rigid body, see {@link DroneEntity#getBank()}), so
 * the extra hierarchy would add pivot-math risk for no present benefit. Splitting the rotor
 * blades into their own animated parts (for a spin effect) is a natural follow-up once this
 * base geometry is confirmed correct in-game.
 */
@Environment(EnvType.CLIENT)
public final class DroneEntityModel {
    private DroneEntityModel() {}

    public static final EntityModelLayer LAYER =
            new EntityModelLayer(Identifier.of("photographica", "drone"), "main");

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData drone = data.getRoot().addChild("drone", ModelPartBuilder.create(), ModelTransform.NONE);

        // 足1 (leg, +X side)
        drone.addChild("leg1_foot", ModelPartBuilder.create().uv(0, 0)
                .cuboid(2, 0, -3, 1, 1, 6), ModelTransform.NONE);
        drone.addChild("leg1_back", ModelPartBuilder.create().uv(8, 2)
                .cuboid(2, 1, -3, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("leg1_front", ModelPartBuilder.create().uv(8, 2)
                .cuboid(2, 1, 2, 1, 1, 1), ModelTransform.NONE);

        // 足2 (leg, -X side)
        drone.addChild("leg2_back", ModelPartBuilder.create().uv(8, 2)
                .cuboid(-3, 1, -3, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("leg2_front", ModelPartBuilder.create().uv(6, 2)
                .cuboid(-3, 1, 2, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("leg2_foot", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-3, 0, -3, 1, 1, 6), ModelTransform.NONE);

        // 本体: central plate, camera gimbal mount, 4 arms + their end caps, antenna
        drone.addChild("body_plate", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-3, 2, -3, 6, 1.5f, 6), ModelTransform.NONE);
        drone.addChild("camera_mount", ModelPartBuilder.create().uv(6, 2)
                .cuboid(-1, 1, -1, 2, 1, 2), ModelTransform.NONE);

        drone.addChild("arm_pp", ModelPartBuilder.create().uv(6, 12)
                .cuboid(1, 1.75f, 1, 3, 1.5f, 3), ModelTransform.NONE);
        drone.addChild("arm_np", ModelPartBuilder.create().uv(6, 12)
                .cuboid(-4, 1.75f, 1, 3, 1.5f, 3), ModelTransform.NONE);
        drone.addChild("arm_nn", ModelPartBuilder.create().uv(6, 12)
                .cuboid(-4, 1.75f, -4, 3, 1.5f, 3), ModelTransform.NONE);
        drone.addChild("arm_pn", ModelPartBuilder.create().uv(6, 12)
                .cuboid(1, 1.75f, -4, 3, 1.5f, 3), ModelTransform.NONE);

        drone.addChild("arm_cap_pp", ModelPartBuilder.create().uv(6, 4)
                .cuboid(3, 2, 3, 2, 1, 2), ModelTransform.NONE);
        drone.addChild("arm_cap_np", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-5, 2, 3, 2, 1, 2), ModelTransform.NONE);
        drone.addChild("arm_cap_nn", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-5, 2, -5, 2, 1, 2), ModelTransform.NONE);
        drone.addChild("arm_cap_pn", ModelPartBuilder.create().uv(0, 0)
                .cuboid(3, 2, -5, 2, 1, 2), ModelTransform.NONE);

        drone.addChild("motor_pp", ModelPartBuilder.create().uv(0, 0)
                .cuboid(4, 3, 4, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("motor_np", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-5, 3, 4, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("motor_pn", ModelPartBuilder.create().uv(0, 0)
                .cuboid(4, 3, -5, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("motor_nn", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-5, 3, -5, 1, 1, 1), ModelTransform.NONE);

        drone.addChild("antenna_lower", ModelPartBuilder.create().uv(3, 11)
                .cuboid(-0.5f, 1, -1.5f, 1, 1, 1), ModelTransform.NONE);
        drone.addChild("antenna_upper", ModelPartBuilder.create().uv(0, 8)
                .cuboid(-0.5f, 3, -3, 1, 1, 1), ModelTransform.NONE);

        // はね: 4 flat rotor blades, one above each arm tip. A true-zero height cuboid risks
        // degenerate (NaN) face normals in some rendering paths, so these use a hairline 0.02
        // thickness instead of the file's literal zero — visually identical, structurally safe.
        drone.addChild("blade_pn", ModelPartBuilder.create().uv(17, 27)
                .cuboid(2, 3.74f, -7, 5, 0.02f, 5), ModelTransform.NONE);
        drone.addChild("blade_nn", ModelPartBuilder.create().uv(17, 27)
                .cuboid(-7, 3.74f, -7, 5, 0.02f, 5), ModelTransform.NONE);
        drone.addChild("blade_np", ModelPartBuilder.create().uv(17, 27)
                .cuboid(-7, 3.74f, 2, 5, 0.02f, 5), ModelTransform.NONE);
        drone.addChild("blade_pp", ModelPartBuilder.create().uv(17, 27)
                .cuboid(2, 3.74f, 2, 5, 0.02f, 5), ModelTransform.NONE);

        return TexturedModelData.of(data, 32, 32);
    }

    // Render-state carrying the bank angle across from DroneEntity to DroneEntityRenderer's
    // setAngles() call — the render-state pattern means the model itself never touches the
    // entity directly.
    //? if >=1.21.4 {
    /*public static class State extends net.minecraft.client.render.entity.state.EntityRenderState {
        public float bank;
    }
    *///?}

    /** Fetches the baked model part for {@link #LAYER}, registered via
     *  {@code EntityModelLayerRegistry} in {@code PhotographicaClient}. */
    public static ModelPart create(net.minecraft.client.render.entity.EntityRendererFactory.Context ctx) {
        return ctx.getPart(LAYER);
    }
}
