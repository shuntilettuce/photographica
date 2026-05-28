package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.VideoSettings;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
//? if <1.21.4 {
import net.minecraft.item.Equipment;
//?}
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
//? if <1.21.4 {
import net.minecraft.util.TypedActionResult;
//?}
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Consumer;

//? if >=1.21.4 {
/*public class VideoCameraItem extends Item {*/
//?} else {
public class VideoCameraItem extends Item implements Equipment {
//?}

    /** Wired by PhotographicaClient: toggles recording on/off. */
    public static Consumer<ItemStack> clientToggleRecord = stack -> {};
    /** Wired by PhotographicaClient: opens the settings screen. */
    public static Consumer<ItemStack> clientOpenScreen   = stack -> {};

    public VideoCameraItem(Settings settings) {
        super(settings.maxCount(1));
    }

    //? if <1.21.4 {
    @Override
    public EquipmentSlot getSlotType() {
        return EquipmentSlot.CHEST;
    }
    //?}

    //? if >=1.21.4 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) {
            clientToggleRecord.accept(stack);
        }
        return ActionResult.SUCCESS;
    }*/
    //?} else {
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient()) {
            clientToggleRecord.accept(stack);
        }
        return TypedActionResult.success(stack, world.isClient());
    }
    //?}

    public static VideoSettings getSettings(ItemStack stack) {
        VideoSettings s = stack.get(ModDataComponents.VIDEO_SETTINGS);
        return s != null ? s : VideoSettings.DEFAULT;
    }

    public static void setSettings(ItemStack stack, VideoSettings settings) {
        stack.set(ModDataComponents.VIDEO_SETTINGS, settings);
    }

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay, java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        VideoSettings s = getSettings(stack);
        tooltipSink.accept(Text.literal("絞り: F" + s.aperture()).formatted(Formatting.GRAY));
        tooltipSink.accept(Text.literal("ISO AUTO  AF  24fps").formatted(Formatting.DARK_GRAY));
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        VideoSettings s = getSettings(stack);
        tooltip.add(Text.literal("絞り: F" + s.aperture()).formatted(Formatting.GRAY));
        tooltip.add(Text.literal("ISO AUTO  AF  24fps").formatted(Formatting.DARK_GRAY));
    }
    //?}
}
