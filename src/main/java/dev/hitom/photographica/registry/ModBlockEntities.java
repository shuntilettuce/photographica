package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.entity.CameraStandBlockEntity;
import dev.hitom.photographica.block.entity.DarkroomBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final BlockEntityType<CameraStandBlockEntity> CAMERA_STAND =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "camera_stand"),
                    BlockEntityType.Builder.create(CameraStandBlockEntity::new, ModBlocks.CAMERA_STAND).build(null)
            );

    public static final BlockEntityType<DarkroomBlockEntity> DARKROOM =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "darkroom"),
                    BlockEntityType.Builder.create(DarkroomBlockEntity::new, ModBlocks.DARKROOM).build(null)
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
