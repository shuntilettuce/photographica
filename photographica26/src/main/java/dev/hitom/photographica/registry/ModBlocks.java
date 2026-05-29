package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.DarkroomBlock;
import dev.hitom.photographica.block.EnlargerBlock;
import dev.hitom.photographica.block.PhotoFrameBlock;
import dev.hitom.photographica.block.PhotoStandBlock;
import dev.hitom.photographica.block.PrinterBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;

public final class ModBlocks {
    private ModBlocks() {}

    public static final Block DARKROOM = Registry.register(
            BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "darkroom"),
            new DarkroomBlock(bs("darkroom")
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlock.Instrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(2.5f)));

    public static final Block PRINTER = Registry.register(
            BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "printer"),
            new PrinterBlock(bs("printer")
                    .mapColor(MapColor.METAL)
                    .instrument(NoteBlock.Instrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.0f)));

    public static final Block ENLARGER = Registry.register(
            BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "enlarger"),
            new EnlargerBlock(bs("enlarger")
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlock.Instrument.BASS)
                    .requiresCorrectToolForDrops()
                    .strength(2.5f)));

    public static final Block PHOTO_FRAME = Registry.register(
            BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "photo_frame"),
            new PhotoFrameBlock(bs("photo_frame")
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlock.Instrument.BASS)
                    .strength(0.5f)
                    .noOcclusion()));

    public static final Block PHOTO_STAND = Registry.register(
            BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "photo_stand"),
            new PhotoStandBlock(bs("photo_stand")
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlock.Instrument.BASS)
                    .strength(0.5f)
                    .noOcclusion()));

    public static void register() {
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "darkroom"),
                new BlockItem(DARKROOM, is("darkroom")));
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "printer"),
                new BlockItem(PRINTER, is("printer")));
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "enlarger"),
                new BlockItem(ENLARGER, is("enlarger")));
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "photo_frame"),
                new BlockItem(PHOTO_FRAME, is("photo_frame")));
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "photo_stand"),
                new BlockItem(PHOTO_STAND, is("photo_stand")));

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(DARKROOM.asItem());
            entries.accept(PRINTER.asItem());
            entries.accept(ENLARGER.asItem());
            entries.accept(PHOTO_FRAME.asItem());
            entries.accept(PHOTO_STAND.asItem());
        });
    }

    private static BlockBehaviour.Properties bs(String name) {
        ResourceKey<Block> k = ResourceKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, name));
        return BlockBehaviour.Properties.of().setId(k);
    }

    private static Item.Properties is(String name) {
        ResourceKey<Item> k = ResourceKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, name));
        return new Item.Properties().setId(k);
    }
}
