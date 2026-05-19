package dev.hitom.photographica.registry;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.block.CameraStandBlock;
import dev.hitom.photographica.block.DarkroomBlock;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.Instrument;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
    private ModBlocks() {}

    public static final Block CAMERA_STAND = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "camera_stand"),
            new CameraStandBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.STONE_GRAY)
                    .instrument(Instrument.BASEDRUM)
                    .requiresTool()
                    .strength(2.5f))
    );

    public static final Block DARKROOM = Registry.register(
            Registries.BLOCK,
            Identifier.of(Photographica.MOD_ID, "darkroom"),
            new DarkroomBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.STONE_GRAY)
                    .instrument(Instrument.BASEDRUM)
                    .requiresTool()
                    .strength(2.5f))
    );

    public static void register() {
        // Register BlockItems
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "camera_stand"),
                new BlockItem(CAMERA_STAND, new Item.Settings()));
        Registry.register(Registries.ITEM,
                Identifier.of(Photographica.MOD_ID, "darkroom"),
                new BlockItem(DARKROOM, new Item.Settings()));

        // Add to creative tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(CAMERA_STAND.asItem());
            entries.add(DARKROOM.asItem());
        });
    }
}
