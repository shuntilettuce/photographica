package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class SdCardItem extends Item {
    public SdCardItem(Properties settings) {
        super(settings.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltipSink, TooltipFlag type) {
        SdCardData data = stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        tooltipSink.accept(Component.literal("§e" + data.photos().size() + "/" + data.capacity() + "枚保存済"));
        if (!data.photos().isEmpty()) {
            int max = Math.min(3, data.photos().size());
            for (int i = 0; i < max; i++) {
                PhotoData p = data.photos().get(i);
                tooltipSink.accept(Component.literal("§8  " + p.photographer() + " @(" + p.x() + "," + p.y() + "," + p.z() + ")"));
            }
            if (data.photos().size() > max) {
                tooltipSink.accept(Component.literal("§8  ...他" + (data.photos().size() - max) + "枚"));
            }
        }
    }

    public static SdCardData getSdCard(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
    }

    public static void setSdCard(ItemStack stack, SdCardData data) {
        stack.set(ModDataComponents.SD_CARD, data);
    }

    public static boolean hasSdCard(ItemStack stack) {
        return stack.has(ModDataComponents.SD_CARD);
    }
}
