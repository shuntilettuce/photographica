package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.VideoSettings;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class VideoCameraItem extends Item {

    /** Wired by PhotographicaClient: toggles recording on/off. */
    public static Consumer<ItemStack> clientToggleRecord = stack -> {};
    /** Wired by PhotographicaClient: opens the settings screen. */
    public static Consumer<ItemStack> clientOpenScreen   = stack -> {};

    public VideoCameraItem(Properties settings) {
        super(settings.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack stack = user.getItemInHand(hand);
        if (world.isClientSide()) {
            clientToggleRecord.accept(stack);
        }
        return InteractionResult.SUCCESS;
    }

    public static VideoSettings getSettings(ItemStack stack) {
        VideoSettings s = stack.get(ModDataComponents.VIDEO_SETTINGS);
        return s != null ? s : VideoSettings.DEFAULT;
    }

    public static void setSettings(ItemStack stack, VideoSettings settings) {
        stack.set(ModDataComponents.VIDEO_SETTINGS, settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltipSink, TooltipFlag type) {
        VideoSettings s = getSettings(stack);
        tooltipSink.accept(Component.literal("絞り: F" + s.aperture()).withStyle(ChatFormatting.GRAY));
        tooltipSink.accept(Component.literal("ISO AUTO  AF  24fps").withStyle(ChatFormatting.DARK_GRAY));
    }
}
