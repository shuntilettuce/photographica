package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.DarkroomBlock;
import dev.hitom.photographica.block.EnlargerBlock;
import dev.hitom.photographica.block.PhotoFrameBlock;
import dev.hitom.photographica.block.PhotoStandBlock;
import dev.hitom.photographica.block.PrinterBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
    private ModBlocks() {}

    //? if >=1.21.11 {
    /*public static final Block DARKROOM = Registry.register(
            Registries.BLOCK, Identifier.of(Photographica.MOD_ID, "darkroom"),
            new DarkroomBlock(bs("darkroom")
                    .mapColor(MapColor.STONE_GRAY)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresTool()
                    .strength(2.5f)));

    public static final Block PRINTER = Registry.register(
            Registries.BLOCK, Identifier.of(Photographica.MOD_ID, "printer"),
            new PrinterBlock(bs("printer")
                    .mapColor(MapColor.IRON_GRAY)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresTool()
                    .strength(3.0f)));

    public static final Block ENLARGER = Registry.register(
            Registries.BLOCK, Identifier.of(Photographica.MOD_ID, "enlarger"),
            new EnlargerBlock(bs("enlarger")
                    .mapColor(MapColor.OAK_TAN)
                    .instrument(NoteBlockInstrument.BASS)
                    .requiresTool()
                    .strength(2.5f)));

    public static final Block PHOTO_FRAME = Registry.register(
            Registries.BLOCK, Identifier.of(Photographica.MOD_ID, "photo_frame"),
            new PhotoFrameBlock(bs("photo_frame")
                    .mapColor(MapColor.OAK_TAN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(0.5f)
                    .nonOpaque()));

    public static final Block PHOTO_STAND = Registry.register(
            Registries.BLOCK, Identifier.of(Photographica.MOD_ID, "photo_stand"),
            new PhotoStandBlock(bs("photo_stand")
                    .mapColor(MapColor.OAK_TAN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(0.5f)
                    .nonOpaque()));*/
    //?} else {
    public static final Block DARKROOM = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "darkroom"),
            new DarkroomBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.STONE_GRAY)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresTool()
                    .strength(2.5f))
    );

    public static final Block PRINTER = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "printer"),
            new PrinterBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.IRON_GRAY)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresTool()
                    .strength(3.0f))
    );

    public static final Block ENLARGER = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "enlarger"),
            new EnlargerBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.OAK_TAN)
                    .instrument(NoteBlockInstrument.BASS)
                    .requiresTool()
                    .strength(2.5f))
    );

    public static final Block PHOTO_FRAME = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "photo_frame"),
            new PhotoFrameBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.OAK_TAN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(0.5f)
                    .nonOpaque())
    );

    public static final Block PHOTO_STAND = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "photo_stand"),
            new PhotoStandBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.OAK_TAN)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(0.5f)
                    .nonOpaque())
    );
    //?}

    public static void register() {
        //? if >=1.21.11 {
        /*Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, "darkroom"),
                new BlockItem(DARKROOM, is("darkroom")));
        Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, "printer"),
                new BlockItem(PRINTER, is("printer")));
        Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, "enlarger"),
                new BlockItem(ENLARGER, is("enlarger")));
        Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, "photo_frame"),
                new BlockItem(PHOTO_FRAME, is("photo_frame")));
        Registry.register(Registries.ITEM, Identifier.of(Photographica.MOD_ID, "photo_stand"),
                new BlockItem(PHOTO_STAND, is("photo_stand")));*/
        //?} else {
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "darkroom"),
                new BlockItem(DARKROOM, new Item.Settings()));
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "printer"),
                new BlockItem(PRINTER, new Item.Settings()));
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "enlarger"),
                new BlockItem(ENLARGER, new Item.Settings()));
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "photo_frame"),
                new BlockItem(PHOTO_FRAME, new Item.Settings()));
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "photo_stand"),
                new BlockItem(PHOTO_STAND, new Item.Settings()));
        //?}

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(DARKROOM.asItem());
            entries.add(PRINTER.asItem());
            entries.add(ENLARGER.asItem());
            entries.add(PHOTO_FRAME.asItem());
            entries.add(PHOTO_STAND.asItem());
        });
    }

    //? if >=1.21.11 {
    /*private static AbstractBlock.Settings bs(String name) {
        net.minecraft.registry.RegistryKey<Block> k = net.minecraft.registry.RegistryKey.of(
            net.minecraft.registry.RegistryKeys.BLOCK, Identifier.of(Photographica.MOD_ID, name));
        return AbstractBlock.Settings.create().registryKey(k);
    }

    private static Item.Settings is(String name) {
        net.minecraft.registry.RegistryKey<Item> k = net.minecraft.registry.RegistryKey.of(
            net.minecraft.registry.RegistryKeys.ITEM, Identifier.of(Photographica.MOD_ID, name));
        return new Item.Settings().registryKey(k);
    }*/
    //?}
}
