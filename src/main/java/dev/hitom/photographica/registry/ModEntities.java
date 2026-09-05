package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.entity.DroneEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
    private ModEntities() {}

    private static final RegistryKey<EntityType<?>> DRONE_KEY =
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(Photographica.MOD_ID, "drone"));

    public static final EntityType<DroneEntity> DRONE = Registry.register(
            Registries.ENTITY_TYPE,
            DRONE_KEY,
            //? if >=1.21.4 {
            /*FabricEntityTypeBuilder.<DroneEntity>create(SpawnGroup.MISC, DroneEntity::new)
                    // Matches DroneEntityModel's actual geometry (X/Z span -7..7 = 0.875
                    // blocks, Y spans 0..4 = 0.25 blocks — measured directly from the model's
                    // cuboids, not guessed) with a small margin. The previous 0.7×0.5 box was
                    // never actually measured against the model at all: too narrow to contain
                    // the full rotor-arm span (letting the visible blades poke through nearby
                    // terrain the hitbox itself was clear of) and — worse — paired with
                    // DronePilot's own collision box, which treated its tracked position as
                    // the drone's *center* while this dimensions object (like every vanilla
                    // entity) treats Y as the *feet*. That mismatch put the felt "floor" a
                    // half-collision-box-height below where the entity's real feet actually
                    // sat, so flying it down to where it visually looked grounded actually
                    // buried its feet in the block below — see DronePilot.HALF_WIDTH/HEIGHT
                    // for the matching fix on that side.
                    .dimensions(EntityDimensions.fixed(0.9f, 0.3f))
                    // Must comfortably exceed DroneEntity.MAX_REMOTE_RANGE (128): a drone is
                    // routinely flown to the far edge of its radio range, and at the old 64 it
                    // untracked at half that — taking its camera settings (zoom, AF) and the
                    // HUD's channel/altitude readout with it. The extra margin past 128 covers
                    // an airframe that coasts on after losing signal, so the pilot can still
                    // watch it come down. Note the server clamps this to its own view distance,
                    // which is why DronePilot never relies on the entity being loaded.
                    .trackRangeBlocks(192)
                    .trackedUpdateRate(3)
                    .build(DRONE_KEY)*/
            //?} else {
            FabricEntityTypeBuilder.<DroneEntity>create(SpawnGroup.MISC, DroneEntity::new)
                    // Matches DroneEntityModel's actual geometry (X/Z span -7..7 = 0.875
                    // blocks, Y spans 0..4 = 0.25 blocks — measured directly from the model's
                    // cuboids, not guessed) with a small margin. The previous 0.7×0.5 box was
                    // never actually measured against the model at all: too narrow to contain
                    // the full rotor-arm span (letting the visible blades poke through nearby
                    // terrain the hitbox itself was clear of) and — worse — paired with
                    // DronePilot's own collision box, which treated its tracked position as
                    // the drone's *center* while this dimensions object (like every vanilla
                    // entity) treats Y as the *feet*. That mismatch put the felt "floor" a
                    // half-collision-box-height below where the entity's real feet actually
                    // sat, so flying it down to where it visually looked grounded actually
                    // buried its feet in the block below — see DronePilot.HALF_WIDTH/HEIGHT
                    // for the matching fix on that side.
                    .dimensions(EntityDimensions.fixed(0.9f, 0.3f))
                    // Must comfortably exceed DroneEntity.MAX_REMOTE_RANGE (128): a drone is
                    // routinely flown to the far edge of its radio range, and at the old 64 it
                    // untracked at half that — taking its camera settings (zoom, AF) and the
                    // HUD's channel/altitude readout with it. The extra margin past 128 covers
                    // an airframe that coasts on after losing signal, so the pilot can still
                    // watch it come down. Note the server clamps this to its own view distance,
                    // which is why DronePilot never relies on the entity being loaded.
                    .trackRangeBlocks(192)
                    .trackedUpdateRate(3)
                    .build()
            //?}
    );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
