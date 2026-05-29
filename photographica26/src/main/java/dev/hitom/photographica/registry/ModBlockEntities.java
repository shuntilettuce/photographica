package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.entity.DarkroomBlockEntity;
import dev.hitom.photographica.block.entity.EnlargerBlockEntity;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
import dev.hitom.photographica.block.entity.PhotoStandBlockEntity;
import dev.hitom.photographica.block.entity.PrinterBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

public final class ModBlockEntities {
    private ModBlockEntities() {}

    public static final BlockEntityType<DarkroomBlockEntity> DARKROOM =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "darkroom"),
                    FabricBlockEntityTypeBuilder.create(DarkroomBlockEntity::new, ModBlocks.DARKROOM).build()
            );

    public static final BlockEntityType<PrinterBlockEntity> PRINTER =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "printer"),
                    FabricBlockEntityTypeBuilder.create(PrinterBlockEntity::new, ModBlocks.PRINTER).build()
            );

    public static final BlockEntityType<EnlargerBlockEntity> ENLARGER =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "enlarger"),
                    FabricBlockEntityTypeBuilder.create(EnlargerBlockEntity::new, ModBlocks.ENLARGER).build()
            );

    public static final BlockEntityType<PhotoFrameBlockEntity> PHOTO_FRAME =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "photo_frame"),
                    FabricBlockEntityTypeBuilder.create(PhotoFrameBlockEntity::new, ModBlocks.PHOTO_FRAME).build()
            );

    public static final BlockEntityType<PhotoStandBlockEntity> PHOTO_STAND =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "photo_stand"),
                    FabricBlockEntityTypeBuilder.create(PhotoStandBlockEntity::new, ModBlocks.PHOTO_STAND).build()
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
