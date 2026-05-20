package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.entity.DarkroomBlockEntity;
import dev.hitom.photographica.block.entity.EnlargerBlockEntity;
import dev.hitom.photographica.block.entity.PrinterBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final BlockEntityType<DarkroomBlockEntity> DARKROOM =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "darkroom"),
                    BlockEntityType.Builder.create(DarkroomBlockEntity::new, ModBlocks.DARKROOM).build(null)
            );

    public static final BlockEntityType<PrinterBlockEntity> PRINTER =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "printer"),
                    BlockEntityType.Builder.create(PrinterBlockEntity::new, ModBlocks.PRINTER).build(null)
            );

    public static final BlockEntityType<EnlargerBlockEntity> ENLARGER =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "enlarger"),
                    BlockEntityType.Builder.create(EnlargerBlockEntity::new, ModBlocks.ENLARGER).build(null)
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
