package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.entity.DarkroomBlockEntity;
import dev.hitom.photographica.block.entity.EnlargerBlockEntity;
import dev.hitom.photographica.block.entity.FaxMachineBlockEntity;
import dev.hitom.photographica.block.entity.PhotoFrameBlockEntity;
import dev.hitom.photographica.block.entity.PhotoStandBlockEntity;
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
                    //? if >=1.21.4 {
                    /*net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(DarkroomBlockEntity::new, ModBlocks.DARKROOM).build()*/
                    //?} else {
                    BlockEntityType.Builder.create(DarkroomBlockEntity::new, ModBlocks.DARKROOM).build(null)
                    //?}
            );

    public static final BlockEntityType<PrinterBlockEntity> PRINTER =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "printer"),
                    //? if >=1.21.4 {
                    /*net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(PrinterBlockEntity::new, ModBlocks.PRINTER).build()*/
                    //?} else {
                    BlockEntityType.Builder.create(PrinterBlockEntity::new, ModBlocks.PRINTER).build(null)
                    //?}
            );

    public static final BlockEntityType<EnlargerBlockEntity> ENLARGER =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "enlarger"),
                    //? if >=1.21.4 {
                    /*net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(EnlargerBlockEntity::new, ModBlocks.ENLARGER).build()*/
                    //?} else {
                    BlockEntityType.Builder.create(EnlargerBlockEntity::new, ModBlocks.ENLARGER).build(null)
                    //?}
            );

    public static final BlockEntityType<PhotoFrameBlockEntity> PHOTO_FRAME =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "photo_frame"),
                    //? if >=1.21.4 {
                    /*net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(PhotoFrameBlockEntity::new, ModBlocks.PHOTO_FRAME).build()*/
                    //?} else {
                    BlockEntityType.Builder.create(PhotoFrameBlockEntity::new, ModBlocks.PHOTO_FRAME).build(null)
                    //?}
            );

    public static final BlockEntityType<PhotoStandBlockEntity> PHOTO_STAND =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "photo_stand"),
                    //? if >=1.21.4 {
                    /*net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(PhotoStandBlockEntity::new, ModBlocks.PHOTO_STAND).build()*/
                    //?} else {
                    BlockEntityType.Builder.create(PhotoStandBlockEntity::new, ModBlocks.PHOTO_STAND).build(null)
                    //?}
            );

    public static final BlockEntityType<FaxMachineBlockEntity> FAX_MACHINE =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    Identifier.of(Photographica.MOD_ID, "fax_machine"),
                    //? if >=1.21.4 {
                    /*net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(FaxMachineBlockEntity::new, ModBlocks.FAX_MACHINE).build()*/
                    //?} else {
                    BlockEntityType.Builder.create(FaxMachineBlockEntity::new, ModBlocks.FAX_MACHINE).build(null)
                    //?}
            );

    public static void register() {
        // Class init handles registration; this forces initialization.
    }
}
