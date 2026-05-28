package dev.hitom.photographica.item;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;

import java.util.List;

public class SdCardItem extends Item {
    public SdCardItem(Settings settings) {
        super(settings.maxCount(1));
    }

    //? if >=1.21.11 {
    /*@Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, net.minecraft.component.type.TooltipDisplayComponent tooltipDisplay, java.util.function.Consumer<Text> tooltipSink, TooltipType type) {
        SdCardData data = stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        tooltipSink.accept(Text.literal("§e" + data.photos().size() + "/" + data.capacity() + "枚保存済"));
        if (!data.photos().isEmpty()) {
            int max = Math.min(3, data.photos().size());
            for (int i = 0; i < max; i++) {
                PhotoData p = data.photos().get(i);
                tooltipSink.accept(Text.literal("§8  " + p.photographer() + " @(" + p.x() + "," + p.y() + "," + p.z() + ")"));
            }
            if (data.photos().size() > max) {
                tooltipSink.accept(Text.literal("§8  ...他" + (data.photos().size() - max) + "枚"));
            }
        }
    }*/
    //?} else {
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        SdCardData data = stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        tooltip.add(Text.literal("§e" + data.photos().size() + "/" + data.capacity() + "枚保存済"));
        if (!data.photos().isEmpty()) {
            int max = Math.min(3, data.photos().size());
            for (int i = 0; i < max; i++) {
                PhotoData p = data.photos().get(i);
                tooltip.add(Text.literal("§8  " + p.photographer() + " @(" + p.x() + "," + p.y() + "," + p.z() + ")"));
            }
            if (data.photos().size() > max) {
                tooltip.add(Text.literal("§8  ...他" + (data.photos().size() - max) + "枚"));
            }
        }
    }
    //?}

    public static SdCardData getSdCard(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
    }

    public static void setSdCard(ItemStack stack, SdCardData data) {
        stack.set(ModDataComponents.SD_CARD, data);
    }

    public static boolean hasSdCard(ItemStack stack) {
        return stack.contains(ModDataComponents.SD_CARD);
    }
}
