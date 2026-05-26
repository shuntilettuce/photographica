package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.VideoSettings;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.Consumer;

public class VideoCameraItem extends Item implements Equipment {

    /** Wired by PhotographicaClient: toggles recording on/off. */
    public static Consumer<ItemStack> clientToggleRecord = stack -> {};
    /** Wired by PhotographicaClient: opens the settings screen. */
    public static Consumer<ItemStack> clientOpenScreen   = stack -> {};

    public VideoCameraItem(Settings settings) {
        super(settings.maxCount(1));
    }

    @Override
    public EquipmentSlot getSlotType() {
        return EquipmentSlot.CHEST;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (world.isClient) {
            if (user.isSneaking()) {
                clientOpenScreen.accept(stack);
            } else {
                clientToggleRecord.accept(stack);
            }
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    public static VideoSettings getSettings(ItemStack stack) {
        VideoSettings s = stack.get(ModDataComponents.VIDEO_SETTINGS);
        return s != null ? s : VideoSettings.DEFAULT;
    }

    public static void setSettings(ItemStack stack, VideoSettings settings) {
        stack.set(ModDataComponents.VIDEO_SETTINGS, settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        VideoSettings s = getSettings(stack);
        tooltip.add(Text.literal("絞り: F" + s.aperture()).formatted(Formatting.GRAY));
        tooltip.add(Text.literal("ISO AUTO  AF  24fps").formatted(Formatting.DARK_GRAY));
    }
}
